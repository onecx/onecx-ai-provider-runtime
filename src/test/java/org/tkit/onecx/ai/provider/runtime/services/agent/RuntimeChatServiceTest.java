package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.external.ExternalAgentDiscoveryService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;
import org.tkit.onecx.ai.provider.runtime.services.provider.ChatModelFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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

    @BeforeEach
    void setUp() {
        chatModelFactory = mock(ChatModelFactory.class);
        mcpService = mock(McpService.class);

        service = new RuntimeChatService();
        service.chatModelFactory = chatModelFactory;
        service.scaffoldPromptComposer = new ScaffoldPromptComposer();
        service.runtimeSkillService = new RuntimeSkillService();
        service.mcpService = mcpService;
        service.externalAgentDiscoveryService = mock(ExternalAgentDiscoveryService.class);
        service.dispatchConfig = dispatchConfig();
        service.objectMapper = new ObjectMapper();
        service.runtimeTimeout = 120L;

        when(mcpService.createToolRegistry(any())).thenReturn(McpToolRegistry.empty());
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
        assertThat(userPayloads).isNotEmpty();
        assertThat(userPayloads).anyMatch(payload -> payload.contains("Conversation history:")
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

    private DispatchConfig dispatchConfig() {
        DispatchConfig dispatchConfig = mock(DispatchConfig.class);
        DispatchConfig.MCPConfig mcpConfig = mock(DispatchConfig.MCPConfig.class);
        when(mcpConfig.maxIterations()).thenReturn(3L);
        when(dispatchConfig.mcpConfig()).thenReturn(mcpConfig);
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
}
