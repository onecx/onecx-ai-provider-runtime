package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.external.AgentCard;
import org.tkit.onecx.ai.provider.runtime.services.external.ExternalAgentDiscoveryService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpPropagatedHeaders;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpTool;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;
import org.tkit.onecx.ai.provider.runtime.services.provider.ChatModelFactory;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.agent.AgentInvocationException;
import dev.langchain4j.agentic.agent.ErrorContext;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.internal.A2AClientBuilder;
import dev.langchain4j.agentic.internal.AgentExecutor;
import dev.langchain4j.agentic.internal.AgentSpecsProvider;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorAgentService;
import dev.langchain4j.agentic.workflow.ParallelAgentService;
import dev.langchain4j.agentic.workflow.SequentialAgentService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.Skills;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentGroupSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ConversationDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ExternalAgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatResponseDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeChatServiceReflectionTest {

    RuntimeChatService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeChatService();
        service.objectMapper = new ObjectMapper();
        service.dispatchConfig = dispatchConfig(3L);
        service.scaffoldPromptComposer = new ScaffoldPromptComposer();
        service.externalAgentDiscoveryService = mock(ExternalAgentDiscoveryService.class);
        service.mcpPropagatedHeaders = mock(McpPropagatedHeaders.class);
        when(service.mcpPropagatedHeaders.currentHeaders()).thenReturn(Map.of());
    }

    @Test
    void privateMessageHelpers_coverRequestAndHistoryFormatting() throws Exception {
        ChatRequestDTO request = chatRequest("hello");
        ChatMessageDTO previous = message("USER", "first");
        ChatMessageDTO answer = message("ASSISTANT", "second");
        ConversationDTO conversation = new ConversationDTO();
        conversation.setHistory(List.of(previous, answer));
        request.setConversation(conversation);

        @SuppressWarnings("unchecked")
        Map<String, Object> agentInput = (Map<String, Object>) invoke("agentInput",
                new Class[] { ChatRequestDTO.class }, request);
        assertThat(agentInput)
                .containsEntry("message", "hello")
                .containsEntry("request", request);
        assertThat(invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class }, request, "current"))
                .asString()
                .contains("Conversation history:")
                .contains("USER: first")
                .contains("ASSISTANT: second")
                .contains("Current user message:")
                .contains("current");
        assertThat(invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class }, chatRequest("only"), "only"))
                .isEqualTo("Current user message:" + System.lineSeparator() + "only");
        assertThat(invoke("inputMessage", new Class[] { Object.class, String.class }, "direct", "fallback"))
                .isEqualTo("direct");
        assertThat(invoke("inputMessage", new Class[] { Object.class, String.class }, Map.of("message", "mapped"),
                "fallback")).isEqualTo("mapped");
        assertThat(invoke("inputMessage", new Class[] { Object.class, String.class }, Map.of("other", "x"),
                "fallback")).isEqualTo("fallback");
        assertThat((String) invoke("extractUserMessage", new Class[] { ChatRequestDTO.class }, new ChatRequestDTO())).isEmpty();
    }

    @Test
    void privateSystemPromptHelpers_coverDelegationAndSupervisorText() throws Exception {
        AgentSnapshotDTO agent = agent("Root", "Root description");
        agent.setAdditionalPrompt("Agent prompt");
        StaticUntypedAgent peerAgent = new StaticUntypedAgent("peer answer");
        RuntimeAgentDelegate delegate = new RuntimeAgentDelegate("Peer Agent", "Peer description",
                () -> new RuntimeAgent("peer", "desc", peerAgent, null));

        String systemMessage = (String) invoke("systemMessage",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class, Skills.class,
                        boolean.class },
                agent, chatRequest("hello"), List.of(delegate), null, false);
        assertThat(systemMessage)
                .contains("Agent prompt")
                .contains("Peer Agent")
                .contains("Peer description");

        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setDescription("Group description");
        group.setRoutingInstructions("Route carefully");
        String supervisorRequest = (String) invoke("supervisorRequest",
                new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, List.class },
                agent, group, chatRequest("Need help"),
                List.of(new RuntimeAgent("Peer", "Peer specialist", new StaticUntypedAgent("x"), null)));
        assertThat(supervisorRequest)
                .contains("Need help")
                .contains("Group description")
                .contains("Route carefully")
                .contains("Peer specialist");
    }

    @Test
    void privateToolExecutorHelpers_coverMcpAndDelegateExecution() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("search_docs");
        McpTool tool = new McpTool("tool-id", "http://mcp", spec, client);
        ToolExecutionResult result = mock(
                ToolExecutionResult.class);
        when(result.resultText()).thenReturn("docs result");
        when(client.executeTool(ArgumentMatchers.any()))
                .thenReturn(result);

        @SuppressWarnings("unchecked")
        Map<ToolSpecification, ToolExecutor> mcpExecutors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class, ChatRequestDTO.class, ChatModel.class },
                new McpToolRegistry(List.of(tool)), chatRequest("hello"), mock(ChatModel.class));
        assertThat(mcpExecutors).containsKey(spec);
        assertThat(mcpExecutors.get(spec).execute(toolRequest("search_docs", "{\"query\":\"onecx\"}"), null))
                .isEqualTo("docs result");

        StaticUntypedAgent firstAgent = new StaticUntypedAgent("peer answer");
        RuntimeAgentDelegate first = new RuntimeAgentDelegate("OneCX Agent", "Docs expert",
                () -> new RuntimeAgent("peer", "desc", firstAgent, null));
        StaticUntypedAgent secondAgent = new StaticUntypedAgent("second answer");
        RuntimeAgentDelegate second = new RuntimeAgentDelegate("OneCX Agent", "",
                () -> new RuntimeAgent("peer", "desc", secondAgent, null));

        @SuppressWarnings("unchecked")
        Map<ToolSpecification, ToolExecutor> delegateExecutors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toDelegateToolExecutors", new Class[] { List.class }, List.of(first, second));
        assertThat(delegateExecutors.keySet()).extracting(ToolSpecification::name)
                .containsExactly("delegate_onecx_agent_1", "delegate_onecx_agent_2");
        ToolExecutor executor = delegateExecutors.values().iterator().next();
        assertThat(executor.execute(toolRequest("delegate_onecx_agent_1", "{\"message\":\"What is OneCX?\"}"), null))
                .isEqualTo("peer answer");

        RuntimeAgentDelegate nullDelegate = new RuntimeAgentDelegate("Null Agent", "", () -> null);
        assertThat(
                (String) invoke("invokeDelegate", new Class[] { RuntimeAgentDelegate.class, String.class }, nullDelegate, "x"))
                .isEmpty();
        ThrowingUntypedAgent throwingAgent = new ThrowingUntypedAgent();
        RuntimeAgentDelegate throwingDelegate = new RuntimeAgentDelegate("Bad Agent", "",
                () -> new RuntimeAgent("bad", "desc", throwingAgent, null));
        assertThat(invoke("invokeDelegate", new Class[] { RuntimeAgentDelegate.class, String.class }, throwingDelegate,
                "x")).asString().contains("Bad Agent");
    }

    @Test
    void privateExtractionHelpers_coverTextToolCallsAndArguments() throws Exception {
        @SuppressWarnings("unchecked")
        List<ToolExecutionRequest> requests = (List<ToolExecutionRequest>) invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class },
                "Before [{\"tool_name\":\"search_docs\",\"arguments\":{\"query\":\"onecx\"}}] after",
                Set.of("search_docs"));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst())
                .satisfies(req -> assertThat(req.name()).isEqualTo("search_docs"),
                        req -> assertThat(req.arguments()).contains("onecx"));

        assertThat(invoke("extractTextToolCalls", new Class[] { String.class, Set.class }, "no json",
                Set.of("search_docs"))).isEqualTo(List.of());
        assertThat(invoke("extractToolMessage", new Class[] { String.class }, "{\"message\":\"hello\"}"))
                .isEqualTo("hello");
        assertThat(invoke("extractToolMessage", new Class[] { String.class }, "not-json")).isEqualTo("not-json");
        assertThat((String) invoke("extractToolMessage", new Class[] { String.class }, " ")).isEmpty();

        @SuppressWarnings("unchecked")
        List<ToolExecutionRequest> argsRequests = (List<ToolExecutionRequest>) invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class },
                "{\"tool\":\"search_docs\",\"args\":\"{\\\"query\\\":\\\"escaped\\\"}\"}", Set.of("search_docs"));
        assertThat(argsRequests).hasSize(1);
        assertThat(argsRequests.getFirst().arguments()).contains("escaped");

        assertThat(invoke("extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"other\",\"arguments\":{}}", Set.of("search_docs"))).isEqualTo(List.of());
        assertThat(invoke("extractTextToolCalls", new Class[] { String.class, Set.class }, "[1,2,3]",
                Set.of("search_docs"))).isEqualTo(List.of());

        @SuppressWarnings("unchecked")
        List<ToolExecutionRequest> blankArgs = (List<ToolExecutionRequest>) invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class }, "{\"name\":\"search_docs\",\"arguments\":\"\"}",
                Set.of("search_docs"));
        assertThat(blankArgs.getFirst().arguments()).isEqualTo("{}");

        @SuppressWarnings("unchecked")
        List<ToolExecutionRequest> nullArgs = (List<ToolExecutionRequest>) invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class }, "{\"name\":\"search_docs\",\"arguments\":null}",
                Set.of("search_docs"));
        assertThat(nullArgs.getFirst().arguments()).isEqualTo("{}");

        assertThat(invoke("extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"search_docs\",\"arguments\":{\"bad\":\"unterminated}", Set.of("search_docs")))
                .isEqualTo(List.of());
    }

    @Test
    void privateDecisionHelpers_coverNamesModesAndLimits() throws Exception {
        assertThat(invoke("toSupervisorResponseStrategy", new Class[] { Object.class }, "LAST").toString())
                .isEqualTo("LAST");
        assertThat(invoke("toSupervisorResponseStrategy", new Class[] { Object.class }, "SCORED").toString())
                .isEqualTo("SCORED");
        assertThat(invoke("toSupervisorResponseStrategy", new Class[] { Object.class }, (Object) null).toString())
                .isEqualTo("SUMMARY");

        service.dispatchConfig = dispatchConfig(0L);
        assertThat(invoke("maxSequentialToolInvocations", new Class[] {})).isEqualTo(1);
        service.dispatchConfig = dispatchConfig((long) Integer.MAX_VALUE + 1L);
        assertThat(invoke("maxSequentialToolInvocations", new Class[] {})).isEqualTo(Integer.MAX_VALUE);

        assertThat(invoke("delegateToolBaseName", new Class[] { String.class }, "OneCX Agent!")).isEqualTo(
                "delegate_onecx_agent");
        assertThat(invoke("runtimeName", new Class[] { AgentSnapshotDTO.class }, new AgentSnapshotDTO()))
                .isEqualTo("local-agent");
        assertThat(invoke("runtimeDescription", new Class[] { AgentSnapshotDTO.class }, new AgentSnapshotDTO()))
                .isEqualTo("Configured local agent");
        assertThat(invoke("runtimeName", new Class[] { ExternalAgentSnapshotDTO.class }, new ExternalAgentSnapshotDTO()))
                .isEqualTo("remote-agent");
        assertThat(invoke("runtimeDescription", new Class[] { ExternalAgentSnapshotDTO.class },
                new ExternalAgentSnapshotDTO())).isEqualTo("Discovered remote A2A agent");
    }

    @Test
    void privateRemoteAndDelegateDiscoveryHelpers_coverSkipsAndSorting() throws Exception {
        AgentSnapshotDTO live = agent("B local", "Local B");
        AgentSnapshotDTO defaultStatus = agent("A local", "Local A");

        ExternalAgentSnapshotDTO remote = externalAgent("C remote", "Remote C", true, "http://discover", null);
        ExternalAgentSnapshotDTO disabled = externalAgent("Disabled", "Nope", false, "http://disabled", null);

        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setAgents(List.of(live, defaultStatus));
        group.setExternalAgents(List.of(disabled, remote));

        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> delegates = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class }, group, chatRequest("hi"),
                Map.of());
        assertThat(delegates).extracting(RuntimeAgentDelegate::name)
                .containsExactly("A local", "B local", "C remote");

        assertThat(invoke("delegatesForGroup", new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                null, chatRequest("hi"), Map.of())).isEqualTo(List.of());

        assertThat(invoke("buildRemoteAgent", new Class[] { ExternalAgentSnapshotDTO.class },
                externalAgent("Auth", "Auth", true, "http://discover", "secret"))).isNull();
        when(service.externalAgentDiscoveryService.fetchAgentCard("http://missing")).thenReturn(null);
        assertThat(invoke("buildRemoteAgent", new Class[] { ExternalAgentSnapshotDTO.class },
                externalAgent("Missing", "Missing", true, "http://missing", null))).isNull();
        when(service.externalAgentDiscoveryService.fetchAgentCard("http://blank"))
                .thenReturn(new AgentCard("Blank", "Blank", " "));
        assertThat(invoke("buildRemoteAgent", new Class[] { ExternalAgentSnapshotDTO.class },
                externalAgent("Blank", "Blank", true, "http://blank", null))).isNull();
    }

    @Test
    void textToolCallNormalizingChatModel_convertsJsonTextToToolRequest() throws Exception {
        Object normalizer = textToolCallNormalizingChatModel(new StaticChatModel("""
                I need a tool.
                {"name":"search_docs","arguments":{"query":"onecx"}}
                """), Set.of("search_docs"));

        ChatResponse response = ((ChatModel) normalizer).chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .build());

        assertThat(response.aiMessage())
                .satisfies(ai -> assertThat(ai.hasToolExecutionRequests()).isTrue(),
                        ai -> assertThat(ai.toolExecutionRequests().getFirst().name()).isEqualTo("search_docs"));
    }

    @Test
    void textToolCallNormalizingChatModel_keepsNullAndNonToolResponses() throws Exception {
        Object nullNormalizer = textToolCallNormalizingChatModel(new NullChatModel(), Set.of("search_docs"));
        assertThat(((ChatModel) nullNormalizer).chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .build())).isNull();

        Object plainNormalizer = textToolCallNormalizingChatModel(new StaticChatModel("plain answer"), Set.of("search_docs"));
        ChatResponse response = ((ChatModel) plainNormalizer).chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .build());

        assertThat(response.aiMessage())
                .satisfies(ai -> assertThat(ai.text()).isEqualTo("plain answer"),
                        ai -> assertThat(ai.hasToolExecutionRequests()).isFalse());
    }

    @Test
    void textToolCallNormalizingChatModel_returnsResponseWhenAiMessageIsNull() throws Exception {
        ChatResponse nullAiMessageResponse = mock(ChatResponse.class);
        when(nullAiMessageResponse.aiMessage()).thenReturn(null);
        ChatModel nullAiMessageModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                return nullAiMessageResponse;
            }
        };
        Object normalizer = textToolCallNormalizingChatModel(nullAiMessageModel, Set.of("search_docs"));
        ChatResponse response = ((ChatModel) normalizer).chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .build());
        assertThat(response).isNotNull();
        assertThat(response.aiMessage()).isNull();
    }

    @Test
    void lazySupervisorAgenticAction_invokesSelectedAgentAndFallbacks() throws Exception {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.readState("message")).thenReturn("scoped message");
        StaticUntypedAgent peerAgent = new StaticUntypedAgent("peer answer");
        Object action = lazySupervisorAction(
                () -> new RuntimeAgent("peer", "desc", peerAgent, null),
                "fallback message");

        assertThat(invoke(action, "invoke", new Class[] { AgenticScope.class }, scope)).isEqualTo("peer answer");
        assertThat((AgentSpecsProvider) action)
                .extracting(AgentSpecsProvider::outputKey, AgentSpecsProvider::description,
                        AgentSpecsProvider::async, AgentSpecsProvider::listener)
                .containsExactly("response", "Lazy description", false, null);

        EchoUntypedAgent echoAgent = new EchoUntypedAgent();
        Object fallbackAction = lazySupervisorAction(() -> new RuntimeAgent("peer", "desc", echoAgent, null),
                "fallback message");
        assertThat(invoke(fallbackAction, "invoke", new Class[] { AgenticScope.class }, (Object) null))
                .isEqualTo("fallback message");

        Object nullAgentAction = lazySupervisorAction(() -> null, "fallback message");
        assertThat((String) invoke(nullAgentAction, "invoke", new Class[] { AgenticScope.class }, scope)).isEmpty();

        // message state is blank → falls back to fallbackMessage
        AgenticScope blankScope = mock(AgenticScope.class);
        when(blankScope.readState("message")).thenReturn("   ");
        StaticUntypedAgent blankAgent = new StaticUntypedAgent("blank fallback result");
        Object blankAction = lazySupervisorAction(
                () -> new RuntimeAgent("peer", "desc", blankAgent, null),
                "fallback for blank");
        assertThat(invoke(blankAction, "invoke", new Class[] { AgenticScope.class }, blankScope))
                .isEqualTo("blank fallback result");

        // agent returns null result → response is ""
        NullResultUntypedAgent nullResultAgent = new NullResultUntypedAgent();
        Object nullResultAction = lazySupervisorAction(
                () -> new RuntimeAgent("peer", "desc", nullResultAgent, null),
                "fallback");
        assertThat((String) invoke(nullResultAction, "invoke", new Class[] { AgenticScope.class }, scope))
                .isEmpty();
    }

    @Test
    void localAgenticAction_resolvesMessageFromScopeContextAndSpecs() throws Exception {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.readState("message")).thenReturn(" ");
        when(scope.contextAsConversation()).thenReturn("conversation context");
        Object action = localAgenticAction();

        assertThat(invoke(action, "invoke", new Class[] { AgenticScope.class }, scope)).isEqualTo("conversation context");
        assertThat((String) invoke(action, "invoke", new Class[] { AgenticScope.class }, (Object) null)).isEmpty();
        assertThat((AgentSpecsProvider) action)
                .extracting(AgentSpecsProvider::outputKey, AgentSpecsProvider::description,
                        AgentSpecsProvider::async, AgentSpecsProvider::listener)
                .containsExactly("response", "Local description", false, null);
        assertThat(invoke(action, "toAgentExecutor", new Class[] {})).isInstanceOf(AgentExecutor.class);

        // null messageComposer → falls back to UnaryOperator.identity()
        Class<?> actionType = Class.forName(RuntimeChatService.class.getName() + "$LocalAgenticAction");
        Class<?> agentType = Class.forName(RuntimeChatService.class.getName() + "$LocalChatAgent");
        Object chatAgent = Proxy.newProxyInstance(agentType.getClassLoader(), new Class[] { agentType },
                (proxy, method, args) -> args != null && args.length > 0 ? args[0] : "");
        Constructor<?> nullComposerCtor = actionType.getDeclaredConstructor(String.class, String.class, agentType,
                UnaryOperator.class);
        nullComposerCtor.setAccessible(true);
        Object nullComposerAction = nullComposerCtor.newInstance("Agent", "Desc", chatAgent, null);
        AgenticScope msgScope = mock(AgenticScope.class);
        when(msgScope.readState("message")).thenReturn("hello");
        assertThat(invoke(nullComposerAction, "invoke", new Class[] { AgenticScope.class }, msgScope))
                .isEqualTo("hello");
    }

    @Test
    void agenticWorkflowInvocationAdapter_staticHelpersCoverEmptyAndLastOutput() throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$AgenticWorkflowInvocationAdapter");

        AgenticScope emptyScope = mock(AgenticScope.class);
        when(emptyScope.agentInvocations()).thenReturn(List.of());
        assertThat((String) invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class }, (Object) null)).isEmpty();
        assertThat((String) invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class }, emptyScope)).isEmpty();

        AgentInvocation blankInvocation = mock(AgentInvocation.class);
        when(blankInvocation.output()).thenReturn(" ");
        AgentInvocation firstInvocation = mock(AgentInvocation.class);
        when(firstInvocation.output()).thenReturn("first");
        AgentInvocation lastInvocation = mock(AgentInvocation.class);
        when(lastInvocation.output()).thenReturn("last");
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.agentInvocations()).thenReturn(List.of(blankInvocation, firstInvocation, lastInvocation));
        assertThat(invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class }, scope)).isEqualTo("last");

        // scope with null agentInvocations
        AgenticScope nullInvocationsScope = mock(AgenticScope.class);
        when(nullInvocationsScope.agentInvocations()).thenReturn(null);
        assertThat((String) invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class },
                nullInvocationsScope)).isEmpty();

        assertThat(invokeStatic(type, "safeName", new Class[] { String.class }, " Root Agent! ")).isEqualTo("root-agent");
        assertThat(invokeStatic(type, "safeName", new Class[] { String.class }, (Object) null)).isEqualTo("agent");
    }

    @Test
    void agenticWorkflowInvocationAdapter_invokeAndGetAgenticScope_covered() throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$AgenticWorkflowInvocationAdapter");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, Object.class);
        constructor.setAccessible(true);

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent mockWorkflow = new StaticUntypedAgent("adapter result");
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            Object adapter = constructor.newInstance("test-agent", new StaticUntypedAgent("delegate"));

            // Cover invoke(Map) — delegates to invokeWithAgenticScope().result()
            Object invokeResult = invoke(adapter, "invoke",
                    new Class[] { Map.class }, Map.of("message", "hello"));
            assertThat(invokeResult).isEqualTo("adapter result");

            // Cover invokeWithAgenticScope with null input
            Object scopeResult = invoke(adapter, "invokeWithAgenticScope",
                    new Class[] { Map.class }, (Object) null);
            assertThat(scopeResult).isNotNull();

            // Cover getAgenticScope — returns null
            Object agenticScope = invoke(adapter, "getAgenticScope",
                    new Class[] { Object.class }, "memory-1");
            assertThat(agenticScope).isNull();

            // Cover evictAgenticScope — returns false
            Object evicted = invoke(adapter, "evictAgenticScope",
                    new Class[] { Object.class }, "memory-1");
            assertThat(evicted).isEqualTo(false);
        }
    }

    @Test
    void agenticWorkflowInvocationAdapter_returnsEmptyResultWhenWorkflowReturnsNull() throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$AgenticWorkflowInvocationAdapter");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, Object.class);
        constructor.setAccessible(true);

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent nullWorkflow = mock(UntypedAgent.class);
            when(nullWorkflow.invokeWithAgenticScope(ArgumentMatchers.any()))
                    .thenReturn(null);
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(nullWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            Object adapter = constructor.newInstance("test-agent", new StaticUntypedAgent("delegate"));
            Object result = invoke(adapter, "invokeWithAgenticScope",
                    new Class[] { Map.class }, Map.of("message", "hello"));
            assertThat(result).isNotNull();
            assertThat((String) ((ResultWithAgenticScope<?>) result).result()).isEmpty();
        }
    }

    @Test
    void methodLookup_throwsIllegalStateExceptionForNonExistentMethod() {
        assertThatThrownBy(() -> invokeStatic(RuntimeChatService.class, "methodLookup",
                new Class[] { Class.class, String.class, Class[].class },
                RuntimeChatService.class, "nonExistentMethod", new Class[] { String.class }))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonExistentMethod");
    }

    @Test
    void isUserConfirmationPresent_coversNullRequestAndHistoryScenarios() throws Exception {
        ChatModel yesModel = new StaticChatModel("YES");
        ChatModel noModel = new StaticChatModel("NO");

        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, null, null))
                .isEqualTo(false);

        ChatRequestDTO emptyRequest = new ChatRequestDTO();
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, emptyRequest, null))
                .isEqualTo(false);

        ChatRequestDTO noHistory = new ChatRequestDTO();
        ConversationDTO emptyConv = new ConversationDTO();
        emptyConv.setHistory(List.of());
        noHistory.setConversation(emptyConv);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, noHistory, null))
                .isEqualTo(false);

        ChatRequestDTO withConfirmation = chatRequest("yes");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "yes, proceed")));
        withConfirmation.setConversation(conv);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, withConfirmation, yesModel))
                .isEqualTo(true);

        ChatRequestDTO withoutConfirmation = chatRequest("no way");
        ConversationDTO conv2 = new ConversationDTO();
        conv2.setHistory(List.of(message("ASSISTANT", "Do you want to proceed?"), message("USER", "no way")));
        withoutConfirmation.setConversation(conv2);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, withoutConfirmation, noModel))
                .isEqualTo(false);

        ChatRequestDTO assistantOnly = chatRequest("hello");
        ConversationDTO conv3 = new ConversationDTO();
        conv3.setHistory(List.of(message("ASSISTANT", "hello")));
        assistantOnly.setConversation(conv3);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, assistantOnly, null))
                .isEqualTo(false);
    }

    @Test
    void withConfirmationDescription_prependsConfirmationPrefixAndPreservesOriginal() throws Exception {
        ToolSpecification original = toolSpec("search_docs");
        ToolSpecification modified = (ToolSpecification) invoke("withConfirmationDescription",
                new Class[] { ToolSpecification.class }, original);
        assertThat(modified.description())
                .contains("[REQUIRES USER CONFIRMATION]")
                .contains("Search docs");
        assertThat(modified.name()).isEqualTo("search_docs");

        ToolSpecification nullDescSpec = ToolSpecification.builder()
                .name("no_desc")
                .description(null)
                .parameters(JsonObjectSchema.builder().addStringProperty("q").build())
                .build();
        ToolSpecification modifiedNullDesc = (ToolSpecification) invoke("withConfirmationDescription",
                new Class[] { ToolSpecification.class }, nullDescSpec);
        assertThat(modifiedNullDesc.description()).contains("[REQUIRES USER CONFIRMATION]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toToolExecutors_alwaysAskTool_returnsConfirmationRequestWithoutUserConfirmation() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("dangerous_tool");
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                null, "ALWAYS_ASK");

        ToolExecutionResult result = mock(
                ToolExecutionResult.class);
        when(result.resultText()).thenReturn("executed");
        when(client.executeTool(ArgumentMatchers.any())).thenReturn(result);

        Map<ToolSpecification, ToolExecutor> executors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class, ChatRequestDTO.class, ChatModel.class },
                new McpToolRegistry(List.of(alwaysAskTool)), chatRequest("hello"), new StaticChatModel("NO"));

        ToolExecutor executor = executors.values().iterator().next();
        String response = executor.execute(toolRequest("dangerous_tool", "{}"), null);
        assertThat(response).contains("CONFIRMATION REQUIRED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toToolExecutors_alwaysAskTool_executesWhenUserConfirms() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("dangerous_tool");
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                null, "ALWAYS_ASK");

        ToolExecutionResult result = mock(
                ToolExecutionResult.class);
        when(result.resultText()).thenReturn("executed successfully");
        when(client.executeTool(ArgumentMatchers.any())).thenReturn(result);

        ChatRequestDTO request = chatRequest("yes");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "yes")));
        request.setConversation(conv);

        Map<ToolSpecification, ToolExecutor> executors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class, ChatRequestDTO.class, ChatModel.class },
                new McpToolRegistry(List.of(alwaysAskTool)), request, new StaticChatModel("YES"));

        ToolExecutor executor = executors.values().iterator().next();
        String response = executor.execute(toolRequest("dangerous_tool", "{}"), null);
        assertThat(response).isEqualTo("executed successfully");
    }

    @ParameterizedTest
    @CsvSource({ "{,{,},1", "},{,},-1", "a,{,},0" })
    void charDepthDelta_returnsCorrectDelta(char current, char open, char close, int expected) throws Exception {
        assertThat(invoke("charDepthDelta", new Class[] { char.class, char.class, char.class },
                current, open, close)).isEqualTo(expected);
    }

    @Test
    void handleAlwaysAskExecution_returnsNullWhenUserConfirms() throws Exception {
        ChatRequestDTO request = chatRequest("yes");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "yes")));
        request.setConversation(conv);

        assertThat(invoke("handleAlwaysAskExecution",
                new Class[] { ToolExecutionRequest.class, ChatRequestDTO.class, ChatModel.class },
                toolRequest("dangerous_tool", "{}"), request, new StaticChatModel("YES"))).isNull();
    }

    @Test
    void handleAlwaysAskExecution_returnsConfirmationRequestWhenNoConfirmation() throws Exception {
        String response = (String) invoke("handleAlwaysAskExecution",
                new Class[] { ToolExecutionRequest.class, ChatRequestDTO.class, ChatModel.class },
                toolRequest("dangerous_tool", "{\"query\":\"onecx\"}"), chatRequest("hello"),
                new StaticChatModel("NO"));
        assertThat(response)
                .contains("CONFIRMATION REQUIRED")
                .contains("dangerous_tool")
                .contains("{\"query\":\"onecx\"}");
    }

    @Test
    void handleAlwaysAskExecution_returnsConfirmationRequestWithEmptyArgsFallback() throws Exception {
        String response = (String) invoke("handleAlwaysAskExecution",
                new Class[] { ToolExecutionRequest.class, ChatRequestDTO.class, ChatModel.class },
                toolRequest("dangerous_tool", ""), chatRequest("hello"), new StaticChatModel("NO"));
        assertThat(response)
                .contains("CONFIRMATION REQUIRED")
                .contains("{}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toToolExecutors_alwaysAskTool_returnsConfirmationRequestWithBlankArgumentsFallback() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("dangerous_tool");
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                null, "ALWAYS_ASK");

        Map<ToolSpecification, ToolExecutor> executors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class, ChatRequestDTO.class, ChatModel.class },
                new McpToolRegistry(List.of(alwaysAskTool)), chatRequest("hello"), new StaticChatModel("NO"));

        ToolExecutor executor = executors.values().iterator().next();
        String response = executor.execute(toolRequest("dangerous_tool", "  "), null);
        assertThat(response)
                .contains("CONFIRMATION REQUIRED")
                .contains("{}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toToolExecutors_alwaysAskViaExecutionPolicy_returnsConfirmationRequest() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("dangerous_tool");
        McpTool alwaysAskTool = new McpTool("server", "http://mcp", spec, client,
                "ALWAYS_ASK", null);

        Map<ToolSpecification, ToolExecutor> executors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class, ChatRequestDTO.class, ChatModel.class },
                new McpToolRegistry(List.of(alwaysAskTool)), chatRequest("hello"), new StaticChatModel("NO"));

        ToolExecutor executor = executors.values().iterator().next();
        String response = executor.execute(toolRequest("dangerous_tool", "{}"), null);
        assertThat(response).contains("CONFIRMATION REQUIRED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void toToolExecutors_nonAlwaysAskTool_executesNormally() throws Exception {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("safe_tool");
        McpTool normalTool = new McpTool("server", "http://mcp", spec, client,
                null, "ALLOW");

        ToolExecutionResult result = mock(
                ToolExecutionResult.class);
        when(result.resultText()).thenReturn("executed");
        when(client.executeTool(ArgumentMatchers.any())).thenReturn(result);

        Map<ToolSpecification, ToolExecutor> executors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class, ChatRequestDTO.class, ChatModel.class },
                new McpToolRegistry(List.of(normalTool)), chatRequest("hello"), mock(ChatModel.class));

        ToolExecutor executor = executors.values().iterator().next();
        String response = executor.execute(toolRequest("safe_tool", "{}"), null);
        assertThat(response).isEqualTo("executed");
    }

    @Test
    void systemMessage_withHasAlwaysAskTools_appendsConfirmationInstructions() throws Exception {
        AgentSnapshotDTO agent = agent("test-agent", "desc");
        ChatRequestDTO request = chatRequest("hello");

        String message = (String) invoke("systemMessage",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class,
                        Skills.class, boolean.class },
                agent, request, null, null, true);

        assertThat(message)
                .contains("REQUIRES USER CONFIRMATION")
                .contains("explicit user confirmation")
                .contains("Always respond in the same language");
    }

    @Test
    void systemMessage_withoutHasAlwaysAskTools_omitsConfirmationInstructions() throws Exception {
        AgentSnapshotDTO agent = agent("test-agent", "desc");
        ChatRequestDTO request = chatRequest("hello");

        String message = (String) invoke("systemMessage",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class,
                        Skills.class, boolean.class },
                agent, request, null, null, false);

        assertThat(message)
                .doesNotContain("REQUIRES USER CONFIRMATION")
                .contains("Always respond in the same language");
    }

    @Test
    void mcpTool_backwardCompatibleConstructor_setsNullExecutionPolicyAndAllowed() {
        McpClient client = mock(McpClient.class);
        ToolSpecification spec = toolSpec("tool");
        McpTool tool = new McpTool("server", "http://mcp", spec, client);

        assertThat(tool)
                .satisfies(t -> assertThat(t.executionPolicy()).isNull(),
                        t -> assertThat(t.allowed()).isNull(),
                        t -> assertThat(t.serverName()).isEqualTo("server"),
                        t -> assertThat(t.toolSpecification()).isEqualTo(spec));
    }

    @Test
    void buildLocalAgent_withMockedAiServices_coversBuilderChain() throws Exception {
        RuntimeChatService svc = new RuntimeChatService();
        svc.objectMapper = new ObjectMapper();
        svc.dispatchConfig = dispatchConfig(3L);
        svc.scaffoldPromptComposer = new ScaffoldPromptComposer();
        svc.externalAgentDiscoveryService = mock(ExternalAgentDiscoveryService.class);
        svc.chatModelFactory = mock(ChatModelFactory.class);
        svc.mcpService = mock(McpService.class);
        svc.runtimeSkillService = mock(RuntimeSkillService.class);

        ChatModel chatModel = mock(ChatModel.class);
        when(svc.chatModelFactory.createChatModel(ArgumentMatchers.any())).thenReturn(chatModel);
        when(svc.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        McpClient client = mock(McpClient.class);
        McpTool alwaysAskByAllowed = new McpTool("srv", "http://mcp", toolSpec("ask-by-allowed"), client,
                null, "ALWAYS_ASK");
        when(svc.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new McpToolRegistry(List.of(alwaysAskByAllowed)));

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            @SuppressWarnings("unchecked")
            AiServices<Object> mockBuilder = setupMockLocalChatAgentWithBuilder(aiServicesStatic);
            when(mockBuilder.tools(
                    ArgumentMatchers.<Map<ToolSpecification, ToolExecutor>> any()))
                    .thenReturn(mockBuilder);
            when(mockBuilder.toolProvider(ArgumentMatchers.any())).thenReturn(mockBuilder);

            Object result = invoke(svc, "buildLocalAgent",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class, Map.class },
                    agent("test", "desc"), chatRequest("hello"), null, Map.of());
            assertThat(result).isNotNull();
        }
    }

    @Test
    void hasAlwaysAskTools_detectsByAllowedAndExecutionPolicy() throws Exception {
        McpClient client = mock(McpClient.class);

        McpTool byAllowed = new McpTool("srv", "http://mcp", toolSpec("a"), client, null, "ALWAYS_ASK");
        McpTool byPolicy = new McpTool("srv", "http://mcp", toolSpec("b"), client, "ALWAYS_ASK", null);
        McpTool neither = new McpTool("srv", "http://mcp", toolSpec("c"), client, null, null);

        assertThat(invoke(service, "hasAlwaysAskTools", new Class[] { McpToolRegistry.class },
                new McpToolRegistry(List.of(byAllowed)))).isEqualTo(true);
        assertThat(invoke(service, "hasAlwaysAskTools", new Class[] { McpToolRegistry.class },
                new McpToolRegistry(List.of(byPolicy)))).isEqualTo(true);
        assertThat(invoke(service, "hasAlwaysAskTools", new Class[] { McpToolRegistry.class },
                new McpToolRegistry(List.of(neither)))).isEqualTo(false);
        assertThat(invoke(service, "hasAlwaysAskTools", new Class[] { McpToolRegistry.class },
                McpToolRegistry.empty())).isEqualTo(false);
    }

    @Test
    void chatMemoryProvider_createsMemoryWithMaxMessagesBasedOnIterations() throws Exception {
        Object provider = invoke(service, "chatMemoryProvider", new Class[] {});
        assertThat(provider).isNotNull().isInstanceOf(ChatMemoryProvider.class);

        ChatMemoryProvider cmp = (ChatMemoryProvider) provider;
        assertThat(cmp.get("session-1")).isNotNull();
    }

    @Test
    void outputFromScope_coversNullAndNonEmptyInvocations() throws Exception {
        assertThat((String) invoke("outputFromScope", new Class[] { AgenticScope.class }, (Object) null)).isEmpty();

        AgenticScope emptyScope = mock(AgenticScope.class);
        when(emptyScope.agentInvocations()).thenReturn(List.of());
        assertThat((String) invoke("outputFromScope", new Class[] { AgenticScope.class }, emptyScope)).isEmpty();

        AgentInvocation first = mock(AgentInvocation.class);
        when(first.output()).thenReturn("first");
        AgentInvocation second = mock(AgentInvocation.class);
        when(second.output()).thenReturn("second");
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.agentInvocations()).thenReturn(List.of(first, second));
        assertThat(invoke("outputFromScope", new Class[] { AgenticScope.class }, scope))
                .isEqualTo("first" + System.lineSeparator() + "second");
    }

    @Test
    void rootCause_unwrapsNestedCausesAndHandlesNull() throws Exception {
        assertThat(invoke("rootCause", new Class[] { Throwable.class }, (Object) null))
                .isInstanceOf(RuntimeException.class)
                .satisfies(t -> assertThat(((Throwable) t).getMessage()).isEqualTo("unknown failure"));

        Exception inner = new IllegalArgumentException("inner");
        Exception outer = new RuntimeException("outer", inner);
        assertThat(invoke("rootCause", new Class[] { Throwable.class }, outer)).isSameAs(inner);
    }

    @Test
    void runtimeChatException_mapsTimeoutAndGenericCauses() throws Exception {
        var timeoutEx = new TimeoutException("timed out");
        var timeoutResult = invoke("runtimeChatException", new Class[] { Throwable.class, String.class },
                timeoutEx, "fallback");
        assertThat(timeoutResult).isInstanceOf(RuntimeChatException.class)
                .satisfies(e -> assertThat(((RuntimeChatException) e).getStatusCode())
                        .isEqualTo(Response.Status.GATEWAY_TIMEOUT.getStatusCode()));

        var genericEx = new RuntimeException("generic failure");
        var genericResult = invoke("runtimeChatException", new Class[] { Throwable.class, String.class },
                genericEx, "fallback message");
        assertThat(genericResult).isInstanceOf(RuntimeChatException.class)
                .satisfies(e -> assertThat(((RuntimeChatException) e).getStatusCode())
                        .isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode()),
                        e -> assertThat(((RuntimeChatException) e).getMessage()).isEqualTo("generic failure"));

        var nullMsgEx = new RuntimeException();
        var nullMsgResult = invoke("runtimeChatException", new Class[] { Throwable.class, String.class },
                nullMsgEx, "fallback message");
        assertThat(nullMsgResult).isInstanceOf(RuntimeChatException.class)
                .satisfies(e -> assertThat(((RuntimeChatException) e).getMessage()).isEqualTo("fallback message"));
    }

    @Test
    void runtimeTimeoutSeconds_returnsConfiguredOrDefault() throws Exception {
        assertThat(invoke("runtimeTimeoutSeconds", new Class[] {})).isEqualTo(120L);

        Field field = RuntimeChatService.class.getDeclaredField("runtimeTimeout");
        field.setAccessible(true);
        field.setLong(service, 300L);
        assertThat(invoke("runtimeTimeoutSeconds", new Class[] {})).isEqualTo(300L);
    }

    @Test
    void invokeSingleAgent_returnsResultString() throws Exception {
        RuntimeAgent agent = new RuntimeAgent("test", "desc",
                new StaticUntypedAgent("hello"), null);
        String result = (String) invoke("invokeSingleAgent",
                new Class[] { RuntimeAgent.class, ChatRequestDTO.class },
                agent, chatRequest("hi"));
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void invokeSingleAgent_returnsEmptyStringForNullResult() throws Exception {
        RuntimeAgent agent = new RuntimeAgent("test", "desc",
                new StaticUntypedAgent(null), null);
        String result = (String) invoke("invokeSingleAgent",
                new Class[] { RuntimeAgent.class, ChatRequestDTO.class },
                agent, chatRequest("hi"));
        assertThat(result).isEmpty();
    }

    @Test
    void executeGroups_returnsEmptyWhenNoGroups() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        agent.setGroups(List.of());
        assertThat((String) invoke("executeGroups",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                agent, chatRequest("hi"), Map.of())).isEmpty();
    }

    @Test
    void executeGroups_returnsEmptyWhenGroupHasNoName() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName(null);
        agent.setGroups(List.of(group));
        assertThat((String) invoke("executeGroups",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                agent, chatRequest("hi"), Map.of())).isEmpty();
    }

    @Test
    void executeGroups_filtersNullGroupInList() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        AgentGroupSnapshotDTO validGroup = new AgentGroupSnapshotDTO();
        validGroup.setName("valid-group");
        validGroup.setOrchestrationMode("LEAD_DELEGATES");
        validGroup.setAgents(List.of());
        agent.setGroups(Arrays.asList(null, validGroup, null));
        assertThat((String) invoke("executeGroups",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                agent, chatRequest("hi"), Map.of())).isEmpty();
    }

    @Test
    void invokeRootResponse_fallsBackToSingleAgentWhenGroupsEmpty() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        agent.setGroups(List.of());

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any())).thenReturn(chatModel);
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("invokeRootResponse",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    agent, chatRequest("hello"), Map.of());
            assertThat(result).isEmpty();
        }
    }

    @Test
    void isUserConfirmationPresent_recognizesPolishConfirmation() throws Exception {
        ChatRequestDTO request = chatRequest("tak");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("ASSISTANT", "Czy chcesz kontynuować?"), message("USER", "tak, proszę")));
        request.setConversation(conv);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, request, new StaticChatModel("YES")))
                .isEqualTo(true);
    }

    @Test
    void isUserConfirmationPresent_recognizesGermanConfirmation() throws Exception {
        ChatRequestDTO request = chatRequest("ja");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("ASSISTANT", "Möchten Sie fortfahren?"), message("USER", "ja, mach weiter")));
        request.setConversation(conv);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, request, new StaticChatModel("YES")))
                .isEqualTo(true);
    }

    @Test
    void isUserConfirmationPresent_returnsFalseWhenLlmThrowsException() throws Exception {
        ChatRequestDTO request = chatRequest("yes");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "yes")));
        request.setConversation(conv);
        ChatModel throwingModel = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                throw new RuntimeException("LLM unavailable");
            }
        };
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, request, throwingModel))
                .isEqualTo(false);
    }

    @Test
    void isUserConfirmationPresent_returnsFalseWhenLlmReturnsNullResponse() throws Exception {
        ChatRequestDTO request = chatRequest("yes");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "yes")));
        request.setConversation(conv);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, request, new NullChatModel()))
                .isEqualTo(false);
    }

    @Test
    void isUserConfirmationPresent_returnsFalseWhenLlmReturnsNullText() throws Exception {
        ChatRequestDTO request = chatRequest("yes");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "yes")));
        request.setConversation(conv);
        ChatModel nullTextModel = new ChatModel() {
            @Override
            public String chat(String message) {
                return null;
            }

            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
            }
        };
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, request, nullTextModel))
                .isEqualTo(false);
    }

    @Test
    void extractLastUserMessage_returnsLastUserMessage() throws Exception {
        ChatRequestDTO request = new ChatRequestDTO();
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "hello"), message("ASSISTANT", "hi"), message("USER", "yes")));
        request.setConversation(conv);
        assertThat(invoke("extractLastUserMessage", new Class[] { ChatRequestDTO.class }, request))
                .isEqualTo("yes");
    }

    @Test
    void extractLastUserMessage_returnsNullWhenNoUserMessage() throws Exception {
        ChatRequestDTO request = new ChatRequestDTO();
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("ASSISTANT", "hello")));
        request.setConversation(conv);
        assertThat(invoke("extractLastUserMessage", new Class[] { ChatRequestDTO.class }, request))
                .isNull();
    }

    @Test
    void extractLastUserMessage_fallsBackToHistoryWhenChatMessageIsNotUserType() throws Exception {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setChatMessage(message("ASSISTANT", "current assistant msg"));
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "from history")));
        request.setConversation(conv);
        assertThat(invoke("extractLastUserMessage", new Class[] { ChatRequestDTO.class }, request))
                .isEqualTo("from history");
    }

    @Test
    void extractLastUserMessage_returnsNullWhenHistoryIsNull() throws Exception {
        ChatRequestDTO request = new ChatRequestDTO();
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(null);
        request.setConversation(conv);
        assertThat(invoke("extractLastUserMessage", new Class[] { ChatRequestDTO.class }, request))
                .isNull();
    }

    @Test
    void extractLastUserMessage_returnsNullForNullRequest() throws Exception {
        assertThat(invoke("extractLastUserMessage", new Class[] { ChatRequestDTO.class }, (Object) null))
                .isNull();
    }

    @Test
    void toDelegateToolExecutors_returnsEmptyMapForNullInput() throws Exception {
        @SuppressWarnings("unchecked")
        Map<ToolSpecification, ToolExecutor> result = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toDelegateToolExecutors", new Class[] { List.class }, (Object) null);
        assertThat(result).isEmpty();
    }

    // ---- chat() and invoke() public entry points ----

    @Test
    void chat_throwsForNullRequest() {
        assertThatThrownBy(() -> service.chat(null))
                .isInstanceOf(RuntimeChatException.class)
                .hasMessageContaining("Root agent snapshot is required");
    }

    @Test
    void chat_throwsForNullRootAgent() {
        RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
        assertThatThrownBy(() -> service.chat(request))
                .isInstanceOf(RuntimeChatException.class)
                .hasMessageContaining("Root agent snapshot is required");
    }

    @Test
    void chat_invokesSuccessfullyWithSyncExecutor() throws Exception {
        setupChatMocks();
        // Use a synchronous executor so chat() runs invoke() in-thread
        service.managedExecutor = mock(ManagedExecutor.class);
        doAnswer(RuntimeChatServiceReflectionTest::runSynchronously)
                .when(service.managedExecutor).execute(ArgumentMatchers.any());

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
            request.setRootAgent(agent("root", "root desc"));
            request.setChatRequest(chatRequest("hello"));
            RuntimeChatResponseDTO response = service.chat(request);
            assertThat(response).isNotNull();
            assertThat(response.getMessage()).isNotNull();
        }
    }

    @Test
    void chat_throwsRuntimeChatExceptionWhenInnerCallFails() {
        setupChatMocks();
        service.managedExecutor = mock(ManagedExecutor.class);
        doAnswer(RuntimeChatServiceReflectionTest::runSynchronously)
                .when(service.managedExecutor).execute(ArgumentMatchers.any());

        // Without mocking AiServices, buildLocalAgent throws → invoke() catches
        // and wraps in RuntimeChatException → chat() catches and re-throws
        RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        rootAgent.setA2aEnabled(true);
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setOrchestrationMode("LEAD_DELEGATES");
        group.setAgents(List.of());
        rootAgent.setGroups(List.of(group));
        request.setRootAgent(rootAgent);
        request.setChatRequest(chatRequest("hello"));

        assertThatThrownBy(() -> service.chat(request))
                .isInstanceOfAny(RuntimeChatException.class, CompletionException.class,
                        ExecutionException.class);
    }

    @Test
    void invoke_returnsResponseWithMessage() throws Exception {
        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
            request.setRootAgent(agent("root", "root desc"));
            request.setChatRequest(chatRequest("hello"));
            RuntimeChatResponseDTO response = (RuntimeChatResponseDTO) invoke("invoke",
                    new Class[] { RuntimeChatRequestDTO.class, Map.class }, request, Map.of());
            assertThat(response).isNotNull();
            assertThat(response.getMessage()).isNotNull();
        }
    }

    @Test
    void invoke_throwsRuntimeChatExceptionWhenInnerCallFails() {
        setupChatMocks();

        // Without mocking AiServices, buildLocalAgent will throw → invoke() catches
        // and wraps in RuntimeChatException. Reflection wraps it in InvocationTargetException.
        RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        rootAgent.setA2aEnabled(true);
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setOrchestrationMode("LEAD_DELEGATES");
        group.setAgents(List.of());
        rootAgent.setGroups(List.of(group));
        request.setRootAgent(rootAgent);
        request.setChatRequest(chatRequest("hello"));

        assertThatThrownBy(() -> invoke("invoke",
                new Class[] { RuntimeChatRequestDTO.class, Map.class }, request, Map.of()))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(RuntimeChatException.class);
    }

    @Test
    void invoke_returnsEmptyMessageWhenRootResponseIsNull() throws Exception {
        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            // invokeRootResponse returns "" from empty groups → invoke returns response with ""
            RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
            AgentSnapshotDTO rootAgent = agent("root", "root desc");
            rootAgent.setA2aEnabled(true);
            rootAgent.setGroups(List.of()); // empty groups → falls back to single agent
            request.setRootAgent(rootAgent);
            request.setChatRequest(chatRequest("hello"));
            RuntimeChatResponseDTO response = (RuntimeChatResponseDTO) invoke("invoke",
                    new Class[] { RuntimeChatRequestDTO.class, Map.class }, request, Map.of());
            assertThat(response).isNotNull();
            assertThat(response.getMessage()).isNotNull();
        }
    }

    private void setupChatMocks() {
        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        service.managedExecutor = null;
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);
    }

    // ---- runtimeExecutor() ----

    @Test
    void runtimeExecutor_usesManagedExecutorWhenNotNull() throws Exception {
        service.managedExecutor = mock(ManagedExecutor.class);
        Object executor = invoke("runtimeExecutor", new Class[] {}, new Object[] {});
        assertThat(executor).isSameAs(service.managedExecutor);
    }

    @Test
    void runtimeExecutor_fallsBackToDelayedExecutorWhenNull() throws Exception {
        service.managedExecutor = null;
        Object executor = invoke("runtimeExecutor", new Class[] {}, new Object[] {});
        assertThat(executor).isNotNull();
    }

    // ---- runtimeName / runtimeDescription null branches ----

    @Test
    void runtimeNameAndDescription_returnDefaultsForNullAgent() throws Exception {
        assertThat((String) invoke("runtimeName", new Class[] { AgentSnapshotDTO.class }, (AgentSnapshotDTO) null))
                .isEqualTo("local-agent");
        assertThat((String) invoke("runtimeDescription", new Class[] { AgentSnapshotDTO.class }, (AgentSnapshotDTO) null))
                .isEqualTo("Configured local agent");
        assertThat((String) invoke("runtimeName", new Class[] { ExternalAgentSnapshotDTO.class },
                (ExternalAgentSnapshotDTO) null)).isEqualTo("remote-agent");
        assertThat((String) invoke("runtimeDescription", new Class[] { ExternalAgentSnapshotDTO.class },
                (ExternalAgentSnapshotDTO) null)).isEqualTo("Discovered remote A2A agent");
    }

    // ---- isCallableExternalAgent branches ----

    @Test
    void isCallableExternalAgent_coversNullDisabledAndBlankUrl() throws Exception {
        assertThat(invoke("isCallableExternalAgent",
                new Class[] { ExternalAgentSnapshotDTO.class }, (Object) null)).isEqualTo(false);
        assertThat(invoke("isCallableExternalAgent",
                new Class[] { ExternalAgentSnapshotDTO.class },
                externalAgent("ext", "desc", false, "http://url", null))).isEqualTo(false);
        assertThat(invoke("isCallableExternalAgent",
                new Class[] { ExternalAgentSnapshotDTO.class },
                externalAgent("ext", "desc", true, "  ", null))).isEqualTo(false);
        assertThat(invoke("isCallableExternalAgent",
                new Class[] { ExternalAgentSnapshotDTO.class },
                externalAgent("ext", "desc", true, "http://url", null))).isEqualTo(true);
    }

    // ---- extractUserMessage null branches ----

    @Test
    void extractUserMessage_coversNullRequestAndNullChatMessage() throws Exception {
        assertThat((String) invoke("extractUserMessage", new Class[] { ChatRequestDTO.class }, (Object) null))
                .isEmpty();
        ChatRequestDTO req = new ChatRequestDTO();
        req.setChatMessage(null);
        assertThat((String) invoke("extractUserMessage", new Class[] { ChatRequestDTO.class }, req)).isEmpty();
    }

    // ---- inputMessage branches ----

    @Test
    void inputMessage_coversCharSequenceMapAndFallback() throws Exception {
        assertThat((String) invoke("inputMessage", new Class[] { Object.class, String.class },
                "hello", "fallback")).isEqualTo("hello");
        assertThat((String) invoke("inputMessage", new Class[] { Object.class, String.class },
                "  ", "fallback")).isEqualTo("fallback");
        assertThat((String) invoke("inputMessage", new Class[] { Object.class, String.class },
                Map.of("message", "from-map"), "fallback")).isEqualTo("from-map");
        assertThat((String) invoke("inputMessage", new Class[] { Object.class, String.class },
                Map.of("message", "  "), "fallback")).isEqualTo("fallback");
        assertThat((String) invoke("inputMessage", new Class[] { Object.class, String.class },
                Map.of("other", "x"), "fallback")).isEqualTo("fallback");
        assertThat((String) invoke("inputMessage", new Class[] { Object.class, String.class },
                42, "fallback")).isEqualTo("fallback");
    }

    // ---- userMessage with conversation history ----

    @Test
    void userMessage_includesConversationHistoryWhenPresent() throws Exception {
        ChatRequestDTO request = chatRequest("current question");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "previous question"), message("ASSISTANT", "previous answer")));
        request.setConversation(conv);
        String result = (String) invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class },
                request, "current question");
        assertThat(result).contains("Conversation history:")
                .contains("previous question")
                .contains("previous answer")
                .contains("Current user message:")
                .contains("current question");
    }

    @Test
    void userMessage_usesExtractUserMessageWhenCurrentMessageBlank() throws Exception {
        ChatRequestDTO request = chatRequest("extracted message");
        String result = (String) invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class },
                request, "  ");
        assertThat(result).contains("extracted message");
    }

    // ---- delegationPolicy with blank description ----

    @Test
    void delegationPolicy_omitsDescriptionWhenBlank() throws Exception {
        StaticUntypedAgent innerAgent = new StaticUntypedAgent("x");
        RuntimeAgentDelegate delegate = new RuntimeAgentDelegate("Agent", "  ",
                () -> new RuntimeAgent("a", "d", innerAgent, null));
        String result = (String) invoke("delegationPolicy", new Class[] { List.class }, List.of(delegate));
        assertThat(result).contains("Agent").doesNotContain(":  ");
    }

    // ---- extractTextToolCalls edge cases ----

    @Test
    void extractTextToolCalls_coversBlankTextAndNullToolNames() throws Exception {
        assertThat(invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class }, "  ", Set.of("tool")))
                .isEqualTo(List.of());
        assertThat(invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class }, "text", null))
                .isEqualTo(List.of());
        assertThat(invoke("extractTextToolCalls",
                new Class[] { String.class, Set.class }, "text", Set.of()))
                .isEqualTo(List.of());
    }

    @Test
    void extractTextToolCalls_coversArrayJsonAndNonObjectItem() throws Exception {
        // Array with valid tool call
        List<ToolExecutionRequest> result1 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "[{\"name\":\"search\",\"arguments\":{\"q\":\"test\"}}]", Set.of("search"));
        assertThat(result1).hasSize(1);
        assertThat(result1.get(0).name()).isEqualTo("search");

        // Array with non-object item (null) — should be skipped
        List<ToolExecutionRequest> result2 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "[null]", Set.of("search"));
        assertThat(result2).isEmpty();
    }

    @Test
    void addTextToolCall_coversToolAndToolNameFields() throws Exception {
        // "tool" field
        List<ToolExecutionRequest> result1 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"tool\":\"my_tool\",\"arguments\":{}}", Set.of("my_tool"));
        assertThat(result1).hasSize(1);

        // "tool_name" field
        List<ToolExecutionRequest> result2 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"tool_name\":\"my_tool\",\"arguments\":{}}", Set.of("my_tool"));
        assertThat(result2).hasSize(1);
    }

    // ---- toolArguments edge cases ----

    @Test
    void toolArguments_coversNullArgsAndTextualArgs() throws Exception {
        // "args" field fallback
        List<ToolExecutionRequest> result1 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\",\"args\":{\"key\":\"val\"}}", Set.of("tool"));
        assertThat(result1).hasSize(1);
        assertThat(result1.get(0).arguments()).contains("key");

        // textual arguments
        List<ToolExecutionRequest> result2 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\",\"arguments\":\"raw text\"}", Set.of("tool"));
        assertThat(result2).hasSize(1);
        assertThat(result2.get(0).arguments()).isEqualTo("raw text");

        // null arguments
        List<ToolExecutionRequest> result3 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\",\"arguments\":null}", Set.of("tool"));
        assertThat(result3).hasSize(1);
        assertThat(result3.get(0).arguments()).isEqualTo("{}");

        // blank textual arguments
        List<ToolExecutionRequest> result4 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\",\"arguments\":\"  \"}", Set.of("tool"));
        assertThat(result4).hasSize(1);
        assertThat(result4.get(0).arguments()).isEqualTo("{}");

        // no arguments or args field at all — both null
        List<ToolExecutionRequest> result5 = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\"}", Set.of("tool"));
        assertThat(result5).hasSize(1);
        assertThat(result5.get(0).arguments()).isEqualTo("{}");
    }

    // ---- textField non-textual branch ----

    @Test
    void textField_returnsNullForNonTextualValue() throws Exception {
        // name is a number, not textual — should fall through to other fields, then not match
        List<ToolExecutionRequest> result = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":123}", Set.of("123"));
        assertThat(result).isEmpty();
    }

    // ---- extractToolMessage null message branch ----

    @Test
    void extractToolMessage_returnsArgumentsWhenMessageIsNull() throws Exception {
        assertThat((String) invoke("extractToolMessage", new Class[] { String.class },
                "{\"message\":null}")).isEqualTo("{\"message\":null}");
    }

    @Test
    void extractToolMessage_returnsArgumentsWhenNoMessageField() throws Exception {
        assertThat((String) invoke("extractToolMessage", new Class[] { String.class },
                "{\"other\":\"value\"}")).isEqualTo("{\"other\":\"value\"}");
    }

    // ---- delegateToolBaseName with blank name ----

    @Test
    void delegateToolBaseName_returnsDefaultForBlankName() throws Exception {
        assertThat((String) invoke("delegateToolBaseName", new Class[] { String.class }, "  "))
                .isEqualTo("delegate_agent");
        assertThat((String) invoke("delegateToolBaseName", new Class[] { String.class }, (Object) null))
                .isEqualTo("delegate_agent");
    }

    // ---- rootCause with null ----

    // ---- runtimeChatException edge cases ----

    @Test
    void runtimeChatException_returnsSameInstanceForRuntimeChatException() throws Exception {
        RuntimeChatException original = new RuntimeChatException("CODE", "Type", "msg", Response.Status.BAD_REQUEST);
        RuntimeChatException result = (RuntimeChatException) invoke("runtimeChatException",
                new Class[] { Throwable.class, String.class }, original, "fallback");
        assertThat(result).isSameAs(original);
    }

    @Test
    void runtimeChatException_coversNullCause() throws Exception {
        RuntimeChatException result = (RuntimeChatException) invoke("runtimeChatException",
                new Class[] { Throwable.class, String.class }, (Object) null, "fallback message");
        assertThat(result).isNotNull();
        assertThat(result.getDetail()).isEqualTo("fallback message");
    }

    // ---- executeGroup default mode (unknown orchestration) ----

    @Test
    void executeGroup_unknownMode_fallsBackToLeadDelegates() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-unknown");
        group.setOrchestrationMode("UNKNOWN_MODE");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("executeGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isNotNull();
        }
    }

    // ---- executeGroup with empty delegates (SEQUENTIAL/PARALLEL) ----

    @Test
    void executeGroup_sequentialMode_returnsEmptyWhenNoDelegates() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq-empty");
        group.setOrchestrationMode("SEQUENTIAL");
        group.setAgents(List.of()); // no agents
        rootAgent.setGroups(List.of(group));

        String result = (String) invoke("executeGroup",
                new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                rootAgent, group, chatRequest("hello"), Map.of());
        assertThat(result).isEmpty();
    }

    // ---- executeGroup default mode with no delegates ----

    @Test
    void executeGroup_leadDelegatesMode_returnsEmptyWhenNoDelegates() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-empty");
        group.setOrchestrationMode("LEAD_DELEGATES");
        group.setAgents(List.of());
        group.setExternalAgents(List.of());

        String result = (String) invoke("executeGroup",
                new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                rootAgent, group, chatRequest("hello"), Map.of());
        assertThat(result).isEmpty();
    }

    // ---- supervisorRequest with blank description and routing instructions ----

    @Test
    void supervisorRequest_omitsBlankDescriptionAndRoutingInstructions() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        // description and routingInstructions are null/blank
        String result = (String) invoke("supervisorRequest",
                new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, List.class },
                rootAgent, group, chatRequest("hello"),
                List.of(new RuntimeAgent("Peer", "Peer specialist", new StaticUntypedAgent("x"), null)));
        assertThat(result)
                .contains("hello").contains("root").contains("Peer specialist")
                .doesNotContain("Group description:")
                .doesNotContain("Group routing instructions:");
    }

    @Test
    void supervisorRequest_omitsAgentDescriptionWhenBlank() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        String result = (String) invoke("supervisorRequest",
                new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, List.class },
                rootAgent, group, chatRequest("hello"),
                List.of(new RuntimeAgent("NoDesc", null, new StaticUntypedAgent("x"), null)));
        assertThat(result)
                .contains("- NoDesc")
                .doesNotContain("NoDesc:");
    }

    // ---- invokeRootResponse falls back to single agent when group response is blank ----

    @Test
    void invokeRootResponse_fallsBackToSingleAgentWhenGroupResponseIsBlank() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-empty-delegates");
        group.setOrchestrationMode("LEAD_DELEGATES");
        group.setAgents(List.of()); // empty → executeGroup returns ""
        agent.setGroups(List.of(group));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("invokeRootResponse",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    agent, chatRequest("hello"), Map.of());
            assertThat(result).isNotNull();
        }
    }

    // ---- delegatesForGroup with null group and external agents ----

    @Test
    void delegatesForGroup_returnsEmptyForNullGroup() throws Exception {
        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> result = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                null, chatRequest("hi"), Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void delegatesForGroup_skipsNullInternalAgentsAndNonCallableExternalAgents() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setAgents(Arrays.asList(null, agent("valid-agent", "desc")));
        group.setExternalAgents(List.of(
                externalAgent("disabled-ext", "desc", false, "http://url", null),
                externalAgent("valid-ext", "desc", true, "http://url", null)));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> result = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                group, chatRequest("hi"), Map.of());
        assertThat(result)
                .hasSize(2)
                .extracting(RuntimeAgentDelegate::name)
                .contains("valid-agent", "valid-ext");
    }

    @Test
    void delegatesForGroup_onlyInternalAgents_coversNullExternalAgents() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setAgents(List.of(agent("internal-agent", "desc")));
        group.setExternalAgents(null); // explicit null

        setupChatMocks();

        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> result = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                group, chatRequest("hi"), Map.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("internal-agent");
    }

    @Test
    void delegatesForGroup_onlyExternalAgents_coversNullAgents() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setAgents(null); // explicit null
        group.setExternalAgents(List.of(externalAgent("valid-ext", "desc", true, "http://url", null)));

        setupChatMocks();

        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> result = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                group, chatRequest("hi"), Map.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("valid-ext");
    }

    // ---- invokeDelegate with throwing delegate (covers catch block) ----

    @Test
    void invokeDelegate_returnsErrorMessageWhenDelegateThrows() throws Exception {
        ThrowingUntypedAgent throwingAgent = new ThrowingUntypedAgent();
        RuntimeAgentDelegate throwingDelegate = new RuntimeAgentDelegate("Bad Agent", "",
                () -> new RuntimeAgent("bad", "desc", throwingAgent, null));
        String result = (String) invoke("invokeDelegate",
                new Class[] { RuntimeAgentDelegate.class, String.class }, throwingDelegate, "hello");
        assertThat(result).contains("could not complete the delegated request");
    }

    // ---- outputFromScope with null invocations and blank output ----

    @Test
    void outputFromScope_coversNullInvocationsAndBlankOutput() throws Exception {
        AgenticScope nullInvocations = mock(AgenticScope.class);
        when(nullInvocations.agentInvocations()).thenReturn(null);
        assertThat((String) invoke("outputFromScope", new Class[] { AgenticScope.class }, (Object) null))
                .isEmpty();
        assertThat((String) invoke("outputFromScope", new Class[] { AgenticScope.class }, nullInvocations))
                .isEmpty();

        AgentInvocation blankInvocation = mock(AgentInvocation.class);
        when(blankInvocation.output()).thenReturn("  ");
        AgenticScope scopeWithBlank = mock(AgenticScope.class);
        when(scopeWithBlank.agentInvocations()).thenReturn(List.of(blankInvocation));
        assertThat((String) invoke("outputFromScope", new Class[] { AgenticScope.class }, scopeWithBlank))
                .isEmpty();
    }

    // ---- maxSequentialToolInvocations edge cases ----

    @Test
    void maxSequentialToolInvocations_returns1WhenConfiguredLessThan1() throws Exception {
        service.dispatchConfig = dispatchConfig(0L);
        assertThat(invoke("maxSequentialToolInvocations", new Class[] {}, new Object[] {})).isEqualTo(1);
    }

    @Test
    void maxSequentialToolInvocations_capsAtIntegerMax() throws Exception {
        DispatchConfig dc = mock(DispatchConfig.class);
        DispatchConfig.ToolConfig tc = mock(DispatchConfig.ToolConfig.class);
        when(tc.maxIterations()).thenReturn((long) Integer.MAX_VALUE + 1L);
        when(dc.toolConfig()).thenReturn(tc);
        service.dispatchConfig = dc;
        assertThat(invoke("maxSequentialToolInvocations", new Class[] {}, new Object[] {})).isEqualTo(Integer.MAX_VALUE);
    }

    // ---- runtimeTimeoutSeconds with zero ----

    @Test
    void runtimeTimeoutSeconds_returnsDefaultWhenZeroOrNegative() throws Exception {
        service.runtimeTimeout = 0;
        assertThat(invoke("runtimeTimeoutSeconds", new Class[] {}, new Object[] {})).isEqualTo(120L);
        service.runtimeTimeout = -5;
        assertThat(invoke("runtimeTimeoutSeconds", new Class[] {}, new Object[] {})).isEqualTo(120L);
    }

    // ---- invokeRootResponse: a2aEnabled=false skips groups ----

    @Test
    void invokeRootResponse_skipsGroupsWhenA2aDisabled() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(false);
        agent.setGroups(List.of()); // has groups but a2a disabled

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("invokeRootResponse",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    agent, chatRequest("hello"), Map.of());
            assertThat(result).isNotNull();
        }
    }

    // ---- invokeRootResponse: a2aEnabled=true but groups=null ----

    @Test
    void invokeRootResponse_skipsGroupsWhenGroupsNull() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        agent.setGroups(null);

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("invokeRootResponse",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    agent, chatRequest("hello"), Map.of());
            assertThat(result).isNotNull();
        }
    }

    // ---- executeSupervisorRoutedGroup: null result from supervisor ----

    @Test
    void executeSupervisorRoutedGroup_returnsEmptyWhenSupervisorReturnsNull() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-sup-null");
        group.setOrchestrationMode("SUPERVISOR_ROUTED");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            SupervisorAgent mockSupervisor = mock(
                    SupervisorAgent.class);
            when(mockSupervisor.invokeWithAgenticScope(ArgumentMatchers.anyString()))
                    .thenReturn(null);

            SupervisorAgentService<SupervisorAgent> supBuilder = mockSupervisorBuilder(mockSupervisor);
            agenticStatic.when(AgenticServices::supervisorBuilder).thenReturn(supBuilder);

            String result = (String) invoke("executeSupervisorRoutedGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isEmpty();

            // Also cover the case where supervisor returns ResultWithAgenticScope with null result
            when(mockSupervisor.invokeWithAgenticScope(ArgumentMatchers.anyString()))
                    .thenReturn(new ResultWithAgenticScope<>(null, null));
            String resultWithNullScope = (String) invoke("executeSupervisorRoutedGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(resultWithNullScope).isEmpty();
        }
    }

    // ---- executeSequentialGroup/executeParallelGroup: null result ----

    @Test
    void executeSequentialGroup_returnsEmptyForNullResult() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq");
        // Use a workflow that returns null
        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent nullWorkflow = new NullResultUntypedAgent();
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(nullWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            String result = (String) invoke("executeSequentialGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, List.class, ChatRequestDTO.class },
                    group, List.of(), chatRequest("hello"));
            assertThat(result).isEmpty();
        }
    }

    @Test
    void executeParallelGroup_returnsEmptyForNullResult() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-par");
        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent nullWorkflow = new NullResultUntypedAgent();
            ParallelAgentService<UntypedAgent> parBuilder = mockParallelBuilder(nullWorkflow);
            agenticStatic.when(AgenticServices::parallelBuilder).thenReturn(parBuilder);

            String result = (String) invoke("executeParallelGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, List.class, ChatRequestDTO.class },
                    group, List.of(), chatRequest("hello"));
            assertThat(result).isEmpty();
        }
    }

    // ---- userMessage: null conversation / null history ----

    @Test
    void userMessage_omitsHistoryWhenConversationNull() throws Exception {
        ChatRequestDTO request = chatRequest("hello");
        // conversation is null
        String result = (String) invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class },
                request, "hello");
        assertThat(result).contains("Current user message:").contains("hello")
                .doesNotContain("Conversation history:");
    }

    @Test
    void userMessage_omitsHistoryWhenHistoryEmpty() throws Exception {
        ChatRequestDTO request = chatRequest("hello");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of());
        request.setConversation(conv);
        String result = (String) invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class },
                request, "hello");
        assertThat(result).doesNotContain("Conversation history:");
    }

    // ---- extractUserMessage: null message ----

    @Test
    void extractUserMessage_returnsEmptyWhenMessageIsNull() throws Exception {
        ChatRequestDTO request = new ChatRequestDTO();
        ChatMessageDTO msg = new ChatMessageDTO();
        msg.setMessage(null);
        request.setChatMessage(msg);
        assertThat((String) invoke("extractUserMessage", new Class[] { ChatRequestDTO.class }, request))
                .isEmpty();
    }

    // ---- extractToolMessage: blank arguments ----

    @Test
    void extractToolMessage_returnsEmptyForBlankArguments() throws Exception {
        assertThat((String) invoke("extractToolMessage", new Class[] { String.class }, "  "))
                .isEmpty();
        assertThat((String) invoke("extractToolMessage", new Class[] { String.class }, (Object) null))
                .isEmpty();
    }

    // ---- toolArguments: objectMapper writeValueAsString exception (covered by new test below) ----

    // ---- addTextToolCall: name not in availableToolNames ----

    @Test
    void addTextToolCall_skipsWhenNameNotInAvailableToolNames() throws Exception {
        List<ToolExecutionRequest> result = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"unknown_tool\",\"arguments\":{}}", Set.of("other_tool"));
        assertThat(result).isEmpty();
    }

    // ---- rootCause: self-referential cause ----

    @Test
    void rootCause_handlesSelfReferentialCause() throws Exception {
        // Use a mock to simulate self-referential cause (can't do with real Throwable)
        Throwable selfRef = mock(Throwable.class);
        when(selfRef.getCause()).thenReturn(selfRef);
        Throwable result = (Throwable) invoke("rootCause", new Class[] { Throwable.class }, selfRef);
        assertThat(result).isSameAs(selfRef);
    }

    // ---- lambda$outputFromScope$0: null output ----

    @Test
    void outputFromScope_filtersNullOutput() throws Exception {
        AgentInvocation nullOutput = mock(AgentInvocation.class);
        when(nullOutput.output()).thenReturn(null);
        AgentInvocation validOutput = mock(AgentInvocation.class);
        when(validOutput.output()).thenReturn("valid");
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.agentInvocations()).thenReturn(List.of(nullOutput, validOutput));
        String result = (String) invoke("outputFromScope", new Class[] { AgenticScope.class }, scope);
        assertThat(result).isEqualTo("valid");
    }

    // ---- extractTextToolCalls: invalid JSON (readTree exception) ----

    @Test
    void extractTextToolCalls_skipsInvalidJson() throws Exception {
        // Balanced braces but invalid JSON content — readTree will throw, loop continues
        List<ToolExecutionRequest> result = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\ninvalid\n}", Set.of("tool"));
        assertThat(result).isEmpty();
    }

    // ---- delegatesForGroup: external agent with null in list ----

    @Test
    void delegatesForGroup_skipsNullExternalAgent() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setExternalAgents(Arrays.asList(null, externalAgent("valid-ext", "desc", true, "http://url", null)));

        setupChatMocks();

        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> result = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                group, chatRequest("hi"), Map.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("valid-ext");
    }

    // ---- lazySupervisorCandidate: null group ----

    @Test
    void lazySupervisorCandidate_handlesNullGroup() throws Exception {
        StaticUntypedAgent innerAgent = new StaticUntypedAgent("x");
        Object result = invoke("lazySupervisorCandidate",
                new Class[] { String.class, String.class, Supplier.class,
                        AgentGroupSnapshotDTO.class, String.class },
                "name", "desc",
                (Supplier<RuntimeAgent>) () -> new RuntimeAgent("a", "d",
                        innerAgent, null),
                null, "fallback");
        assertThat(result).isNotNull();
    }

    // ---- invokeDelegate: throwing delegate covers catch block fully ----

    // ---- supervisorRequest: with description and routing instructions ----

    @Test
    void supervisorRequest_includesDescriptionAndRoutingInstructionsWhenPresent() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setDescription("  desc  ");
        group.setRoutingInstructions("  route  ");
        String result = (String) invoke("supervisorRequest",
                new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, List.class },
                rootAgent, group, chatRequest("hello"),
                List.of(new RuntimeAgent("Peer", "  specialist  ", new StaticUntypedAgent("x"), null)));
        assertThat(result).contains("Group description: desc")
                .contains("Group routing instructions: route")
                .contains("specialist");
    }

    // ---- invokeRootResponse: a2aEnabled=null ----

    @Test
    void invokeRootResponse_skipsGroupsWhenA2aEnabledIsNull() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(null);
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setOrchestrationMode("LEAD_DELEGATES");
        group.setAgents(List.of());
        agent.setGroups(List.of(group));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("invokeRootResponse",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    agent, chatRequest("hello"), Map.of());
            assertThat(result).isNotNull();
        }
    }

    // ---- userMessage: null history ----

    @Test
    void userMessage_omitsHistoryWhenHistoryIsNull() throws Exception {
        ChatRequestDTO request = chatRequest("hello");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(null);
        request.setConversation(conv);
        String result = (String) invoke("userMessage", new Class[] { ChatRequestDTO.class, String.class },
                request, "hello");
        assertThat(result).doesNotContain("Conversation history:");
    }

    // ---- executeSupervisorRoutedGroup: result with null result() ----

    // ---- delegatesForGroup: null agents and null externalAgents ----

    // ---- invokeDelegate: null result from invoker ----

    @Test
    void invokeDelegate_returnsEmptyForNullInvokerResult() throws Exception {
        NullResultUntypedAgent nullResultAgent = new NullResultUntypedAgent();
        RuntimeAgentDelegate delegate = new RuntimeAgentDelegate("Null Result", "desc",
                () -> new RuntimeAgent("null-result", "desc", nullResultAgent, null));
        String result = (String) invoke("invokeDelegate",
                new Class[] { RuntimeAgentDelegate.class, String.class }, delegate, "hello");
        assertThat(result).isEmpty();
    }

    // ---- extractTextToolCalls: readTree exception path ----

    // ---- toolArguments: non-object, non-textual arguments (e.g., numeric) ----

    @Test
    void toolArguments_serializesNumericArguments() throws Exception {
        List<ToolExecutionRequest> result = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\",\"arguments\":42}", Set.of("tool"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).arguments()).isEqualTo("42");
    }

    // ---- extractToolMessage: valid JSON with message ----

    @Test
    void extractToolMessage_returnsMessageFromValidJson() throws Exception {
        assertThat((String) invoke("extractToolMessage", new Class[] { String.class },
                "{\"message\":\"hello world\"}")).isEqualTo("hello world");
    }

    // ---- isUserConfirmationPresent: response starting with NO ----

    @Test
    void isUserConfirmationPresent_returnsFalseWhenResponseStartsWithNo() throws Exception {
        ChatRequestDTO request = chatRequest("no");
        ConversationDTO conv = new ConversationDTO();
        conv.setHistory(List.of(message("USER", "no")));
        request.setConversation(conv);
        assertThat(invoke("isUserConfirmationPresent",
                new Class[] { ChatRequestDTO.class, ChatModel.class }, request, new StaticChatModel("NO")))
                .isEqualTo(false);
    }

    @Test
    void systemMessage_withRuntimeSkills_appendsActivationPrompt() throws Exception {
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.runtimeSkillService.activationPrompt(ArgumentMatchers.any()))
                .thenReturn("ACTIVATE SKILLS NOW");
        Skills skills = Skills.from(
                DefaultSkill.builder().name("test-skill").description("test").content("content")
                        .build());
        String result = (String) invoke("systemMessage",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class,
                        Skills.class, boolean.class },
                agent("root", "root desc"), chatRequest("hi"), null, skills, false);
        assertThat(result).contains("ACTIVATE SKILLS NOW");
    }

    @Test
    void delegatesForGroup_coversInternalAndExternalAgents() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        ExternalAgentSnapshotDTO externalAgent = externalAgent("ext-agent", "ext desc", true, "http://discovery", null);
        group.setAgents(List.of(internalAgent));
        group.setExternalAgents(List.of(externalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        @SuppressWarnings("unchecked")
        List<RuntimeAgentDelegate> delegates = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                group, chatRequest("hi"), Map.of());
        assertThat(delegates)
                .hasSize(2)
                .extracting(RuntimeAgentDelegate::name)
                .contains("ext-agent", "internal-agent");
    }

    @Test
    void buildRemoteAgent_returnsNullForNonCallableAgent() throws Exception {
        ExternalAgentSnapshotDTO disabled = externalAgent("ext", "desc", false, "http://discovery", null);
        assertThat(invoke("buildRemoteAgent",
                new Class[] { ExternalAgentSnapshotDTO.class }, disabled)).isNull();
    }

    @Test
    void buildRemoteAgent_returnsNullWhenApiKeyPresent() throws Exception {
        ExternalAgentSnapshotDTO withKey = externalAgent("ext", "desc", true, "http://discovery", "secret-key");
        assertThat(invoke("buildRemoteAgent",
                new Class[] { ExternalAgentSnapshotDTO.class }, withKey)).isNull();
    }

    @Test
    void buildRemoteAgent_returnsNullWhenDiscoveryFails() throws Exception {
        ExternalAgentSnapshotDTO agent = externalAgent("ext", "desc", true, "http://discovery", null);
        when(service.externalAgentDiscoveryService.fetchAgentCard("http://discovery")).thenReturn(null);
        assertThat(invoke("buildRemoteAgent",
                new Class[] { ExternalAgentSnapshotDTO.class }, agent)).isNull();
    }

    @Test
    void buildRemoteAgent_buildsA2AAgentWhenDiscoverySucceeds() throws Exception {
        ExternalAgentSnapshotDTO agent = externalAgent("ext", "ext desc", true, "http://discovery", null);
        AgentCard card = new AgentCard("ext", "desc", "http://invoke-url");
        when(service.externalAgentDiscoveryService.fetchAgentCard("http://discovery")).thenReturn(card);

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            @SuppressWarnings("unchecked")
            A2AClientBuilder<Object> a2aBuilder = mock(
                    A2AClientBuilder.class);
            when(a2aBuilder.inputKeys(ArgumentMatchers.<String> any())).thenReturn(a2aBuilder);
            when(a2aBuilder.outputKey(ArgumentMatchers.anyString())).thenReturn(a2aBuilder);
            UntypedAgent mockAgent = new StaticUntypedAgent("a2a response");
            when(a2aBuilder.build()).thenReturn(mockAgent);
            agenticStatic.when(() -> AgenticServices.a2aBuilder("http://invoke-url"))
                    .thenReturn(a2aBuilder);

            Object result = invoke("buildRemoteAgent",
                    new Class[] { ExternalAgentSnapshotDTO.class }, agent);
            assertThat(result).isNotNull();
        }
    }

    @Test
    void buildLocalAgent_withRuntimeSkills_addsToolProvider() throws Exception {
        RuntimeChatService svc = new RuntimeChatService();
        svc.objectMapper = new ObjectMapper();
        svc.dispatchConfig = dispatchConfig(3L);
        svc.scaffoldPromptComposer = new ScaffoldPromptComposer();
        svc.externalAgentDiscoveryService = mock(ExternalAgentDiscoveryService.class);
        svc.chatModelFactory = mock(ChatModelFactory.class);
        svc.mcpService = mock(McpService.class);
        svc.runtimeSkillService = mock(RuntimeSkillService.class);

        ChatModel chatModel = mock(ChatModel.class);
        when(svc.chatModelFactory.createChatModel(ArgumentMatchers.any())).thenReturn(chatModel);
        when(svc.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());

        Skills skills = Skills.from(
                DefaultSkill.builder().name("test-skill").description("test").content("content")
                        .build());
        when(svc.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(skills);
        when(svc.runtimeSkillService.activationPrompt(ArgumentMatchers.any()))
                .thenReturn("ACTIVATE SKILLS");

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            @SuppressWarnings("unchecked")
            AiServices<Object> mockBuilder = setupMockLocalChatAgentWithBuilder(aiServicesStatic);
            when(mockBuilder.toolProvider(ArgumentMatchers.any())).thenReturn(mockBuilder);

            Object result = invoke(svc, "buildLocalAgent",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class, Map.class },
                    agent("test", "desc"), chatRequest("hello"), null, Map.of());
            assertThat(result).isNotNull();
            Mockito.verify(mockBuilder).toolProvider(ArgumentMatchers.any());
        }
    }

    @Test
    void executeGroup_leadDelegatesMode_invokesLocalAgent() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        group.setOrchestrationMode("LEAD_DELEGATES");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            String result = (String) invoke("executeGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isNotNull();
        }
    }

    @Test
    void executeGroup_sequentialMode_invokesSequenceWorkflow() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq");
        group.setOrchestrationMode("SEQUENTIAL");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            UntypedAgent mockWorkflow = new StaticUntypedAgent("seq result");
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            String result = (String) invoke("executeGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isEqualTo("seq result");
        }
    }

    @Test
    void executeGroup_parallelMode_invokesParallelWorkflow() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-par");
        group.setOrchestrationMode("PARALLEL");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            UntypedAgent mockWorkflow = new StaticUntypedAgent("par result");
            ParallelAgentService<UntypedAgent> parBuilder = mockParallelBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::parallelBuilder).thenReturn(parBuilder);

            String result = (String) invoke("executeGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isEqualTo("par result");
        }
    }

    @Test
    void executeSupervisorRoutedGroup_invokesSupervisor() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-sup");
        group.setOrchestrationMode("SUPERVISOR_ROUTED");
        group.setResponseStrategy("LAST");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            SupervisorAgent mockSupervisor = mock(
                    SupervisorAgent.class);
            ResultWithAgenticScope<String> supervisorResult = new ResultWithAgenticScope<>(null, "supervisor result");
            when(mockSupervisor.invokeWithAgenticScope(ArgumentMatchers.anyString()))
                    .thenReturn(supervisorResult);

            SupervisorAgentService<SupervisorAgent> supBuilder = mockSupervisorBuilder(mockSupervisor);
            agenticStatic.when(AgenticServices::supervisorBuilder).thenReturn(supBuilder);

            String result = (String) invoke("executeSupervisorRoutedGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isEqualTo("supervisor result");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeGroup_supervisorRoutedMode_invokesSupervisorRoutedGroup() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-sup-via-executeGroup");
        group.setOrchestrationMode("SUPERVISOR_ROUTED");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            SupervisorAgent mockSupervisor = mock(
                    SupervisorAgent.class);
            when(mockSupervisor.invokeWithAgenticScope(ArgumentMatchers.anyString()))
                    .thenReturn(new ResultWithAgenticScope<>(null, "supervisor via executeGroup"));

            SupervisorAgentService<SupervisorAgent> supBuilder = mockSupervisorBuilder(mockSupervisor);
            agenticStatic.when(AgenticServices::supervisorBuilder).thenReturn(supBuilder);

            String result = (String) invoke("executeGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isEqualTo("supervisor via executeGroup");
        }
    }

    @Test
    void invokeRootResponse_returnsGroupResponseWhenNotBlank() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq");
        group.setOrchestrationMode("SEQUENTIAL");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        agent.setGroups(List.of(group));
        group.setAgents(List.of(internalAgent));

        service.chatModelFactory = mock(ChatModelFactory.class);
        service.mcpService = mock(McpService.class);
        service.runtimeSkillService = mock(RuntimeSkillService.class);
        when(service.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(service.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(service.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            UntypedAgent mockWorkflow = new StaticUntypedAgent("group response");
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            String result = (String) invoke("invokeRootResponse",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    agent, chatRequest("hello"), Map.of());
            assertThat(result).isEqualTo("group response");
        }
    }

    // ---- Lambda coverage: capture and invoke callbacks passed to mocked builders ----

    @Test
    @SuppressWarnings("unchecked")
    void executeSequentialGroup_lambdaBeforeCall_invokesWriteStatesOnScope() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq-lambda");

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent mockWorkflow = new StaticUntypedAgent("seq result");
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            invoke("executeSequentialGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, List.class, ChatRequestDTO.class },
                    group, List.of(), chatRequest("hello"));

            ArgumentCaptor<Consumer<AgenticScope>> beforeCallCaptor = ArgumentCaptor
                    .forClass(Consumer.class);
            Mockito.verify(seqBuilder).beforeCall(beforeCallCaptor.capture());

            AgenticScope mockScope = mock(AgenticScope.class);
            beforeCallCaptor.getValue().accept(mockScope);
            Mockito.verify(mockScope).writeStates(ArgumentMatchers.any());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSequentialGroup_lambdaOutput_extractsOutputFromScope() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq-output-lambda");

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent mockWorkflow = new StaticUntypedAgent("seq result");
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            invoke("executeSequentialGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, List.class, ChatRequestDTO.class },
                    group, List.of(), chatRequest("hello"));

            ArgumentCaptor<Function<AgenticScope, Object>> outputCaptor = ArgumentCaptor
                    .forClass(Function.class);
            Mockito.verify(seqBuilder).output(outputCaptor.capture());

            // Invoke the captured output lambda with a mocked scope
            AgentInvocation first = mock(AgentInvocation.class);
            when(first.output()).thenReturn("first");
            AgentInvocation second = mock(AgentInvocation.class);
            when(second.output()).thenReturn("second");
            AgenticScope scope = mock(AgenticScope.class);
            when(scope.agentInvocations()).thenReturn(List.of(first, second));
            String output = (String) outputCaptor.getValue().apply(scope);
            assertThat(output).isEqualTo("first" + System.lineSeparator() + "second");

            // Test null scope
            assertThat((String) outputCaptor.getValue().apply(null)).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeParallelGroup_lambdaBeforeCall_invokesWriteStatesOnScope() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-par-lambda");

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent mockWorkflow = new StaticUntypedAgent("par result");
            ParallelAgentService<UntypedAgent> parBuilder = mockParallelBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::parallelBuilder).thenReturn(parBuilder);

            invoke("executeParallelGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, List.class, ChatRequestDTO.class },
                    group, List.of(), chatRequest("hello"));

            ArgumentCaptor<Consumer<AgenticScope>> beforeCallCaptor = ArgumentCaptor
                    .forClass(Consumer.class);
            Mockito.verify(parBuilder).beforeCall(beforeCallCaptor.capture());

            AgenticScope mockScope = mock(AgenticScope.class);
            beforeCallCaptor.getValue().accept(mockScope);
            Mockito.verify(mockScope).writeStates(ArgumentMatchers.any());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeParallelGroup_lambdaOutput_extractsOutputFromScope() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-par-output-lambda");

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            UntypedAgent mockWorkflow = new StaticUntypedAgent("par result");
            ParallelAgentService<UntypedAgent> parBuilder = mockParallelBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::parallelBuilder).thenReturn(parBuilder);

            invoke("executeParallelGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, List.class, ChatRequestDTO.class },
                    group, List.of(), chatRequest("hello"));

            ArgumentCaptor<Function<AgenticScope, Object>> outputCaptor = ArgumentCaptor
                    .forClass(Function.class);
            Mockito.verify(parBuilder).output(outputCaptor.capture());

            AgentInvocation invocation = mock(AgentInvocation.class);
            when(invocation.output()).thenReturn("parallel output");
            AgenticScope scope = mock(AgenticScope.class);
            when(scope.agentInvocations()).thenReturn(List.of(invocation));
            String output = (String) outputCaptor.getValue().apply(scope);
            assertThat(output).isEqualTo("parallel output");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSupervisorRoutedGroup_lambdaRequestGenerator_buildsSupervisorRequest() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-sup-lambda");
        group.setOrchestrationMode("SUPERVISOR_ROUTED");
        group.setDescription("Group desc");
        group.setRoutingInstructions("Route carefully");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            SupervisorAgent mockSupervisor = mock(
                    SupervisorAgent.class);
            when(mockSupervisor.invokeWithAgenticScope(ArgumentMatchers.anyString()))
                    .thenReturn(new ResultWithAgenticScope<>(null, "supervisor result"));

            SupervisorAgentService<SupervisorAgent> supBuilder = mockSupervisorBuilder(mockSupervisor);
            when(supBuilder.requestGenerator(ArgumentMatchers.any())).thenAnswer(
                    new Answer<SupervisorAgentService<SupervisorAgent>>() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public SupervisorAgentService<SupervisorAgent> answer(InvocationOnMock invocation) {
                            Function<AgenticScope, String> fn = invocation.getArgument(0);
                            AgenticScope scope = mock(AgenticScope.class);
                            String request = fn.apply(scope);
                            assertThat(request)
                                    .contains("hello")
                                    .contains("root")
                                    .contains("Group description: Group desc")
                                    .contains("Group routing instructions: Route carefully")
                                    .contains("internal-agent");
                            return supBuilder;
                        }
                    });
            agenticStatic.when(AgenticServices::supervisorBuilder).thenReturn(supBuilder);

            invoke("executeSupervisorRoutedGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSupervisorRoutedGroup_lambdaErrorHandler_returnsEmptyResult() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-sup-error-lambda");
        group.setOrchestrationMode("SUPERVISOR_ROUTED");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            SupervisorAgent mockSupervisor = mock(
                    SupervisorAgent.class);
            when(mockSupervisor.invokeWithAgenticScope(ArgumentMatchers.anyString()))
                    .thenReturn(new ResultWithAgenticScope<>(null, "result"));

            SupervisorAgentService<SupervisorAgent> supBuilder = mockSupervisorBuilder(mockSupervisor);
            agenticStatic.when(AgenticServices::supervisorBuilder).thenReturn(supBuilder);

            invoke("executeSupervisorRoutedGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());

            // Capture and invoke errorHandler lambda
            ArgumentCaptor<Function<ErrorContext, ErrorRecoveryResult>> errorHandlerCaptor = ArgumentCaptor
                    .forClass(Function.class);
            Mockito.verify(supBuilder).errorHandler(errorHandlerCaptor.capture());

            ErrorContext errorContext = new ErrorContext(
                    "agent-1", null, new AgentInvocationException("fail", null));
            ErrorRecoveryResult recovery = errorHandlerCaptor.getValue()
                    .apply(errorContext);
            assertThat(recovery).isNotNull();
            assertThat((String) recovery.result()).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildLocalAgent_lambdaUserMessageProvider_composesUserMessage() throws Exception {
        RuntimeChatService svc = new RuntimeChatService();
        svc.objectMapper = new ObjectMapper();
        svc.dispatchConfig = dispatchConfig(3L);
        svc.scaffoldPromptComposer = new ScaffoldPromptComposer();
        svc.externalAgentDiscoveryService = mock(ExternalAgentDiscoveryService.class);
        svc.chatModelFactory = mock(ChatModelFactory.class);
        svc.mcpService = mock(McpService.class);
        svc.runtimeSkillService = mock(RuntimeSkillService.class);

        when(svc.chatModelFactory.createChatModel(ArgumentMatchers.any()))
                .thenReturn(mock(ChatModel.class));
        when(svc.mcpService.createToolRegistry(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(McpToolRegistry.empty());
        when(svc.runtimeSkillService.runtimeSkills(ArgumentMatchers.any())).thenReturn(null);

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            @SuppressWarnings("unchecked")
            AiServices<Object> mockBuilder = setupMockLocalChatAgentWithBuilder(aiServicesStatic);

            ChatRequestDTO request = chatRequest("hello user");
            ConversationDTO conv = new ConversationDTO();
            conv.setHistory(List.of(message("USER", "previous question")));
            request.setConversation(conv);

            invoke(svc, "buildLocalAgent",
                    new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class, Map.class },
                    agent("test", "desc"), request, null, Map.of());

            // Capture and invoke userMessageProvider lambda
            ArgumentCaptor<Function<Object, String>> providerCaptor = ArgumentCaptor
                    .forClass(Function.class);
            Mockito.verify(mockBuilder).userMessageProvider(providerCaptor.capture());

            // Invoke with a map input (simulating what AiServices would pass)
            String result = providerCaptor.getValue().apply(Map.of("message", "current input"));
            assertThat(result)
                    .contains("Conversation history:")
                    .contains("previous question")
                    .contains("Current user message:")
                    .contains("current input");
        }
    }

    @Test
    void delegatesForGroup_lambdaSupplier_buildsLocalAgentWhenInvoked() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            @SuppressWarnings("unchecked")
            List<RuntimeAgentDelegate> delegates = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    group, chatRequest("hi"), Map.of());

            assertThat(delegates).hasSize(1);
            // Invoke the supplier lambda — this covers lambda$delegatesForGroup$1
            RuntimeAgent agent = delegates.get(0).open();
            assertThat(agent).isNotNull();
        }
    }

    // ---- Lambda: delegatesForGroup external agent supplier (lambda$delegatesForGroup$1) ----

    @Test
    void delegatesForGroup_externalAgentSupplier_buildsRemoteAgentWhenInvoked() throws Exception {
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-1");
        ExternalAgentSnapshotDTO externalAgent = externalAgent("ext-agent", "ext desc", true, "http://discovery", null);
        group.setExternalAgents(List.of(externalAgent));

        setupChatMocks();
        AgentCard card = new AgentCard("ext", "desc", "http://invoke-url");
        when(service.externalAgentDiscoveryService.fetchAgentCard("http://discovery")).thenReturn(card);

        try (MockedStatic<AgenticServices> agenticStatic = Mockito
                .mockStatic(AgenticServices.class)) {
            @SuppressWarnings("unchecked")
            A2AClientBuilder<Object> a2aBuilder = mock(
                    A2AClientBuilder.class);
            when(a2aBuilder.inputKeys(ArgumentMatchers.<String> any())).thenReturn(a2aBuilder);
            when(a2aBuilder.outputKey(ArgumentMatchers.anyString())).thenReturn(a2aBuilder);
            UntypedAgent mockA2aAgent = new StaticUntypedAgent("a2a response");
            when(a2aBuilder.build()).thenReturn(mockA2aAgent);
            agenticStatic.when(() -> AgenticServices.a2aBuilder("http://invoke-url"))
                    .thenReturn(a2aBuilder);

            @SuppressWarnings("unchecked")
            List<RuntimeAgentDelegate> delegates = (List<RuntimeAgentDelegate>) invoke("delegatesForGroup",
                    new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    group, chatRequest("hi"), Map.of());

            assertThat(delegates).hasSize(1);
            // Invoke the external agent supplier lambda — covers lambda$delegatesForGroup$1
            RuntimeAgent agent = delegates.get(0).open();
            assertThat(agent).isNotNull();
        }
    }

    // ---- Branch gap: executeGroups comparator lambda with multiple groups ----

    @Test
    void executeGroups_sortsMultipleGroupsByName() throws Exception {
        AgentSnapshotDTO agent = agent("root", "root desc");
        agent.setA2aEnabled(true);
        AgentGroupSnapshotDTO groupB = new AgentGroupSnapshotDTO();
        groupB.setName("B-group");
        groupB.setOrchestrationMode("LEAD_DELEGATES");
        groupB.setAgents(List.of());
        AgentGroupSnapshotDTO groupA = new AgentGroupSnapshotDTO();
        groupA.setName("A-group");
        groupA.setOrchestrationMode("LEAD_DELEGATES");
        groupA.setAgents(List.of());
        agent.setGroups(List.of(groupB, groupA));

        // Both groups return empty → executeGroups returns ""
        String result = (String) invoke("executeGroups",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                agent, chatRequest("hi"), Map.of());
        assertThat(result).isEmpty();
    }

    // ---- Branch gap: executeGroup try/finally with close ----

    @Test
    void executeGroup_sequentialMode_coversTryFinallyWithAgentClose() throws Exception {
        AgentSnapshotDTO rootAgent = agent("root", "root desc");
        AgentGroupSnapshotDTO group = new AgentGroupSnapshotDTO();
        group.setName("group-seq-close");
        group.setOrchestrationMode("SEQUENTIAL");
        AgentSnapshotDTO internalAgent = agent("internal-agent", "internal desc");
        group.setAgents(List.of(internalAgent));

        setupChatMocks();

        try (MockedStatic<AiServices> aiServicesStatic = Mockito
                .mockStatic(AiServices.class);
                MockedStatic<AgenticServices> agenticStatic = Mockito
                        .mockStatic(AgenticServices.class)) {
            setupMockLocalChatAgent(aiServicesStatic);

            UntypedAgent mockWorkflow = new StaticUntypedAgent("seq with close");
            SequentialAgentService<UntypedAgent> seqBuilder = mockSequenceBuilder(mockWorkflow);
            agenticStatic.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            String result = (String) invoke("executeGroup",
                    new Class[] { AgentSnapshotDTO.class, AgentGroupSnapshotDTO.class, ChatRequestDTO.class, Map.class },
                    rootAgent, group, chatRequest("hello"), Map.of());
            assertThat(result).isEqualTo("seq with close");
        }
    }

    // ---- Branch gap: toolArguments writeValueAsString exception ----

    @Test
    void toolArguments_returnsEmptyJsonWhenSerializationFails() throws Exception {
        // Use a custom ObjectMapper that throws on writeValueAsString
        ObjectMapper throwingMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("serialization failed",
                        (JsonLocation) null) {
                };
            }
        };
        service.objectMapper = throwingMapper;

        List<ToolExecutionRequest> result = (List<ToolExecutionRequest>) invoke(
                "extractTextToolCalls", new Class[] { String.class, Set.class },
                "{\"name\":\"tool\",\"arguments\":{\"key\":\"val\"}}", Set.of("tool"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).arguments()).isEqualTo("{}");
    }

    private static Answer<Void> runSynchronously(InvocationOnMock inv) {
        ((Runnable) inv.getArgument(0)).run();
        return null;
    }

    // ---- Mock setup helpers ----

    @SuppressWarnings("unchecked")
    private static AiServices<Object> mockAiServicesBuilder(Object mockAgent) {
        AiServices<Object> builder = mock(AiServices.class);
        when(builder.chatModel(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.systemMessage(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.userMessageProvider(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.maxSequentialToolsInvocations(ArgumentMatchers.anyInt())).thenReturn(builder);
        when(builder.chatMemoryProvider(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(mockAgent);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static SupervisorAgentService<SupervisorAgent> mockSupervisorBuilder(SupervisorAgent mockSupervisor) {
        SupervisorAgentService<SupervisorAgent> builder = mock(SupervisorAgentService.class);
        when(builder.name(ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.description(ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.chatModel(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.subAgents(ArgumentMatchers.<Collection<?>> any())).thenReturn(builder);
        when(builder.responseStrategy(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.contextGenerationStrategy(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.maxAgentsInvocations(ArgumentMatchers.anyInt())).thenReturn(builder);
        when(builder.requestGenerator(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.errorHandler(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(mockSupervisor);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static SequentialAgentService<UntypedAgent> mockSequenceBuilder(UntypedAgent mockWorkflow) {
        SequentialAgentService<UntypedAgent> builder = mock(SequentialAgentService.class);
        when(builder.name(ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.description(ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.subAgents(ArgumentMatchers.<Collection<?>> any())).thenReturn(builder);
        when(builder.beforeCall(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.output(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(mockWorkflow);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static ParallelAgentService<UntypedAgent> mockParallelBuilder(UntypedAgent mockWorkflow) {
        ParallelAgentService<UntypedAgent> builder = mock(ParallelAgentService.class);
        when(builder.name(ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.description(ArgumentMatchers.anyString())).thenReturn(builder);
        when(builder.subAgents(ArgumentMatchers.<Collection<?>> any())).thenReturn(builder);
        when(builder.beforeCall(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.output(ArgumentMatchers.any())).thenReturn(builder);
        when(builder.build()).thenReturn(mockWorkflow);
        return builder;
    }

    private static Object setupMockLocalChatAgent(MockedStatic<AiServices> aiServicesStatic) throws ClassNotFoundException {
        Class<?> localChatAgentClass = Class.forName(RuntimeChatService.class.getName() + "$LocalChatAgent");
        Object mockAgent = mock(localChatAgentClass);
        AiServices<Object> builder = mockAiServicesBuilder(mockAgent);
        aiServicesStatic.when(() -> AiServices.builder(localChatAgentClass))
                .thenReturn(builder);
        return mockAgent;
    }

    @SuppressWarnings("unchecked")
    private static AiServices<Object> setupMockLocalChatAgentWithBuilder(MockedStatic<AiServices> aiServicesStatic)
            throws ClassNotFoundException {
        Class<?> localChatAgentClass = Class.forName(RuntimeChatService.class.getName() + "$LocalChatAgent");
        Object mockAgent = mock(localChatAgentClass);
        AiServices<Object> builder = mockAiServicesBuilder(mockAgent);
        aiServicesStatic.when(() -> AiServices.builder(localChatAgentClass))
                .thenReturn(builder);
        return builder;
    }

    private Object textToolCallNormalizingChatModel(ChatModel delegate, Set<String> toolNames) throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$TextToolCallNormalizingChatModel");
        Constructor<?> constructor = type.getDeclaredConstructor(RuntimeChatService.class, ChatModel.class, Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(service, delegate, toolNames);
    }

    private Object lazySupervisorAction(Supplier<RuntimeAgent> supplier, String fallbackMessage)
            throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$LazySupervisorAgenticAction");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, Supplier.class,
                String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("Lazy Agent", "Lazy description", supplier, "group-1", fallbackMessage);
    }

    private Object localAgenticAction() throws Exception {
        Class<?> actionType = Class.forName(RuntimeChatService.class.getName() + "$LocalAgenticAction");
        Class<?> agentType = Class.forName(RuntimeChatService.class.getName() + "$LocalChatAgent");
        Object chatAgent = Proxy.newProxyInstance(agentType.getClassLoader(), new Class[] { agentType },
                (proxy, method, args) -> args != null && args.length > 0 ? args[0] : "");
        Constructor<?> constructor = actionType.getDeclaredConstructor(String.class, String.class, agentType,
                UnaryOperator.class);
        constructor.setAccessible(true);
        return constructor.newInstance("Local Agent", "Local description", chatAgent,
                UnaryOperator.identity());
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = RuntimeChatService.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(service, args);
    }

    private Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private Object invokeStatic(Class<?> target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private DispatchConfig dispatchConfig(long maxIterations) {
        DispatchConfig dispatchConfig = mock(DispatchConfig.class);
        DispatchConfig.ToolConfig toolConfig = mock(DispatchConfig.ToolConfig.class);
        when(toolConfig.maxIterations()).thenReturn(maxIterations);
        when(dispatchConfig.toolConfig()).thenReturn(toolConfig);
        return dispatchConfig;
    }

    private AgentSnapshotDTO agent(String name, String description) {
        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setName(name);
        agent.setDescription(description);
        return agent;
    }

    private ExternalAgentSnapshotDTO externalAgent(String name, String description, boolean enabled, String discoveryUrl,
            String apiKey) {
        ExternalAgentSnapshotDTO agent = new ExternalAgentSnapshotDTO();
        agent.setName(name);
        agent.setDescription(description);
        agent.setEnabled(enabled);
        agent.setDiscoveryUrl(discoveryUrl);
        agent.setApiKey(apiKey);
        return agent;
    }

    private ChatRequestDTO chatRequest(String text) {
        ChatMessageDTO message = message("USER", text);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setChatMessage(message);
        return request;
    }

    private ChatMessageDTO message(String type, String text) {
        ChatMessageDTO message = new ChatMessageDTO();
        message.setType(type);
        message.setMessage(text);
        return message;
    }

    private ToolExecutionRequest toolRequest(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(name)
                .arguments(arguments)
                .build();
    }

    private ToolSpecification toolSpec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description("Search docs")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query")
                        .required("query")
                        .build())
                .build();
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

    private static final class NullChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return null;
        }
    }

    private static final class StaticUntypedAgent implements UntypedAgent {

        private final String output;

        private StaticUntypedAgent(String output) {
            this.output = output;
        }

        @Override
        public Object invoke(Map<String, Object> input) {
            return output;
        }

        @Override
        public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
            return new ResultWithAgenticScope<>(null, output);
        }

        @Override
        public AgenticScope getAgenticScope(Object memoryId) {
            return null;
        }

        @Override
        public boolean evictAgenticScope(Object memoryId) {
            return false;
        }
    }

    private static final class ThrowingUntypedAgent implements UntypedAgent {

        @Override
        public Object invoke(Map<String, Object> input) {
            throw new RuntimeException("delegate failed");
        }

        @Override
        public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
            throw new RuntimeException("delegate failed");
        }

        @Override
        public AgenticScope getAgenticScope(Object memoryId) {
            return null;
        }

        @Override
        public boolean evictAgenticScope(Object memoryId) {
            return false;
        }
    }

    private static final class EchoUntypedAgent implements UntypedAgent {

        @Override
        public Object invoke(Map<String, Object> input) {
            return input.get("message");
        }

        @Override
        public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
            return new ResultWithAgenticScope<>(null, String.valueOf(input.get("message")));
        }

        @Override
        public AgenticScope getAgenticScope(Object memoryId) {
            return null;
        }

        @Override
        public boolean evictAgenticScope(Object memoryId) {
            return false;
        }
    }

    private static final class NullResultUntypedAgent implements UntypedAgent {

        @Override
        public Object invoke(Map<String, Object> input) {
            return null;
        }

        @Override
        public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
            return new ResultWithAgenticScope<>(null, null);
        }

        @Override
        public AgenticScope getAgenticScope(Object memoryId) {
            return null;
        }

        @Override
        public boolean evictAgenticScope(Object memoryId) {
            return false;
        }
    }
}
