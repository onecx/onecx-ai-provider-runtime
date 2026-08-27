package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
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
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.DiscoveredToolAnnotationsDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.DiscoveredToolDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolDiscoveryRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolRuleSnapshotDTO;
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
    void createToolRegistry_filtersToolsByAllowRules_andDeniesUnlistedTools() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a"), toolSpec("tool-b"), toolSpec("tool-c")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of(
                rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.ALLOW),
                rule("tool-b", ToolRuleSnapshotDTO.AllowedEnum.DENY)));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("tool-a");
    }

    @Test
    void createToolRegistry_allowsAll_whenNoRulesAndLegacyAllowAll() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, true);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool("http://ok", null, "MCP")));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("tool-a");
    }

    @Test
    void createToolRegistry_deniesAll_whenNoRulesAndNoLegacyAllowAll() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool("http://ok", null, "MCP")));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).isEmpty();
    }

    @Test
    void createToolRegistry_ignoresRules_whenEnforcementDisabled() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(false, true);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of(rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.DENY)));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("tool-a");
    }

    @Test
    void createToolRegistry_returnsEmpty_whenOAuthProviderMissing() {
        var service = serviceWithConfig();
        var tool = tool("http://oauth", null, "MCP", "OAUTH");
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
        var tool = tool("http://example.org", null, "MCP", "OAUTH");
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
        var tool = tool("http://example.org", null, "MCP", "OAUTH");
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

    @Test
    void toAnnotations_returnsNullForNullAndEmptyMetadata() throws Exception {
        var service = serviceWithConfig();
        assertThat(invokeToAnnotations(service, null)).isNull();
        assertThat(invokeToAnnotations(service, Map.of())).isNull();
    }

    @Test
    void toAnnotations_returnsAnnotationsForTrueHints() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.READ_ONLY_HINT, true);
        metadata.put(McpToolMetadataKeys.DESTRUCTIVE_HINT, true);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNotNull();
        assertThat(annotations.getReadOnlyHint()).isTrue();
        assertThat(annotations.getDestructiveHint()).isTrue();
        assertThat(annotations.getIdempotentHint()).isNull();
        assertThat(annotations.getOpenWorldHint()).isNull();
    }

    @Test
    void toAnnotations_returnsNullWhenAllHintsAreFalseOrNull() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.READ_ONLY_HINT, false);
        metadata.put(McpToolMetadataKeys.DESTRUCTIVE_HINT, false);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNull();
    }

    @Test
    void toAnnotations_handlesAllFourHints() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.READ_ONLY_HINT, true);
        metadata.put(McpToolMetadataKeys.DESTRUCTIVE_HINT, false);
        metadata.put(McpToolMetadataKeys.IDEMPOTENT_HINT, true);
        metadata.put(McpToolMetadataKeys.OPEN_WORLD_HINT, true);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNotNull();
        assertThat(annotations.getReadOnlyHint()).isTrue();
        assertThat(annotations.getDestructiveHint()).isNull();
        assertThat(annotations.getIdempotentHint()).isTrue();
        assertThat(annotations.getOpenWorldHint()).isTrue();
    }

    @Test
    void toAnnotations_ignoresNonBooleanMetadataValues() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.READ_ONLY_HINT, "yes");
        metadata.put(McpToolMetadataKeys.DESTRUCTIVE_HINT, 1);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNull();
    }

    @Test
    void boolMeta_returnsTrueForTrueBoolean() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", Boolean.TRUE);
        assertThat(invokeBoolMeta(service, metadata, "key")).isTrue();
    }

    @Test
    void boolMeta_returnsFalseForFalseBoolean() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", Boolean.FALSE);
        assertThat(invokeBoolMeta(service, metadata, "key")).isFalse();
    }

    @Test
    void boolMeta_returnsFalseForNonBooleanValue() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key", "not-a-boolean");
        assertThat(invokeBoolMeta(service, metadata, "key")).isFalse();
    }

    @Test
    void boolMeta_returnsFalseForMissingKey() throws Exception {
        var service = serviceWithConfig();
        assertThat(invokeBoolMeta(service, new HashMap<>(), "missing")).isFalse();
    }

    @Test
    void discoverTools_returnsToolsFromMcpClient() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig();

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a"), toolSpec("tool-b")));
        service.registerClient("http://mcp", client);

        ToolDiscoveryRequestDTO request = new ToolDiscoveryRequestDTO();
        request.setUrl("http://mcp");

        var response = service.discoverTools(request);
        assertThat(response.getTools()).hasSize(2);
        assertThat(response.getTools()).extracting(
                DiscoveredToolDTO::getName)
                .containsExactly("tool-a", "tool-b");
    }

    @Test
    void discoverTools_throwsMcpDiscoveryExceptionOnFailure() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig();

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenThrow(new RuntimeException("connection refused"));
        service.registerClient("http://mcp", client);

        ToolDiscoveryRequestDTO request = new ToolDiscoveryRequestDTO();
        request.setUrl("http://mcp");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.discoverTools(request))
                .isInstanceOf(McpService.McpDiscoveryException.class)
                .hasMessageContaining("Failed to discover tools")
                .hasMessageContaining("http://mcp");
    }

    @Test
    void closeQuietly_swallowsExceptions() throws Exception {
        var service = serviceWithConfig();
        McpClient client = mock(McpClient.class);
        doThrow(new RuntimeException("close failed")).when(client).close();

        Method method = McpService.class.getDeclaredMethod("closeQuietly", McpClient.class);
        method.setAccessible(true);
        method.invoke(service, client);
        org.mockito.Mockito.verify(client).close();
    }

    @Test
    void createToolRegistry_filtersByRules_allowsAlwaysAskRule() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a"), toolSpec("tool-b")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of(
                rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.ALWAYS_ASK)));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("tool-a");
    }

    @Test
    void createToolRegistry_filtersByRules_deniesUnlistedTool() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a"), toolSpec("tool-unlisted")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of(rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.ALLOW)));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("tool-a");
    }

    @Test
    void createToolRegistry_closesClientWhenAllToolsFilteredOut() throws Exception {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of(rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.DENY)));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).isEmpty();
        org.mockito.Mockito.verify(client).close();
    }

    @Test
    void createToolRegistry_propagatesExecutionPolicyAndAllowedToMcpTool() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setExecutionPolicy(ToolSnapshotDTO.ExecutionPolicyEnum.ALWAYS_ASK);
        tool.setToolRules(List.of(rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.ALLOW)));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).hasSize(1);
        McpTool mcpTool = registry.tools().get(0);
        assertThat(mcpTool.executionPolicy()).isEqualTo("ALWAYS_ASK");
        assertThat(mcpTool.allowed()).isEqualTo("ALLOW");
    }

    @Test
    void createToolRegistry_propagatesNullExecutionPolicyAndNullAllowedWhenAbsent() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(false, true);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).hasSize(1);
        McpTool mcpTool = registry.tools().get(0);
        assertThat(mcpTool.executionPolicy()).isNull();
        assertThat(mcpTool.allowed()).isNull();
    }

    @Test
    void discoverTools_mapsAnnotationsFromToolSpecMetadata() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig();

        McpClient client = mock(McpClient.class);
        ToolSpecification specWithMeta = ToolSpecification.builder()
                .name("annotated-tool")
                .description("desc")
                .parameters(JsonObjectSchema.builder().build())
                .metadata(Map.of(
                        McpToolMetadataKeys.READ_ONLY_HINT, true,
                        McpToolMetadataKeys.OPEN_WORLD_HINT, true))
                .build();
        when(client.listTools()).thenReturn(List.of(specWithMeta));
        service.registerClient("http://mcp", client);

        ToolDiscoveryRequestDTO request = new ToolDiscoveryRequestDTO();
        request.setUrl("http://mcp");

        var response = service.discoverTools(request);
        assertThat(response.getTools()).hasSize(1);
        var dto = response.getTools().get(0);
        assertThat(dto.getAnnotations()).isNotNull();
        assertThat(dto.getAnnotations().getReadOnlyHint()).isTrue();
        assertThat(dto.getAnnotations().getOpenWorldHint()).isTrue();
    }

    @Test
    void createToolRegistry_ruleWithNullAllowed_propagatesNullAllowed() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(false, true);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        var ruleWithNullAllowed = new ToolRuleSnapshotDTO();
        ruleWithNullAllowed.setToolName("tool-a");
        ruleWithNullAllowed.setAllowed(null);
        tool.setToolRules(List.of(ruleWithNullAllowed));
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).hasSize(1);
        McpTool mcpTool = registry.tools().get(0);
        assertThat(mcpTool.allowed()).isNull();
    }

    @Test
    void rulesByName_withDuplicateToolNames_keepsFirstRule() throws Exception {
        var service = serviceWithConfig();

        var tool = tool("http://ok", null, "MCP");
        ToolRuleSnapshotDTO rule1 = rule("dup-tool", ToolRuleSnapshotDTO.AllowedEnum.ALLOW);
        ToolRuleSnapshotDTO rule2 = rule("dup-tool", ToolRuleSnapshotDTO.AllowedEnum.DENY);
        tool.setToolRules(List.of(rule1, rule2));

        Method method = McpService.class.getDeclaredMethod("rulesByName", ToolSnapshotDTO.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolRuleSnapshotDTO> result = (Map<String, ToolRuleSnapshotDTO>) method.invoke(service, tool);

        assertThat(result).hasSize(1);
        assertThat(result.get("dup-tool").getAllowed()).isEqualTo(ToolRuleSnapshotDTO.AllowedEnum.ALLOW);
    }

    @Test
    void rulesByName_withEmptyNonNullRulesList_returnsEmptyMap() throws Exception {
        var service = serviceWithConfig();

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of());

        Method method = McpService.class.getDeclaredMethod("rulesByName", ToolSnapshotDTO.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolRuleSnapshotDTO> result = (Map<String, ToolRuleSnapshotDTO>) method.invoke(service, tool);

        assertThat(result).isEmpty();
    }

    @Test
    void rulesByName_withNullRules_returnsEmptyMap() throws Exception {
        var service = serviceWithConfig();

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(null);

        Method method = McpService.class.getDeclaredMethod("rulesByName", ToolSnapshotDTO.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolRuleSnapshotDTO> result = (Map<String, ToolRuleSnapshotDTO>) method.invoke(service, tool);

        assertThat(result).isEmpty();
    }

    @Test
    void filterByRules_withEnforcementAndEmptyNonNullRulesList_deniesAll() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, false);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of());
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.tools()).isEmpty();
    }

    @Test
    void filterByRules_withEnforcementAndEmptyNonNullRulesListAndLegacyAllowAll_allowsAll() {
        var service = new TestableMcpService();
        service.dispatchConfig = dispatchConfig(true, true);

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(toolSpec("tool-a")));
        service.registerClient("http://ok", client);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of());
        var agent = new AgentSnapshotDTO();
        agent.setTools(List.of(tool));

        var registry = service.createToolRegistry(agent);

        assertThat(registry.getToolSpecifications()).extracting(ToolSpecification::name)
                .containsExactly("tool-a");
    }

    @Test
    void filterByRules_withEnforcementAndNullRules_deniesAll() throws Exception {
        var service = serviceWithConfig(true, false);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(null);

        Method method = McpService.class.getDeclaredMethod("filterByRules", ToolSnapshotDTO.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolSpecification> result = (List<ToolSpecification>) method.invoke(service, tool,
                List.of(toolSpec("tool-a")));

        assertThat(result).isEmpty();
    }

    @Test
    void filterByRules_withEnforcementAndEmptyRulesAndLegacyAllowAll_allowsAll() throws Exception {
        var service = serviceWithConfig(true, true);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of());

        Method method = McpService.class.getDeclaredMethod("filterByRules", ToolSnapshotDTO.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolSpecification> result = (List<ToolSpecification>) method.invoke(service, tool,
                List.of(toolSpec("tool-a")));

        assertThat(result).extracting(ToolSpecification::name).containsExactly("tool-a");
    }

    @Test
    void filterByRules_withEnforcementAndNonEmptyRules_filtersByAllowed() throws Exception {
        var service = serviceWithConfig(true, false);

        var tool = tool("http://ok", null, "MCP");
        tool.setToolRules(List.of(rule("tool-a", ToolRuleSnapshotDTO.AllowedEnum.ALLOW)));

        Method method = McpService.class.getDeclaredMethod("filterByRules", ToolSnapshotDTO.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ToolSpecification> result = (List<ToolSpecification>) method.invoke(service, tool,
                List.of(toolSpec("tool-a"), toolSpec("tool-b")));

        assertThat(result).extracting(ToolSpecification::name).containsExactly("tool-a");
    }

    @Test
    void toAnnotations_withOnlyDestructiveHint_returnsAnnotationsWithDestructiveOnly() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.DESTRUCTIVE_HINT, true);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNotNull();
        assertThat(annotations.getReadOnlyHint()).isNull();
        assertThat(annotations.getDestructiveHint()).isTrue();
        assertThat(annotations.getIdempotentHint()).isNull();
        assertThat(annotations.getOpenWorldHint()).isNull();
    }

    @Test
    void toAnnotations_withOnlyIdempotentHint_returnsAnnotationsWithIdempotentOnly() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.IDEMPOTENT_HINT, true);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNotNull();
        assertThat(annotations.getReadOnlyHint()).isNull();
        assertThat(annotations.getDestructiveHint()).isNull();
        assertThat(annotations.getIdempotentHint()).isTrue();
        assertThat(annotations.getOpenWorldHint()).isNull();
    }

    @Test
    void toAnnotations_withOnlyOpenWorldHint_returnsAnnotationsWithOpenWorldOnly() throws Exception {
        var service = serviceWithConfig();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(McpToolMetadataKeys.OPEN_WORLD_HINT, true);

        var annotations = invokeToAnnotations(service, metadata);
        assertThat(annotations).isNotNull();
        assertThat(annotations.getReadOnlyHint()).isNull();
        assertThat(annotations.getDestructiveHint()).isNull();
        assertThat(annotations.getIdempotentHint()).isNull();
        assertThat(annotations.getOpenWorldHint()).isTrue();
    }

    @Test
    void createMcpClient_oauth2Lambda_fallsBackToInitialAuthorizationHeadersWhenRefreshIsEmpty() {
        var service = serviceWithConfig();
        var propagatedHeaders = Map.of("apm-principal-token", "token");
        var tool = tool("http://example.org", null, "MCP", "OAUTH");
        service.mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);
        service.mcpAuthHeaders = mock(McpAuthHeaders.class);
        when(service.mcpPropagatedHeaders.currentHeaders()).thenReturn(propagatedHeaders);
        when(service.mcpAuthHeaders.authorizationHeaders(tool, propagatedHeaders))
                .thenReturn(Map.of("Authorization", "Bearer initial"))
                .thenReturn(Map.of());

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
                    .containsEntry("Authorization", "Bearer initial")
                    .containsEntry("apm-principal-token", "token");
        }
    }

    private DiscoveredToolAnnotationsDTO invokeToAnnotations(McpService service, Map<String, Object> metadata)
            throws Exception {
        Method method = McpService.class.getDeclaredMethod("toAnnotations", Map.class);
        method.setAccessible(true);
        return (DiscoveredToolAnnotationsDTO) method.invoke(service, metadata);
    }

    private boolean invokeBoolMeta(McpService service, Map<String, Object> metadata, String key) throws Exception {
        Method method = McpService.class.getDeclaredMethod("boolMeta", Map.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, metadata, key);
    }

    private static McpService serviceWithConfig() {
        return serviceWithConfig(true, true);
    }

    private static McpService serviceWithConfig(boolean enforcementEnabled, boolean legacyAllowAll) {
        var service = new McpService();
        service.dispatchConfig = dispatchConfig(enforcementEnabled, legacyAllowAll);
        service.mcpAuthHeaders = new McpAuthHeaders();
        service.mcpPropagatedHeaders = new McpPropagatedHeaders();
        return service;
    }

    private static DispatchConfig dispatchConfig() {
        return dispatchConfig(true, true);
    }

    private static DispatchConfig dispatchConfig(boolean enforcementEnabled, boolean legacyAllowAll) {
        DispatchConfig dispatchConfig = mock(DispatchConfig.class);
        DispatchConfig.ToolConfig toolConfig = mock(DispatchConfig.ToolConfig.class);

        when(toolConfig.maxTimeout()).thenReturn(1L);
        when(toolConfig.logRequests()).thenReturn(false);
        when(toolConfig.logResponse()).thenReturn(false);
        when(toolConfig.maxToolExecutionRetries()).thenReturn(2L);
        when(toolConfig.enforcementEnabled()).thenReturn(enforcementEnabled);
        when(toolConfig.legacyAllowAll()).thenReturn(legacyAllowAll);
        when(dispatchConfig.toolConfig()).thenReturn(toolConfig);

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

    private static ToolRuleSnapshotDTO rule(String name, ToolRuleSnapshotDTO.AllowedEnum allowed) {
        var rule = new ToolRuleSnapshotDTO();
        rule.setToolName(name);
        rule.setAllowed(allowed);
        return rule;
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
