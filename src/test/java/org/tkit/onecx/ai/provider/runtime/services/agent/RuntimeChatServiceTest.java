package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.external.ExternalAgentDiscoveryService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;
import org.tkit.onecx.ai.provider.runtime.services.provider.ChatModelFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentGroupSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeStatusDTO;
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
    void chat_withoutRootAgent_returnsFailedResponse() {
        var response = service.chat(new RuntimeChatRequestDTO());

        assertThat(response.getStatus()).isEqualTo(RuntimeStatusDTO.FAILED);
        assertThat(response.getErrorType()).isEqualTo("IllegalArgumentException");
        assertThat(response.getErrorMessage()).isEqualTo("Root agent snapshot is required");
    }

    @Test
    void chat_singleAgent_returnsModelAnswer() {
        when(chatModelFactory.createChatModel(any())).thenReturn(new StaticChatModel("pong"));

        var response = service.chat(runtimeRequest(rootAgent(), "ping"));

        assertThat(response.getStatus()).isEqualTo(RuntimeStatusDTO.SUCCESS);
        assertThat(response.getMessage()).isEqualTo("pong");
    }

    @Test
    void chat_a2aEnabledButGroupHasNoDelegates_fallsBackToRootAgent() {
        AgentGroupSnapshotDTO emptyGroup = new AgentGroupSnapshotDTO();
        emptyGroup.setId("group-empty");
        emptyGroup.setName("Empty group");

        AgentSnapshotDTO rootAgent = rootAgent();
        rootAgent.setA2aEnabled(true);
        rootAgent.setGroups(List.of(emptyGroup));

        when(chatModelFactory.createChatModel(any())).thenReturn(new StaticChatModel("root answer"));

        var response = service.chat(runtimeRequest(rootAgent, "hello"));

        assertThat(response.getStatus()).isEqualTo(RuntimeStatusDTO.SUCCESS);
        assertThat(response.getMessage()).isEqualTo("root answer");
    }

    @Test
    void chat_modelCreationFails_returnsFailedResponse() {
        when(chatModelFactory.createChatModel(any())).thenThrow(new IllegalArgumentException("bad model"));

        var response = service.chat(runtimeRequest(rootAgent(), "ping"));

        assertThat(response.getStatus()).isEqualTo(RuntimeStatusDTO.FAILED);
        assertThat(response.getErrorType()).isEqualTo("IllegalArgumentException");
        assertThat(response.getErrorMessage()).isEqualTo("bad model");
    }

    @Test
    void chat_runtimeTimeout_returnsTimeoutResponse() {
        service.runtimeTimeout = 1L;
        when(chatModelFactory.createChatModel(any())).thenReturn(new SleepingChatModel());

        var response = service.chat(runtimeRequest(rootAgent(), "ping"));

        assertThat(response.getStatus()).isEqualTo(RuntimeStatusDTO.TIMEOUT);
        assertThat(response.getErrorType()).isEqualTo("TimeoutException");
        assertThat(response.getErrorMessage()).contains("Dispatch exceeded runtime timeout of 1 seconds");
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
        agent.setId("root");
        agent.setName("Root");
        agent.setDescription("Root agent");
        agent.setAdditionalPrompt("Answer directly.");
        return agent;
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
