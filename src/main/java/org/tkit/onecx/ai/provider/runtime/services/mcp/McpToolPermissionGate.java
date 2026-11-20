package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.langchain4j.agent.tool.ToolSpecification;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;
import lombok.extern.slf4j.Slf4j;

/**
 * Hides MCP tools whose {@code _meta.requiredPermission} the current user does not hold.
 * Lookups are fail-closed: when the user token or coordinates are missing, or the lookup fails,
 * only the gated tools are removed and all other tools are kept.
 */
@Slf4j
@ApplicationScoped
public class McpToolPermissionGate {

    static final String REQUIRED_PERMISSION_META_KEY = "requiredPermission";
    static final String MALFORMED_PERMISSION_MARKER = "<malformed>";
    private static final String SEPARATOR = "#";

    @ConfigProperty(name = "tkit.rs.context.token.header-param")
    String principalTokenHeader;

    @Inject
    PermissionLookupService permissionLookup;

    /**
     * Returns the tools the user may see. The {@code lookups} map is scoped to one agent build so that each
     * product/application pair is queried at most once per agent; the 5-minute cache absorbs repeats across agents.
     */
    public List<ToolSpecification> filter(ToolSnapshotDTO tool, List<ToolSpecification> specs,
            Map<String, String> headers, Map<String, Optional<ApplicationPermissions>> lookups) {
        boolean hasGated = specs.stream().anyMatch(McpToolPermissionGate::isGated);
        if (!hasGated) {
            return specs;
        }
        Optional<ApplicationPermissions> permissions = lookup(tool, headers, lookups);
        List<ToolSpecification> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            if (!isGated(spec) || isGranted(permissions, requiredPermission(spec))) {
                allowed.add(spec);
            } else {
                denied.add(spec.name());
            }
        }
        if (!denied.isEmpty()) {
            log.info("MCP server '{}': {} tool(s) hidden by required OneCX permission: {}",
                    tool.getName(), denied.size(), String.join(", ", denied));
        }
        return List.copyOf(allowed);
    }

    static boolean isGated(ToolSpecification spec) {
        return spec.metadata() != null && spec.metadata().get(REQUIRED_PERMISSION_META_KEY) != null;
    }

    /**
     * Returns the declared permission, or {@code null} when the metadata value is not a string. A null value is
     * treated as malformed and never granted, so the tool stays hidden.
     */
    static String requiredPermission(ToolSpecification spec) {
        if (spec.metadata() == null) {
            return null;
        }
        return spec.metadata().get(REQUIRED_PERMISSION_META_KEY) instanceof String value ? value : null;
    }

    /**
     * Returns the declared permission for discovery responses. Unlike {@link #requiredPermission}, a non-string
     * metadata value is reported as {@link #MALFORMED_PERMISSION_MARKER} so the discovery output matches runtime
     * behavior: the tool is gated (hidden) at runtime, and the discovery response shows it as gated with a
     * malformed marker instead of {@code null} (which would imply ungated). A string that does not match the
     * {@code <resource>#<action>} format is also reported as {@link #MALFORMED_PERMISSION_MARKER}, mirroring the
     * format validation in {@link #isGranted}.
     */
    static String requiredPermissionForDiscovery(ToolSpecification spec) {
        Object value = spec.metadata() != null ? spec.metadata().get(REQUIRED_PERMISSION_META_KEY) : null;
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            return MALFORMED_PERMISSION_MARKER;
        }
        return parsePermission(s) != null ? s : MALFORMED_PERMISSION_MARKER;
    }

    /**
     * Checks whether the resolved permissions grant the required {@code <resource>#<action>}. The permission
     * response maps each resource to its granted actions, so the check is an exact match: a missing resource
     * or action means the tool is not granted.
     */
    static boolean isGranted(Optional<ApplicationPermissions> permissions, String required) {
        String[] parts = parsePermission(required);
        if (parts == null || permissions.isEmpty() || permissions.get().getPermissions() == null) {
            return false;
        }
        Set<String> actions = permissions.get().getPermissions().get(parts[0]);
        return actions != null && actions.contains(parts[1]);
    }

    /**
     * Splits the permission string on the {@code #} separator and validates the {@code <resource>#<action>}
     * format. Returns the two non-blank parts, or {@code null} when the value is {@code null} or malformed
     * (missing separator, more than one separator, or blank resource/action).
     */
    static String[] parsePermission(String required) {
        if (required == null) {
            return null;
        }
        String[] parts = required.split(SEPARATOR, -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return parts;
    }

    private Optional<ApplicationPermissions> lookup(ToolSnapshotDTO tool, Map<String, String> headers,
            Map<String, Optional<ApplicationPermissions>> lookups) {
        String token = headers != null ? headers.get(principalTokenHeader) : null;
        String product = tool.getProductName();
        String app = tool.getAppId();
        if (token == null || token.isBlank() || product == null || product.isBlank() || app == null
                || app.isBlank()) {
            log.warn("MCP server '{}': cannot look up OneCX permissions (token or product/app missing); "
                    + "gated tools are hidden", tool.getName());
            return Optional.empty();
        }
        return lookups.computeIfAbsent(product + "/" + app, key -> fetch(tool, product, app, token));
    }

    private Optional<ApplicationPermissions> fetch(ToolSnapshotDTO tool, String product, String app, String token) {
        try {
            return Optional.ofNullable(permissionLookup.getApplicationPermissions(product, app, token));
        } catch (Exception ex) {
            log.warn("MCP server '{}': OneCX permission lookup failed, gated tools are hidden: {}: {}",
                    tool.getName(), ex.getClass().getSimpleName(), ex.getMessage());
            log.debug("OneCX permission lookup failure details for {}", tool.getName(), ex);
            return Optional.empty();
        }
    }
}
