package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import gen.org.tkit.onecx.permission.api.PermissionApi;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;
import gen.org.tkit.onecx.permission.model.PermissionRequest;
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
    static final String PRINCIPAL_TOKEN_HEADER = "apm-principal-token";
    private static final String SEPARATOR = "#";

    @Inject
    @RestClient
    PermissionApi permissionApi;

    @Inject
    DispatchConfig dispatchConfig;

    /**
     * Returns the tools the user may see. The {@code lookups} map is scoped to one turn so that each
     * product/application pair is queried at most once per turn.
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

    static String requiredPermission(ToolSpecification spec) {
        return String.valueOf(spec.metadata().get(REQUIRED_PERMISSION_META_KEY));
    }

    static boolean isGranted(Optional<ApplicationPermissions> permissions, String required) {
        if (permissions.isEmpty() || permissions.get().getPermissions() == null) {
            return false;
        }
        String[] parts = required.split(SEPARATOR, -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return false;
        }
        Set<String> actions = permissions.get().getPermissions().get(parts[0]);
        return actions != null && actions.contains(parts[1]);
    }

    private Optional<ApplicationPermissions> lookup(ToolSnapshotDTO tool, Map<String, String> headers,
            Map<String, Optional<ApplicationPermissions>> lookups) {
        String token = headers != null ? headers.get(PRINCIPAL_TOKEN_HEADER) : null;
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
            Duration timeout = Duration.ofSeconds(dispatchConfig.toolConfig().maxTimeout());
            ApplicationPermissions result = permissionApi
                    .getApplicationPermissions(product, app, new PermissionRequest().token(token))
                    .await().atMost(timeout);
            return Optional.ofNullable(result);
        } catch (Exception ex) {
            log.warn("MCP server '{}': OneCX permission lookup failed, gated tools are hidden: {}: {}",
                    tool.getName(), ex.getClass().getSimpleName(), ex.getMessage());
            log.debug("OneCX permission lookup failure details for {}", tool.getName(), ex);
            return Optional.empty();
        }
    }
}
