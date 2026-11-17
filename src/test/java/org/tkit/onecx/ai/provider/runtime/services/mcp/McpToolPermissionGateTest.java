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
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import gen.org.tkit.onecx.permission.api.PermissionApi;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;
import io.smallrye.mutiny.Uni;

class McpToolPermissionGateTest {

    private static final String WRITE = "onecx:event#mcp-write";
    private static final String READ = "onecx:event#mcp-read";
    private static final Map<String, String> HEADERS = Map.of("apm-principal-token", "user-token");

    private PermissionApi permissionApi;
    private McpToolPermissionGate gate;
    private ToolSnapshotDTO tool;

    @BeforeEach
    void setUp() {
        permissionApi = mock(PermissionApi.class);
        DispatchConfig dispatchConfig = mock(DispatchConfig.class);
        DispatchConfig.ToolConfig toolConfig = mock(DispatchConfig.ToolConfig.class);
        when(dispatchConfig.toolConfig()).thenReturn(toolConfig);
        when(toolConfig.maxTimeout()).thenReturn(1L);
        gate = new McpToolPermissionGate();
        gate.permissionApi = permissionApi;
        gate.dispatchConfig = dispatchConfig;

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
        verifyNoInteractions(permissionApi);
    }

    @Test
    void filter_keepsOnlyGatedToolsTheUserHoldsAndKeepsUngatedTools() {
        when(permissionApi.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(permissions(Map.of("onecx:event", Set.of("mcp-write")))));
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
        verifyNoInteractions(permissionApi);
    }

    @Test
    void filter_hidesGatedToolsWhenProductOrAppMissing_withoutLookup() {
        tool.setProductName(null);
        var write = spec("createEvent", WRITE);

        var result = gate.filter(tool, List.of(write), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
        verifyNoInteractions(permissionApi);
    }

    @Test
    void filter_failsClosedForGatedToolsButKeepsPlainTools_whenLookupFails() {
        when(permissionApi.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("permission-svc down")));
        var write = spec("createEvent", WRITE);
        var plain = spec("ping", null);

        var result = gate.filter(tool, List.of(write, plain), HEADERS, new HashMap<>());

        assertThat(result).containsExactly(plain);
    }

    @Test
    void filter_hidesToolsWithMalformedRequiredPermission() {
        when(permissionApi.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(permissions(Map.of("onecx:event", Set.of("mcp-write")))));
        var noSeparator = spec("a", "onecx:event");
        var emptyAction = spec("b", "onecx:event#");

        var result = gate.filter(tool, List.of(noSeparator, emptyAction), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
    }

    @Test
    void filter_hidesGatedToolsWhenUserHasNoPermissionsForResource() {
        when(permissionApi.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(permissions(Map.of("onecx:other", Set.of("mcp-write")))));

        var result = gate.filter(tool, List.of(spec("createEvent", WRITE)), HEADERS, new HashMap<>());

        assertThat(result).isEmpty();
    }

    @Test
    void filter_looksUpEachProductAppOncePerTurn() {
        when(permissionApi.getApplicationPermissions(anyString(), anyString(), any()))
                .thenReturn(Uni.createFrom().item(permissions(Map.of("onecx:event", Set.of("mcp-write")))));
        Map<String, Optional<ApplicationPermissions>> lookups = new HashMap<>();

        gate.filter(tool, List.of(spec("a", WRITE)), HEADERS, lookups);
        gate.filter(tool, List.of(spec("b", WRITE)), HEADERS, lookups);

        verify(permissionApi, times(1)).getApplicationPermissions(
                org.mockito.ArgumentMatchers.eq("onecx-event-management"),
                org.mockito.ArgumentMatchers.eq("onecx-event-management-mcp"),
                any());
    }

    @Test
    void toolSetHash_isStableForSameToolsInDifferentOrder() {
        var a = new McpTool("server", "http://a", spec("alpha", null), null);
        var b = new McpTool("server", "http://a", spec("beta", null), null);

        assertThat(new McpToolRegistry(List.of(a, b)).toolSetHash())
                .isEqualTo(new McpToolRegistry(List.of(b, a)).toolSetHash())
                .hasSize(64);
    }

    @Test
    void toolSetHash_changesWhenToolSetChanges() {
        var a = new McpTool("server", "http://a", spec("alpha", null), null);
        var b = new McpTool("server", "http://a", spec("beta", null), null);

        assertThat(new McpToolRegistry(List.of(a)).toolSetHash())
                .isNotEqualTo(new McpToolRegistry(List.of(a, b)).toolSetHash());
    }

    private static ToolSpecification spec(String name, String requiredPermission) {
        var builder = ToolSpecification.builder().name(name);
        if (requiredPermission != null) {
            builder.addMetadata(McpToolPermissionGate.REQUIRED_PERMISSION_META_KEY, requiredPermission);
        }
        return builder.build();
    }

    private static ApplicationPermissions permissions(Map<String, Set<String>> permissions) {
        return new ApplicationPermissions().permissions(permissions);
    }
}
