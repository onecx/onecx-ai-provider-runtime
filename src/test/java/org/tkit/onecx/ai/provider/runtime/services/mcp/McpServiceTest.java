package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import io.quarkiverse.langchain4j.mcp.auth.McpClientAuthProvider;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class McpServiceTest {

    @Test
    void createToolRegistry_returnsEmpty_whenAgentNull() {
        var service = serviceWithConfig();

        var registry = service.createToolRegistry(null);

        assertThat(registry.tools()).isEmpty();
    }

    @Test
    void createToolRegistry_returnsEmpty_whenToolListNullOrEmpty() {
        var service = serviceWithConfig();

        var agentWithNullTools = new AgentSnapshotDTO();
        agentWithNullTools.setTools(null);
        var nullRegistry = service.createToolRegistry(agentWithNullTools);

        var agentWithEmptyTools = new AgentSnapshotDTO();
        agentWithEmptyTools.setTools(List.of());
        var emptyRegistry = service.createToolRegistry(agentWithEmptyTools);

        assertThat(nullRegistry.tools()).isEmpty();
        assertThat(emptyRegistry.tools()).isEmpty();
    }

    @Test
    void createToolRegistry_mergesDiscoveredTools_andSkipsFailingAndNonMcpServers() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig();

        McpClient okClient = mock(McpClient.class);
        when(okClient.listTools()).thenReturn(List.of(toolSpec("tool-a"), toolSpec("tool-b")));
        service.registerClient("http://ok", okClient);

        McpClient failingClient = mock(McpClient.class);
        doThrow(new RuntimeException("down")).when(failingClient).checkHealth();
        service.registerClient("http://down", failingClient);

        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool("http://ok", null, "MCP"), tool("http://down", null, "MCP"),
                tool("http://ignored", null, "REST")));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).hasSize(2);
        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("tool-a", "tool-b");
    }

    @Test
    void createToolRegistry_returnsEmpty_whenClientCreationThrows() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig();
        service.registerClientCreationError("http://boom", new RuntimeException("cannot create client"));

        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool("http://boom", null, "MCP")));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).isEmpty();
    }

    @Test
    void createToolRegistry_returnsEmpty_whenOAuthProviderMissing() {
        var service = serviceWithConfig();
        var tool = tool("http://oauth", null, "MCP", "OAUTH2");
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        StreamableHttpMcpTransport.Builder transportBuilder = mock(StreamableHttpMcpTransport.Builder.class);
        when(transportBuilder.url("http://oauth")).thenReturn(transportBuilder);
        when(transportBuilder.timeout(Duration.ofSeconds(1))).thenReturn(transportBuilder);
        when(transportBuilder.logRequests(false)).thenReturn(transportBuilder);
        when(transportBuilder.logResponses(false)).thenReturn(transportBuilder);

        try (MockedStatic<StreamableHttpMcpTransport> transportStatic = mockStatic(StreamableHttpMcpTransport.class);
                MockedStatic<McpClientAuthProvider> authStatic = mockStatic(McpClientAuthProvider.class)) {
            transportStatic.when(StreamableHttpMcpTransport::builder).thenReturn(transportBuilder);
            authStatic.when(() -> McpClientAuthProvider.resolve(null)).thenReturn(java.util.Optional.empty());

            var registry = service.createToolRegistry(agent);

            assertThat(registry.tools()).isEmpty();
        }
    }

    @Test
    void receiveToolSpecifications_returnsClientTools() {
        var service = serviceWithConfig();
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-x")));

        var result = service.receiveToolSpecifications(client);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("tool-x");
    }

    @Test
    void receiveToolSpecificationsFallback_returnsEmptyList() {
        var service = serviceWithConfig();

        var result = service.receiveToolSpecificationsFallback(mock(McpClient.class));

        assertThat(result).isEmpty();
    }

    @Test
    void createMcpClient_buildsClientWithConfiguredTransportAndAuthorizationHeader() {
        var service = serviceWithConfig();
        var tool = tool("http://example.org", "Bearer token", "MCP");

        StreamableHttpMcpTransport.Builder transportBuilder = mock(StreamableHttpMcpTransport.Builder.class);
        StreamableHttpMcpTransport transport = mock(StreamableHttpMcpTransport.class);
        DefaultMcpClient.Builder clientBuilder = mock(DefaultMcpClient.Builder.class);
        DefaultMcpClient client = mock(DefaultMcpClient.class);

        when(transportBuilder.url("http://example.org")).thenReturn(transportBuilder);
        when(transportBuilder.timeout(Duration.ofSeconds(1))).thenReturn(transportBuilder);
        when(transportBuilder.logRequests(false)).thenReturn(transportBuilder);
        when(transportBuilder.logResponses(false)).thenReturn(transportBuilder);
        when(transportBuilder.customHeaders(Map.of("Authorization", "Bearer token"))).thenReturn(transportBuilder);
        when(transportBuilder.build()).thenReturn(transport);
        when(clientBuilder.transport(transport)).thenReturn(clientBuilder);
        when(clientBuilder.build()).thenReturn(client);

        try (MockedStatic<StreamableHttpMcpTransport> transportStatic = mockStatic(StreamableHttpMcpTransport.class);
                MockedStatic<DefaultMcpClient> clientStatic = mockStatic(DefaultMcpClient.class)) {
            transportStatic.when(StreamableHttpMcpTransport::builder).thenReturn(transportBuilder);
            clientStatic.when(DefaultMcpClient::builder).thenReturn(clientBuilder);

            assertThat(service.createMcpClient(tool)).isSameAs(client);
        }
    }

    @Test
    void createMcpClient_buildsClientWithDynamicOAuthAuthorizationHeader() {
        var service = serviceWithConfig();
        var tool = tool("http://example.org", null, "MCP", "OAUTH2");
        service.mcpAuthHeaders = mock(McpAuthHeaders.class);
        when(service.mcpAuthHeaders.authorizationHeaders(tool, Map.of()))
                .thenReturn(Map.of("Authorization", "Bearer first"))
                .thenReturn(Map.of("Authorization", "Bearer second"));

        StreamableHttpMcpTransport.Builder transportBuilder = mock(StreamableHttpMcpTransport.Builder.class);
        StreamableHttpMcpTransport transport = mock(StreamableHttpMcpTransport.class);
        DefaultMcpClient.Builder clientBuilder = mock(DefaultMcpClient.Builder.class);
        DefaultMcpClient client = mock(DefaultMcpClient.class);
        ArgumentCaptor<McpHeadersSupplier> headersCaptor = ArgumentCaptor.forClass(McpHeadersSupplier.class);

        when(transportBuilder.url("http://example.org")).thenReturn(transportBuilder);
        when(transportBuilder.timeout(Duration.ofSeconds(1))).thenReturn(transportBuilder);
        when(transportBuilder.logRequests(false)).thenReturn(transportBuilder);
        when(transportBuilder.logResponses(false)).thenReturn(transportBuilder);
        when(transportBuilder.customHeaders(headersCaptor.capture())).thenReturn(transportBuilder);
        when(transportBuilder.build()).thenReturn(transport);
        when(clientBuilder.transport(transport)).thenReturn(clientBuilder);
        when(clientBuilder.build()).thenReturn(client);

        try (MockedStatic<StreamableHttpMcpTransport> transportStatic = mockStatic(StreamableHttpMcpTransport.class);
                MockedStatic<DefaultMcpClient> clientStatic = mockStatic(DefaultMcpClient.class)) {
            transportStatic.when(StreamableHttpMcpTransport::builder).thenReturn(transportBuilder);
            clientStatic.when(DefaultMcpClient::builder).thenReturn(clientBuilder);

            assertThat(service.createMcpClient(tool)).isSameAs(client);
            assertThat(headersCaptor.getValue().apply(null)).containsEntry("Authorization", "Bearer second");
        }
    }

    @Test
    void createMcpClient_propagatesApmPrincipalTokenWithApiKeyAuthorizationHeader() {
        var service = serviceWithConfig();
        service.mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);
        when(service.mcpPropagatedHeaders.currentHeaders())
                .thenReturn(Map.of("apm-principal-token", "principal-token"));
        var tool = tool("http://example.org", "Bearer api-key", "MCP");

        StreamableHttpMcpTransport.Builder transportBuilder = mock(StreamableHttpMcpTransport.Builder.class);
        StreamableHttpMcpTransport transport = mock(StreamableHttpMcpTransport.class);
        DefaultMcpClient.Builder clientBuilder = mock(DefaultMcpClient.Builder.class);
        DefaultMcpClient client = mock(DefaultMcpClient.class);

        Map<String, String> expectedHeaders = Map.of(
                "Authorization", "Bearer api-key",
                "apm-principal-token", "principal-token");
        when(transportBuilder.url("http://example.org")).thenReturn(transportBuilder);
        when(transportBuilder.timeout(Duration.ofSeconds(1))).thenReturn(transportBuilder);
        when(transportBuilder.logRequests(false)).thenReturn(transportBuilder);
        when(transportBuilder.logResponses(false)).thenReturn(transportBuilder);
        when(transportBuilder.customHeaders(expectedHeaders)).thenReturn(transportBuilder);
        when(transportBuilder.build()).thenReturn(transport);
        when(clientBuilder.transport(transport)).thenReturn(clientBuilder);
        when(clientBuilder.build()).thenReturn(client);

        try (MockedStatic<StreamableHttpMcpTransport> transportStatic = mockStatic(StreamableHttpMcpTransport.class);
                MockedStatic<DefaultMcpClient> clientStatic = mockStatic(DefaultMcpClient.class)) {
            transportStatic.when(StreamableHttpMcpTransport::builder).thenReturn(transportBuilder);
            clientStatic.when(DefaultMcpClient::builder).thenReturn(clientBuilder);

            assertThat(service.createMcpClient(tool)).isSameAs(client);
        }
    }

    @Test
    void createMcpClient_propagatesApmPrincipalTokenWithoutAuthorization() {
        var service = serviceWithConfig();
        service.mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);
        when(service.mcpPropagatedHeaders.currentHeaders())
                .thenReturn(Map.of("apm-principal-token", "principal-token"));
        var tool = tool("http://example.org", null, "MCP");

        StreamableHttpMcpTransport.Builder transportBuilder = mock(StreamableHttpMcpTransport.Builder.class);
        StreamableHttpMcpTransport transport = mock(StreamableHttpMcpTransport.class);
        DefaultMcpClient.Builder clientBuilder = mock(DefaultMcpClient.Builder.class);
        DefaultMcpClient client = mock(DefaultMcpClient.class);

        Map<String, String> expectedHeaders = Map.of("apm-principal-token", "principal-token");
        when(transportBuilder.url("http://example.org")).thenReturn(transportBuilder);
        when(transportBuilder.timeout(Duration.ofSeconds(1))).thenReturn(transportBuilder);
        when(transportBuilder.logRequests(false)).thenReturn(transportBuilder);
        when(transportBuilder.logResponses(false)).thenReturn(transportBuilder);
        when(transportBuilder.customHeaders(expectedHeaders)).thenReturn(transportBuilder);
        when(transportBuilder.build()).thenReturn(transport);
        when(clientBuilder.transport(transport)).thenReturn(clientBuilder);
        when(clientBuilder.build()).thenReturn(client);

        try (MockedStatic<StreamableHttpMcpTransport> transportStatic = mockStatic(StreamableHttpMcpTransport.class);
                MockedStatic<DefaultMcpClient> clientStatic = mockStatic(DefaultMcpClient.class)) {
            transportStatic.when(StreamableHttpMcpTransport::builder).thenReturn(transportBuilder);
            clientStatic.when(DefaultMcpClient::builder).thenReturn(clientBuilder);

            assertThat(service.createMcpClient(tool)).isSameAs(client);
        }
    }

    @Test
    void createMcpClient_propagatesApmPrincipalTokenWithOAuthAuthorizationHeader() {
        var service = serviceWithConfig();
        var propagatedHeaders = Map.of("apm-principal-token", "principal-token");
        var tool = tool("http://example.org", null, "MCP", "OAUTH2");
        service.mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);
        service.mcpAuthHeaders = mock(McpAuthHeaders.class);
        when(service.mcpPropagatedHeaders.currentHeaders()).thenReturn(propagatedHeaders);
        when(service.mcpAuthHeaders.authorizationHeaders(tool, propagatedHeaders))
                .thenReturn(Map.of("Authorization", "Bearer first"))
                .thenReturn(Map.of("Authorization", "Bearer second"));

        StreamableHttpMcpTransport.Builder transportBuilder = mock(StreamableHttpMcpTransport.Builder.class);
        StreamableHttpMcpTransport transport = mock(StreamableHttpMcpTransport.class);
        DefaultMcpClient.Builder clientBuilder = mock(DefaultMcpClient.Builder.class);
        DefaultMcpClient client = mock(DefaultMcpClient.class);
        ArgumentCaptor<McpHeadersSupplier> headersCaptor = ArgumentCaptor.forClass(McpHeadersSupplier.class);

        when(transportBuilder.url("http://example.org")).thenReturn(transportBuilder);
        when(transportBuilder.timeout(Duration.ofSeconds(1))).thenReturn(transportBuilder);
        when(transportBuilder.logRequests(false)).thenReturn(transportBuilder);
        when(transportBuilder.logResponses(false)).thenReturn(transportBuilder);
        when(transportBuilder.customHeaders(headersCaptor.capture())).thenReturn(transportBuilder);
        when(transportBuilder.build()).thenReturn(transport);
        when(clientBuilder.transport(transport)).thenReturn(clientBuilder);
        when(clientBuilder.build()).thenReturn(client);

        try (MockedStatic<StreamableHttpMcpTransport> transportStatic = mockStatic(StreamableHttpMcpTransport.class);
                MockedStatic<DefaultMcpClient> clientStatic = mockStatic(DefaultMcpClient.class)) {
            transportStatic.when(StreamableHttpMcpTransport::builder).thenReturn(transportBuilder);
            clientStatic.when(DefaultMcpClient::builder).thenReturn(clientBuilder);

            assertThat(service.createMcpClient(tool)).isSameAs(client);
            assertThat(headersCaptor.getValue().apply(null))
                    .containsEntry("Authorization", "Bearer second")
                    .containsEntry("apm-principal-token", "principal-token");
        }
    }

    private static McpService serviceWithConfig() {
        var service = new McpService();
        service.dispatchConfig = dispatchConfig();
        service.mcpAuthHeaders = new McpAuthHeaders();
        service.mcpPropagatedHeaders = new McpPropagatedHeaders();
        return service;
    }

    private static DispatchConfig dispatchConfig() {
        DispatchConfig dispatchConfig = mock(DispatchConfig.class);
        DispatchConfig.MCPConfig mcpConfig = mock(DispatchConfig.MCPConfig.class);

        when(mcpConfig.maxTimeout()).thenReturn(1L);
        when(mcpConfig.logRequests()).thenReturn(false);
        when(mcpConfig.logResponse()).thenReturn(false);
        when(mcpConfig.maxToolExecutionRetries()).thenReturn(2L);
        when(dispatchConfig.mcpConfig()).thenReturn(mcpConfig);

        return dispatchConfig;
    }

    private static ToolSnapshotDTO tool(String url, String apiKey, String type) {
        return tool(url, apiKey, type, null);
    }

    private static ToolSnapshotDTO tool(String url, String apiKey, String type, String authMode) {
        ToolSnapshotDTO tool = new ToolSnapshotDTO();
        tool.setName(url);
        tool.setType(type);
        tool.setUrl(url);
        tool.setApiKey(apiKey);
        tool.setAuthMode(authMode);
        return tool;
    }

    private static ToolSpecification toolSpec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description("desc")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }

    static class TestableMcpService extends McpService {

        private final Map<String, McpClient> clients = new HashMap<>();
        private final Map<String, RuntimeException> creationErrors = new HashMap<>();

        void registerClient(String url, McpClient client) {
            clients.put(url, client);
        }

        void registerClientCreationError(String url, RuntimeException ex) {
            creationErrors.put(url, ex);
        }

        @Override
        protected McpClient createMcpClient(ToolSnapshotDTO tool) {
            RuntimeException ex = creationErrors.get(tool.getUrl());
            if (ex != null) {
                throw ex;
            }
            return clients.get(tool.getUrl());
        }
    }
}
