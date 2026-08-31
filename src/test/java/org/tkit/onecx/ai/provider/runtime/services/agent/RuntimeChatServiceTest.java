package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.external.ExternalAgentDiscoveryService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpPropagatedHeaders;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpTool;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;
import org.tkit.onecx.ai.provider.runtime.services.provider.ChatModelFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutionResult;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentGroupSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ConversationDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeChatServiceTest {

    RuntimeChatService service;
    ChatModelFactory chatModelFactory;
    McpService mcpService;
    McpPropagatedHeaders mcpPropagatedHeaders;

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        mcpService = mock(McpService.class);
        mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);

        service = new RuntimeChatService();
        service.chatModelFactory = chatModelFactory;
        service.scaffoldPromptComposer = new ScaffoldPromptComposer();
        service.runtimeSkillService = new RuntimeSkillService();
        service.mcpService = mcpService;
        service.mcpPropagatedHeaders = mcpPropagatedHeaders;
        service.externalAgentDiscoveryService = mock(ExternalAgentDiscoveryService.class);
        service.dispatchConfig = dispatchConfig();
        service.objectMapper = new ObjectMapper();
        service.runtimeTimeout = 120L;

        when(mcpService.createToolRegistry(any(), any())).thenReturn(McpToolRegistry.empty());
    }

    @Test
    void chat_withoutRootAgent_throwsBadRequest() {
        assertThatThrownBy(() -> service.chat(new RuntimeChatRequestDTO()))
                .isInstanceOf(RuntimeChatException.class)
                .satisfies(ex -> {
                    RuntimeChatException error = (RuntimeChatException) ex;
                    assertThat(error.getErrorCode()).isEqualTo("RUNTIME_CHAT_REQUEST_INVALID");
                    assertThat(error.getErrorType()).isEqualTo("IllegalArgumentException");
                    assertThat(error.getDetail()).isEqualTo("Root agent snapshot is required");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                });
    }

    @Test
    void chat_singleAgent_returnsModelAnswer() {
        when(chatModelFactory.createChatModel(any())).thenReturn(new StaticChatModel("pong"));

        var response = service.chat(runtimeRequest(rootAgent(), "ping"));

        assertThat(response.getMessage()).isEqualTo("pong");
    }

    @Test
    void chat_capturesAndCachesPropagatedHeaders() {
        when(chatModelFactory.createChatModel(any())).thenReturn(new StaticChatModel("pong"));

        service.chat(runtimeRequest(rootAgent(), "ping"));

        verify(mcpPropagatedHeaders).currentHeaders();
    }

    @Test
    void chat_a2aEnabledButGroupHasNoDelegates_fallsBackToRootAgent() {
        AgentGroupSnapshotDTO emptyGroup = new AgentGroupSnapshotDTO();
        emptyGroup.setName("Empty group");

        AgentSnapshotDTO rootAgent = rootAgent();
        rootAgent.setA2aEnabled(true);
        rootAgent.setGroups(List.of(emptyGroup));

        when(chatModelFactory.createChatModel(any())).thenReturn(new StaticChatModel("root answer"));

        var response = service.chat(runtimeRequest(rootAgent, "hello"));

        assertThat(response.getMessage()).isEqualTo("root answer");
    }

    @Test
    void chat_modelCreationFails_throwsServerError() {
        when(chatModelFactory.createChatModel(any())).thenThrow(new IllegalArgumentException("bad model"));

        assertThatThrownBy(() -> service.chat(runtimeRequest(rootAgent(), "ping")))
                .isInstanceOf(RuntimeChatException.class)
                .satisfies(ex -> {
                    RuntimeChatException error = (RuntimeChatException) ex;
                    assertThat(error.getErrorCode()).isEqualTo("RUNTIME_CHAT_FAILED");
                    assertThat(error.getErrorType()).isEqualTo("IllegalArgumentException");
                    assertThat(error.getDetail()).isEqualTo("bad model");
                    assertThat(error.getStatusCode()).isEqualTo(500);
                });
    }

    @Test
    void chat_runtimeTimeout_throwsGatewayTimeout() {
        service.runtimeTimeout = 1L;
        when(chatModelFactory.createChatModel(any())).thenReturn(new SleepingChatModel());

        assertThatThrownBy(() -> service.chat(runtimeRequest(rootAgent(), "ping")))
                .isInstanceOf(RuntimeChatException.class)
                .satisfies(ex -> {
                    RuntimeChatException error = (RuntimeChatException) ex;
                    assertThat(error.getErrorCode()).isEqualTo("RUNTIME_CHAT_TIMEOUT");
                    assertThat(error.getErrorType()).isEqualTo("TimeoutException");
                    assertThat(error.getStatusCode()).isEqualTo(504);
                });
    }

    @Test
    void chat_includesConversationHistoryInLlmRequest() {
        CapturingChatModel model = new CapturingChatModel("pong");
        when(chatModelFactory.createChatModel(any())).thenReturn(model);

        RuntimeChatRequestDTO request = runtimeRequest(rootAgent(), "current question");
        ConversationDTO conversation = new ConversationDTO();
        conversation.setHistory(List.of(chatMessage("USER", "first"), chatMessage("ASSISTANT", "second")));
        request.getChatRequest().setConversation(conversation);

        var response = service.chat(request);

        assertThat(response.getMessage()).isEqualTo("pong");
        assertThat(model.lastRequest).isNotNull();
        List<String> userPayloads = model.lastRequest.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(RuntimeChatServiceTest::extractUserMessageText)
                .toList();
        assertThat(userPayloads).isNotEmpty().anyMatch(payload -> payload.contains("Conversation history:")
                && payload.contains("USER: first")
                && payload.contains("ASSISTANT: second")
                && payload.contains("Current user message:")
                && payload.contains("current question"));
    }

    private RuntimeChatRequestDTO runtimeRequest(AgentSnapshotDTO rootAgent, String text) {
        ChatMessageDTO message = new ChatMessageDTO();
        message.setType("USER");
        message.setMessage(text);

        ChatRequestDTO chatRequest = new ChatRequestDTO();
        chatRequest.setChatMessage(message);

        RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
        request.setRootAgent(rootAgent);
        request.setChatRequest(chatRequest);
        return request;
    }

    private AgentSnapshotDTO rootAgent() {
        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setName("Root");
        agent.setDescription("Root agent");
        agent.setAdditionalPrompt("Answer directly.");
        return agent;
    }

    private ChatMessageDTO chatMessage(String type, String text) {
        ChatMessageDTO message = new ChatMessageDTO();
        message.setType(type);
        message.setMessage(text);
        return message;
    }

    private static String extractUserMessageText(UserMessage message) {
        try {
            Method singleText = UserMessage.class.getMethod("singleText");
            Object value = singleText.invoke(message);
            return value != null ? value.toString() : "";
        } catch (Exception ignored) {
            try {
                Method text = UserMessage.class.getMethod("text");
                Object value = text.invoke(message);
                return value != null ? value.toString() : "";
            } catch (Exception ex) {
                return message.toString();
            }
        }
    }

    @Test
    void chat_withAlwaysAskTool_buildsConfirmationDescriptionInToolSpec() {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = ToolSpecification.builder()
                .name("dangerous_tool")
                .description("Delete everything")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("target")
                        .required("target")
                        .build())
                .build();
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                null, "ALWAYS_ASK");
        when(mcpService.createToolRegistry(any(), any())).thenReturn(new McpToolRegistry(List.of(alwaysAskTool)));
        when(chatModelFactory.createChatModel(any())).thenReturn(new StaticChatModel("pong"));

        var response = service.chat(runtimeRequest(rootAgent(), "hello"));

        assertThat(response).isNotNull();
    }

    @Test
    void chat_withAlwaysAskTool_returnsConfirmationRequiredWhenNoUserConfirmation() {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = ToolSpecification.builder()
                .name("dangerous_tool")
                .description("Delete everything")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("target")
                        .required("target")
                        .build())
                .build();
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                "ALWAYS_ASK", null);
        when(mcpService.createToolRegistry(any(), any())).thenReturn(new McpToolRegistry(List.of(alwaysAskTool)));

        ConfirmationDetectingChatModel model = new ConfirmationDetectingChatModel("NO",
                AiMessage.from(ToolExecutionRequest.builder()
                        .name("dangerous_tool")
                        .arguments("{\"target\":\"all\"}")
                        .build()));
        when(chatModelFactory.createChatModel(any())).thenReturn(model);

        var response = service.chat(runtimeRequest(rootAgent(), "delete everything"));

        assertThat(response).isNotNull();
        assertThat(response.getMessage()).contains("CONFIRMATION REQUIRED");
    }

    @Test
    void chat_withAlwaysAskTool_executesWhenUserConfirms() {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = ToolSpecification.builder()
                .name("dangerous_tool")
                .description("Delete everything")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("target")
                        .required("target")
                        .build())
                .build();
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                "ALWAYS_ASK", null);
        when(mcpService.createToolRegistry(any(), any())).thenReturn(new McpToolRegistry(List.of(alwaysAskTool)));

        ToolExecutionResult toolResult = mock(ToolExecutionResult.class);
        when(toolResult.resultText()).thenReturn("deleted successfully");
        when(client.executeTool(any())).thenReturn(toolResult);

        ConfirmationDetectingChatModel model = new ConfirmationDetectingChatModel("YES",
                AiMessage.from(ToolExecutionRequest.builder()
                        .name("dangerous_tool")
                        .arguments("{\"target\":\"all\"}")
                        .build()));
        when(chatModelFactory.createChatModel(any())).thenReturn(model);

        var response = service.chat(runtimeRequest(rootAgent(), "yes, delete everything"));

        assertThat(response).isNotNull();
    }

    private DispatchConfig dispatchConfig() {
        DispatchConfig dispatchConfig = mock(DispatchConfig.class);
        DispatchConfig.ToolConfig toolConfig = mock(DispatchConfig.ToolConfig.class);
        when(toolConfig.maxIterations()).thenReturn(3L);
        when(dispatchConfig.toolConfig()).thenReturn(toolConfig);
        return dispatchConfig;
    }

    private static final class StaticChatModel implements ChatModel {

        private final String response;

        private StaticChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    private static final class CapturingChatModel implements ChatModel {

        private final String response;
        private volatile ChatRequest lastRequest;

        private CapturingChatModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            this.lastRequest = chatRequest;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    private static final class SleepingChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("late answer"))
                    .build();
        }
    }

    private static final class ConfirmationDetectingChatModel implements ChatModel {

        private final String confirmationResponse;
        private final AiMessage toolCallResponse;
        private int callCount = 0;

        private ConfirmationDetectingChatModel(String confirmationResponse, AiMessage toolCallResponse) {
            this.confirmationResponse = confirmationResponse;
            this.toolCallResponse = toolCallResponse;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            callCount++;
            if (callCount == 1) {
                return ChatResponse.builder()
                        .aiMessage(toolCallResponse)
                        .build();
            }
            if (callCount == 2) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(confirmationResponse))
                        .build();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("CONFIRMATION REQUIRED: Please confirm."))
                    .build();
        }
    }
}
