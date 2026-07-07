package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import io.quarkiverse.langchain4j.mcp.auth.McpClientAuthProvider;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class McpAuthHeadersTest {

    @Test
    void authorizationHeaders_returnsBearerHeaderFromResolvedProvider() {
        var headers = new McpAuthHeaders();
        var tool = tool("tool-name", "http://mcp");
        McpClientAuthProvider provider = mock(McpClientAuthProvider.class);
        when(provider.getAuthorization(any())).thenAnswer(invocation -> {
            McpClientAuthProvider.Input input = invocation.getArgument(0);
            assertThat(input.method()).isEqualTo("POST");
            assertThat(input.uri().toString()).isEqualTo("http://mcp");
            assertThat(input.headers()).containsKey("X-Existing");
            return "Bearer token";
        });

        try (MockedStatic<McpClientAuthProvider> authStatic = mockStatic(McpClientAuthProvider.class)) {
            authStatic.when(() -> McpClientAuthProvider.resolve(null))
                    .thenReturn(java.util.Optional.of(provider));

            assertThat(headers.authorizationHeaders(tool, Map.of("X-Existing", "value")))
                    .containsEntry("Authorization", "Bearer token");
        }
    }

    @Test
    void authorizationHeaders_returnsEmpty_whenProviderReturnsBlankToken() {
        var headers = new McpAuthHeaders();
        var tool = tool("tool-name", "http://mcp");
        McpClientAuthProvider provider = mock(McpClientAuthProvider.class);
        when(provider.getAuthorization(any())).thenReturn(" ");

        try (MockedStatic<McpClientAuthProvider> authStatic = mockStatic(McpClientAuthProvider.class)) {
            authStatic.when(() -> McpClientAuthProvider.resolve(null))
                    .thenReturn(java.util.Optional.of(provider));

            assertThat(headers.authorizationHeaders(tool)).isEmpty();
        }
    }

    @Test
    void authorizationHeaders_returnsEmpty_whenNoProviderAvailable() {
        var headers = new McpAuthHeaders();
        var tool = tool("tool-name", "http://mcp");

        try (MockedStatic<McpClientAuthProvider> authStatic = mockStatic(McpClientAuthProvider.class)) {
            authStatic.when(() -> McpClientAuthProvider.resolve(null)).thenReturn(java.util.Optional.empty());

            assertThat(headers.authorizationHeaders(tool)).isEmpty();
        }
    }

    private static ToolSnapshotDTO tool(String name, String url) {
        ToolSnapshotDTO tool = new ToolSnapshotDTO();
        tool.setName(name);
        tool.setUrl(url);
        return tool;
    }
}
