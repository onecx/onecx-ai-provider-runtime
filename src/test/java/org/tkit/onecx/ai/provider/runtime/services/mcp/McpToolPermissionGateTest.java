package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolSpecification;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;

class McpToolPermissionGateTest {

    private static final String WRITE = "onecx:event#mcp-write";
    private static final String READ = "onecx:event#mcp-read";
    private static final Map<String, String> HEADERS = Map.of("apm-principal-token", "user-token");

    private PermissionLookupService permissionLookup;
    private McpToolPermissionGate gate;
    private ToolSnapshotDTO tool;

    @BeforeEach
    void setUp() {
        permissionLookup = mock(PermissionLookupService.class);
        gate = new McpToolPermissionGate();
        gate.permissionLookup = permissionLookup;
        gate.principalTokenHeader = "apm-principal-token";

        tool = new ToolSnapshotDTO();
        tool.setName("event-management");
        tool.setType("MCP");
        tool.setProductName("onecx-event-management");
        tool.setAppId("onecx-event-management-mcp");
    }

    @Test
    void filter_returnsAllToolsWithoutLookup_whenNoToolIsGated() {
        var plain = spec("plain", null);

        var result = gate.filter(tool, List.of(plain), HEADERS, new HashMap<>());

        assertThat(result).containsExactly(plain);
        verifyNoInteractions(permissionLookup);
    }

    @Test
    void filter_keepsOnlyGatedToolsTheUserHoldsAndKeepsUngatedTools() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(permissions(Map.of("onecx:event", Set.of("mcp-write"))));
        var write = spec("createEvent", WRITE);
        var read = spec("getEvents", READ);
        var plain = spec("ping", null);

        var result = gate.filter(tool, List.of(write, read, plain), HEADERS, new HashMap<>());

        assertThat(result).containsExactly(write, plain);
    }

    @Test
    void filter_hidesGatedToolsWhenTokenMissing_withoutLookup() {
        var write = spec("createEvent", WRITE);
        var plain = spec("ping", null);

        var result = gate.filter(tool, List.of(write, plain), Map.of(), new HashMap<>());

        assertThat(result).containsExactly(plain);
        verifyNoInteractions(permissionLookup);
    }

    @Test
    void filter_hidesGatedToolsWhenProductOrAppMissing_withoutLookup() {
        tool.setProductName(null);
        var write = spec("createEvent", WRITE);

        var result = gate.filter(tool, List.of(write), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
        verifyNoInteractions(permissionLookup);
    }

    @Test
    void filter_failsClosedForGatedToolsButKeepsPlainTools_whenLookupFails() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("permission-svc down"));
        var write = spec("createEvent", WRITE);
        var plain = spec("ping", null);

        var result = gate.filter(tool, List.of(write, plain), HEADERS, new HashMap<>());

        assertThat(result).containsExactly(plain);
    }

    @Test
    void filter_hidesToolsWithMalformedRequiredPermission() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(permissions(Map.of("onecx:event", Set.of("mcp-write"))));
        var noSeparator = spec("a", "onecx:event");
        var emptyAction = spec("b", "onecx:event#");

        var result = gate.filter(tool, List.of(noSeparator, emptyAction), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
    }

    @Test
    void filter_hidesGatedToolsWhenUserHasNoPermissionsForResource() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(permissions(Map.of("onecx:other", Set.of("mcp-write"))));

        var result = gate.filter(tool, List.of(spec("createEvent", WRITE)), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
    }

    @Test
    void filter_looksUpEachProductAppOncePerTurn() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(permissions(Map.of("onecx:event", Set.of("mcp-write"))));
        Map<String, Optional<ApplicationPermissions>> lookups = new HashMap<>();

        gate.filter(tool, List.of(spec("a", WRITE)), HEADERS, lookups);
        gate.filter(tool, List.of(spec("b", WRITE)), HEADERS, lookups);

        verify(permissionLookup, times(1)).getApplicationPermissions(
                org.mockito.ArgumentMatchers.eq("onecx-event-management"),
                org.mockito.ArgumentMatchers.eq("onecx-event-management-mcp"),
                any());
    }

    @Test
    void filter_hidesGatedToolWhenRequiredPermissionIsNotAString() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(permissions(Map.of("onecx:event", Set.of("mcp-write"))));
        var nonString = ToolSpecification.builder().name("odd")
                .addMetadata(McpToolPermissionGate.REQUIRED_PERMISSION_META_KEY, Map.of("action", "mcp-write"))
                .build();

        var result = gate.filter(tool, List.of(nonString, spec("plain", null)), HEADERS, new HashMap<>());

        assertThat(result).containsExactly(spec("plain", null));
    }

    @Test
    void requiredPermissionForDiscovery_returnsStringPermissionAsIs() {
        var spec = spec("tool", "onecx:event#mcp-write");

        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(spec)).isEqualTo("onecx:event#mcp-write");
    }

    @Test
    void requiredPermissionForDiscovery_returnsMalformedMarkerForNonStringValue() {
        var spec = specWithNonStringMeta("tool");

        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(spec))
                .isEqualTo(McpToolPermissionGate.MALFORMED_PERMISSION_MARKER);
    }

    @Test
    void requiredPermissionForDiscovery_returnsMalformedMarkerForMalformedString() {
        var noSeparator = spec("a", "onecx:event");
        var emptyAction = spec("b", "onecx:event#");
        var emptyResource = spec("c", "#mcp-write");
        var twoSeparators = spec("d", "onecx:event#mcp-write#extra");

        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(noSeparator))
                .isEqualTo(McpToolPermissionGate.MALFORMED_PERMISSION_MARKER);
        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(emptyAction))
                .isEqualTo(McpToolPermissionGate.MALFORMED_PERMISSION_MARKER);
        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(emptyResource))
                .isEqualTo(McpToolPermissionGate.MALFORMED_PERMISSION_MARKER);
        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(twoSeparators))
                .isEqualTo(McpToolPermissionGate.MALFORMED_PERMISSION_MARKER);
    }

    @Test
    void requiredPermissionForDiscovery_returnsNullWhenNotGated() {
        var spec = spec("plain", null);

        assertThat(McpToolPermissionGate.requiredPermissionForDiscovery(spec)).isNull();
    }

    @Test
    void isGranted_returnsFalseForNullOrMalformedRequirement() {
        var granted = Optional.of(permissions(Map.of("onecx:event", Set.of("mcp-write"))));

        assertThat(McpToolPermissionGate.isGranted(granted, null)).isFalse();
        assertThat(McpToolPermissionGate.isGranted(granted, "onecx:event#")).isFalse();
        assertThat(McpToolPermissionGate.isGranted(granted, "onecx:event#mcp-write#extra")).isFalse();
    }

    @Test
    void filter_hidesGatedToolWhenLookupReturnsNoPermissions() {
        when(permissionLookup.getApplicationPermissions(anyString(), anyString(), any())).thenReturn(null);

        var result = gate.filter(tool, List.of(spec("createEvent", WRITE)), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
    }

    private static ToolSpecification spec(String name, String requiredPermission) {
        var builder = ToolSpecification.builder().name(name);
        if (requiredPermission != null) {
            builder.addMetadata(McpToolPermissionGate.REQUIRED_PERMISSION_META_KEY, requiredPermission);
        }
        return builder.build();
    }

    private static ToolSpecification specWithNonStringMeta(String name) {
        return ToolSpecification.builder().name(name)
                .addMetadata(McpToolPermissionGate.REQUIRED_PERMISSION_META_KEY, 42)
                .build();
    }

    private static ApplicationPermissions permissions(Map<String, Set<String>> permissions) {
        return new ApplicationPermissions().permissions(permissions);
    }
}
