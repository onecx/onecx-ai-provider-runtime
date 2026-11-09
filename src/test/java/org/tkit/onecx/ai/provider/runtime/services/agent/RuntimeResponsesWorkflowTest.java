package org.tkit.onecx.ai.provider.runtime.services.agent;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpPropagatedHeaders;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpTool;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;
import org.tkit.onecx.ai.provider.runtime.services.provider.ChatModelFactory;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentGroupSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ModelSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ScaffoldSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.SkillSnapshotDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GenerateKeycloakClient(clientName = "responsesTestClient", scopes = { "ocx-agent:write" })
class RuntimeResponsesWorkflowTest extends AbstractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Inject
    ChatModelFactory factory;
    @Inject
    DispatchConfig config;
    private RuntimeChatService service;
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private String toolName;
    private String toolArguments;
    private int status = 200;
    private String rejectedModel;
    private boolean textToolCalls;

    @BeforeEach
    void setUp() throws IOException {
        service = new RuntimeChatService();
        service.objectMapper = JSON;
        service.chatModelFactory = factory;
        service.dispatchConfig = config;
        service.scaffoldPromptComposer = new ScaffoldPromptComposer();
        service.runtimeSkillService = new RuntimeSkillService();
        service.mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);
        when(service.mcpPropagatedHeaders.currentHeaders()).thenReturn(Map.of());
        service.mcpService = mock(McpService.class);
        when(service.mcpService.createToolRegistry(any(), any())).thenReturn(McpToolRegistry.empty());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            requests.add(request);
            paths.add(exchange.getRequestURI().getPath());
            int responseStatus = request.path("model").asText().equals(rejectedModel) ? 404 : status;
            boolean root = "root".equals(request.path("model").asText());
            String output = root && requests.size() == 1 && toolName != null
                    ? """
                            {"type":"reasoning","id":"reason-1","summary":[],"encrypted_content":"opaque-state"},
                            {"type":"function_call","id":"fc-1","call_id":"call-1","name":"%s","arguments":%s}
                            """.formatted(toolName, JSON.writeValueAsString(toolArguments))
                    : """
                            {"id":"msg-1","type":"message","role":"assistant","status":"completed",
                             "content":[{"type":"output_text","text":"%s","annotations":[]}]}
                            """.formatted(root ? "final answer" : "delegate answer");
            if (textToolCalls && requests.size() == 1) {
                String text = "{\"name\":\"" + toolName + "\",\"arguments\":" + toolArguments + "}";
                output = """
                        {"type":"reasoning","id":"reason-1","summary":[],"encrypted_content":"opaque-state"},
                        {"id":"msg-1","type":"message","role":"assistant","status":"completed",
                         "content":[{"type":"output_text","text":%s,"annotations":[]}]}
                        """.formatted(JSON.writeValueAsString(text));
            }
            byte[] response = (responseStatus == 200 ? """
                    {"id":"resp-%d","object":"response","model":"test","status":"completed","output":[%s],
                     "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                    """.formatted(requests.size(), output)
                    : "{\"error\":{\"message\":\"secret sk-private\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void mcpToolExecutesAndReturnsOutputWithEncryptedReasoningContinuation() {
        toolName = "lookup";
        toolArguments = "{\"query\":\"hello\"}";
        var specification = ToolSpecification.builder().name(toolName)
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build()).build();
        McpClient mcp = mock(McpClient.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(result.resultText()).thenReturn("MCP result for hello");
        when(mcp.executeTool(any())).thenAnswer(invocation -> {
            var call = (dev.langchain4j.agent.tool.ToolExecutionRequest) invocation.getArgument(0);
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.arguments()).isEqualTo(toolArguments);
            return result;
        });
        when(service.mcpService.createToolRegistry(any(), any()))
                .thenReturn(new McpToolRegistry(List.of(new McpTool("test", "local", specification, mcp))));
        assertThat(service.chat(request(agent("root"))).getMessage()).isEqualTo("final answer");
        assertContinuation("MCP result for hello", 2);
        assertThat(requests.getFirst().path("tools").toString()).contains("\"name\":\"lookup\"");
    }

    @Test
    void delegateToolExecutesRealResponsesAgentAndReturnsItsOutput() {
        toolName = "delegate_peer";
        toolArguments = "{\"message\":\"focused task\"}";
        AgentSnapshotDTO root = agent("root");
        root.setA2aEnabled(true);
        var group = new AgentGroupSnapshotDTO();
        group.setName("team");
        group.setAgents(List.of(agent("peer")));
        root.setGroups(List.of(group));
        assertThat(service.chat(request(root)).getMessage()).isEqualTo("final answer");
        assertContinuation("delegate answer", 3);
        assertThat(requests.get(1).path("model").asText()).isEqualTo("peer");
        assertThat(requests.get(1).path("input").toString()).contains("focused task");
    }

    @Test
    void skillActivationExecutesAndReturnsInstructions() {
        toolName = "activate_skill";
        toolArguments = "{\"skill_name\":\"Audit\"}";
        var root = agent("root");
        var scaffold = new ScaffoldSnapshotDTO();
        var skill = new SkillSnapshotDTO();
        skill.setName("Audit");
        skill.setDescription("Audit checks");
        skill.setInstruction("Use compliance rules.");
        scaffold.setSkills(List.of(skill));
        root.setScaffold(scaffold);
        assertThat(service.chat(request(root)).getMessage()).isEqualTo("final answer");
        assertContinuation("Use compliance rules.", 2);
        assertThat(requests.getFirst().path("tools").toString()).contains("activate_skill");
    }

    @Test
    void textToolNormalizationPreservesEncryptedResponsesReasoning() {
        textToolCalls = true;
        toolName = "lookup";
        toolArguments = "{\"query\":\"hello\"}";
        var root = agent("root");
        var specification = ToolSpecification.builder().name(toolName)
                .parameters(JsonObjectSchema.builder().addStringProperty("query").required("query").build()).build();
        McpClient mcp = mock(McpClient.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);
        when(result.resultText()).thenReturn("MCP result for hello");
        when(mcp.executeTool(any())).thenReturn(result);
        when(service.mcpService.createToolRegistry(any(), any()))
                .thenReturn(new McpToolRegistry(List.of(new McpTool("test", "local", specification, mcp))));
        assertThat(service.chat(request(root)).getMessage()).isEqualTo("final answer");
        assertThat(paths).containsExactly("/v1/responses", "/v1/responses");
        assertThat(requests.getLast().path("input").toString()).contains(
                "\"encrypted_content\":\"opaque-state\"", "\"call_id\":\"text-tool-call-1\"",
                "\"type\":\"function_call_output\"", "MCP result for hello");
    }

    @Test
    void endpointRejectionSurvivesAgentAndAsyncWrapping() {
        status = 404;
        assertThatThrownBy(() -> service.chat(request(agent("root"))))
                .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_ENDPOINT_REJECTED");
                    assertThat(ex.getStatusCode()).isEqualTo(502);
                    assertThat(ex.getDetail()).doesNotContain("secret", "sk-private");
                });
        assertThat(paths).containsExactly("/v1/responses");
    }

    @Test
    void structuredExceptionIsPreservedEvenWhenItHasAnUnderlyingCause() {
        var structured = new RuntimeChatException("RUNTIME_MODEL_ENDPOINT_REJECTED", "ProviderEndpointRejection",
                "Safe detail", Response.Status.BAD_GATEWAY, new IllegalStateException("private provider detail"));
        service.chatModelFactory = mock(ChatModelFactory.class);
        when(service.chatModelFactory.createChatModel(any())).thenThrow(new CompletionException(structured));
        assertThatThrownBy(() -> service.chat(request(agent("root")))).isSameAs(structured);
    }

    @Test
    void delegateEndpointRejectionIsNotConvertedIntoSuccessfulToolOutput() {
        toolName = "delegate_peer";
        toolArguments = "{\"message\":\"focused task\"}";
        rejectedModel = "peer";
        var root = agent("root");
        root.setA2aEnabled(true);
        var group = new AgentGroupSnapshotDTO();
        group.setName("team");
        group.setAgents(List.of(agent("peer")));
        root.setGroups(List.of(group));
        assertThatThrownBy(() -> service.chat(request(root)))
                .isInstanceOfSatisfying(RuntimeChatException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_ENDPOINT_REJECTED"));
        assertThat(paths).containsExactly("/v1/responses", "/v1/responses");
    }

    @Test
    void supervisorEndpointRejectionIsNotSwallowedOrRetriedAsRootAgent() {
        status = 404;
        var root = agent("root");
        root.setA2aEnabled(true);
        var group = new AgentGroupSnapshotDTO();
        group.setName("team");
        group.setOrchestrationMode("SUPERVISOR_ROUTED");
        group.setAgents(List.of(agent("peer")));
        root.setGroups(List.of(group));
        assertThatThrownBy(() -> service.chat(request(root)))
                .isInstanceOfSatisfying(RuntimeChatException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_ENDPOINT_REJECTED"));
        assertThat(paths).containsExactly("/v1/responses");
    }

    @Test
    void runtimeHttpReturnsSafeStructuredEndpointRejection() {
        status = 400;
        String body = given()
                .auth().oauth2(getKeycloakClientToken("responsesTestClient"))
                .contentType("application/json")
                .body(request(agent("root")))
                .post("/internal/runtime/chat")
                .then().statusCode(502)
                .body("errorCode", equalTo("RUNTIME_MODEL_ENDPOINT_REJECTED"))
                .extract().asString();
        assertThat(body).doesNotContain("secret", "sk-private", "sk-test");
        assertThat(paths).containsExactly("/v1/responses");
    }

    @Test
    void runtimeHttpReturnsExplicitOversizedConfigurationErrorWithoutProviderRequest() {
        var root = agent("root");
        root.getModel().setModelConfig("x".repeat(256));
        given()
                .auth().oauth2(getKeycloakClientToken("responsesTestClient"))
                .contentType("application/json")
                .body(request(root))
                .post("/internal/runtime/chat")
                .then().statusCode(400)
                .body("errorCode", equalTo("RUNTIME_MODEL_CONFIG_INVALID"));
        assertThat(paths).isEmpty();
    }

    private void assertContinuation(String output, int count) {
        assertThat(paths).hasSize(count).containsOnly("/v1/responses");
        JsonNode input = requests.getLast().path("input");
        assertThat(input.toString()).contains("\"type\":\"reasoning\"", "\"encrypted_content\":\"opaque-state\"",
                "\"type\":\"function_call\"", "\"call_id\":\"call-1\"", "\"type\":\"function_call_output\"", output);
        for (JsonNode request : requests) {
            assertThat(request.path("reasoning").path("effort").asText()).isEqualTo("medium");
        }
    }

    private AgentSnapshotDTO agent(String name) {
        var provider = new ProviderSnapshotDTO();
        provider.setType("OPENAI");
        provider.setApiKey("sk-test");
        provider.setLlmUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        var model = new ModelSnapshotDTO();
        model.setProvider(provider);
        model.setModelIdentifier(name);
        model.setModelConfig("{\"api\":\"responses\",\"reasoningEffort\":\"medium\"}");
        var agent = new AgentSnapshotDTO();
        agent.setName(name);
        agent.setModel(model);
        return agent;
    }

    private RuntimeChatRequestDTO request(AgentSnapshotDTO root) {
        var message = new ChatMessageDTO();
        message.setType("USER");
        message.setMessage("hello");
        var chat = new ChatRequestDTO();
        chat.setChatMessage(message);
        var request = new RuntimeChatRequestDTO();
        request.setRootAgent(root);
        request.setChatRequest(chat);
        return request;
    }
}
