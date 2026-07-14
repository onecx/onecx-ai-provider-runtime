package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.external.AgentCard;
import org.tkit.onecx.ai.provider.runtime.services.external.ExternalAgentDiscoveryService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpTool;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.internal.AgentExecutor;
import dev.langchain4j.agentic.internal.AgentSpecsProvider;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentGroupSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ConversationDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ExternalAgentSnapshotDTO;
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
        assertThat(agentInput).containsEntry("message", "hello");
        assertThat(agentInput).containsEntry("request", request);
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
        assertThat(invoke("extractUserMessage", new Class[] { ChatRequestDTO.class }, new ChatRequestDTO())).isEqualTo("");
    }

    @Test
    void privateSystemPromptHelpers_coverDelegationAndSupervisorText() throws Exception {
        AgentSnapshotDTO agent = agent("Root", "Root description");
        agent.setAdditionalPrompt("Agent prompt");
        RuntimeAgentDelegate delegate = new RuntimeAgentDelegate("Peer Agent", "Peer description",
                () -> new RuntimeAgent("peer", "desc", new StaticUntypedAgent("peer answer"), null));

        String systemMessage = (String) invoke("systemMessage",
                new Class[] { AgentSnapshotDTO.class, ChatRequestDTO.class, List.class, dev.langchain4j.skills.Skills.class },
                agent, chatRequest("hello"), List.of(delegate), null);
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
        dev.langchain4j.service.tool.ToolExecutionResult result = mock(
                dev.langchain4j.service.tool.ToolExecutionResult.class);
        when(result.resultText()).thenReturn("docs result");
        when(client.executeTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);

        @SuppressWarnings("unchecked")
        Map<ToolSpecification, ToolExecutor> mcpExecutors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toToolExecutors", new Class[] { McpToolRegistry.class }, new McpToolRegistry(List.of(tool)));
        assertThat(mcpExecutors).containsKey(spec);
        assertThat(mcpExecutors.get(spec).execute(toolRequest("search_docs", "{\"query\":\"onecx\"}"), null))
                .isEqualTo("docs result");

        RuntimeAgentDelegate first = new RuntimeAgentDelegate("OneCX Agent", "Docs expert",
                () -> new RuntimeAgent("peer", "desc", new StaticUntypedAgent("peer answer"), null));
        RuntimeAgentDelegate second = new RuntimeAgentDelegate("OneCX Agent", "",
                () -> new RuntimeAgent("peer", "desc", new StaticUntypedAgent("second answer"), null));

        @SuppressWarnings("unchecked")
        Map<ToolSpecification, ToolExecutor> delegateExecutors = (Map<ToolSpecification, ToolExecutor>) invoke(
                "toDelegateToolExecutors", new Class[] { List.class }, List.of(first, second));
        assertThat(delegateExecutors.keySet()).extracting(ToolSpecification::name)
                .containsExactly("delegate_onecx_agent_1", "delegate_onecx_agent_2");
        ToolExecutor executor = delegateExecutors.values().iterator().next();
        assertThat(executor.execute(toolRequest("delegate_onecx_agent_1", "{\"message\":\"What is OneCX?\"}"), null))
                .isEqualTo("peer answer");

        RuntimeAgentDelegate nullDelegate = new RuntimeAgentDelegate("Null Agent", "", () -> null);
        assertThat(invoke("invokeDelegate", new Class[] { RuntimeAgentDelegate.class, String.class }, nullDelegate, "x"))
                .isEqualTo("");
        RuntimeAgentDelegate throwingDelegate = new RuntimeAgentDelegate("Bad Agent", "",
                () -> new RuntimeAgent("bad", "desc", new ThrowingUntypedAgent(), null));
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
        assertThat(requests.getFirst().name()).isEqualTo("search_docs");
        assertThat(requests.getFirst().arguments()).contains("onecx");

        assertThat(invoke("extractTextToolCalls", new Class[] { String.class, Set.class }, "no json",
                Set.of("search_docs"))).isEqualTo(List.of());
        assertThat(invoke("extractToolMessage", new Class[] { String.class }, "{\"message\":\"hello\"}"))
                .isEqualTo("hello");
        assertThat(invoke("extractToolMessage", new Class[] { String.class }, "not-json")).isEqualTo("not-json");
        assertThat(invoke("extractToolMessage", new Class[] { String.class }, " ")).isEqualTo("");

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
                new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class }, group, chatRequest("hi"));
        assertThat(delegates).extracting(RuntimeAgentDelegate::name)
                .containsExactly("A local", "B local", "C remote");

        assertThat(invoke("delegatesForGroup", new Class[] { AgentGroupSnapshotDTO.class, ChatRequestDTO.class },
                null, chatRequest("hi"))).isEqualTo(List.of());

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
                .messages(List.of(dev.langchain4j.data.message.UserMessage.from("hello")))
                .build());

        assertThat(response.aiMessage().hasToolExecutionRequests()).isTrue();
        assertThat(response.aiMessage().toolExecutionRequests().getFirst().name()).isEqualTo("search_docs");
    }

    @Test
    void textToolCallNormalizingChatModel_keepsNullAndNonToolResponses() throws Exception {
        Object nullNormalizer = textToolCallNormalizingChatModel(new NullChatModel(), Set.of("search_docs"));
        assertThat(((ChatModel) nullNormalizer).chat(ChatRequest.builder()
                .messages(List.of(dev.langchain4j.data.message.UserMessage.from("hello")))
                .build())).isNull();

        Object plainNormalizer = textToolCallNormalizingChatModel(new StaticChatModel("plain answer"), Set.of("search_docs"));
        ChatResponse response = ((ChatModel) plainNormalizer).chat(ChatRequest.builder()
                .messages(List.of(dev.langchain4j.data.message.UserMessage.from("hello")))
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("plain answer");
        assertThat(response.aiMessage().hasToolExecutionRequests()).isFalse();
    }

    @Test
    void lazySupervisorAgenticAction_invokesSelectedAgentAndFallbacks() throws Exception {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.readState("message")).thenReturn("scoped message");
        Object action = lazySupervisorAction(
                () -> new RuntimeAgent("peer", "desc", new StaticUntypedAgent("peer answer"), null),
                "fallback message");

        assertThat(invoke(action, "invoke", new Class[] { AgenticScope.class }, scope)).isEqualTo("peer answer");
        assertThat(((AgentSpecsProvider) action).outputKey()).isEqualTo("response");
        assertThat(((AgentSpecsProvider) action).description()).isEqualTo("Lazy description");
        assertThat(((AgentSpecsProvider) action).async()).isFalse();
        assertThat(((AgentSpecsProvider) action).listener()).isNull();

        Object fallbackAction = lazySupervisorAction(() -> new RuntimeAgent("peer", "desc", new EchoUntypedAgent(), null),
                "fallback message");
        assertThat(invoke(fallbackAction, "invoke", new Class[] { AgenticScope.class }, (Object) null))
                .isEqualTo("fallback message");

        Object nullAgentAction = lazySupervisorAction(() -> null, "fallback message");
        assertThat(invoke(nullAgentAction, "invoke", new Class[] { AgenticScope.class }, scope)).isEqualTo("");
    }

    @Test
    void localAgenticAction_resolvesMessageFromScopeContextAndSpecs() throws Exception {
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.readState("message")).thenReturn(" ");
        when(scope.contextAsConversation()).thenReturn("conversation context");
        Object action = localAgenticAction();

        assertThat(invoke(action, "invoke", new Class[] { AgenticScope.class }, scope)).isEqualTo("conversation context");
        assertThat(invoke(action, "invoke", new Class[] { AgenticScope.class }, (Object) null)).isEqualTo("");
        assertThat(((AgentSpecsProvider) action).outputKey()).isEqualTo("response");
        assertThat(((AgentSpecsProvider) action).description()).isEqualTo("Local description");
        assertThat(((AgentSpecsProvider) action).async()).isFalse();
        assertThat(((AgentSpecsProvider) action).listener()).isNull();
        assertThat(invoke(action, "toAgentExecutor", new Class[] {})).isInstanceOf(AgentExecutor.class);
    }

    @Test
    void agenticWorkflowInvocationAdapter_staticHelpersCoverEmptyAndLastOutput() throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$AgenticWorkflowInvocationAdapter");

        AgenticScope emptyScope = mock(AgenticScope.class);
        when(emptyScope.agentInvocations()).thenReturn(List.of());
        assertThat(invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class }, (Object) null)).isEqualTo("");
        assertThat(invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class }, emptyScope)).isEqualTo("");

        AgentInvocation blankInvocation = mock(AgentInvocation.class);
        when(blankInvocation.output()).thenReturn(" ");
        AgentInvocation firstInvocation = mock(AgentInvocation.class);
        when(firstInvocation.output()).thenReturn("first");
        AgentInvocation lastInvocation = mock(AgentInvocation.class);
        when(lastInvocation.output()).thenReturn("last");
        AgenticScope scope = mock(AgenticScope.class);
        when(scope.agentInvocations()).thenReturn(List.of(blankInvocation, firstInvocation, lastInvocation));
        assertThat(invokeStatic(type, "lastOutput", new Class[] { AgenticScope.class }, scope)).isEqualTo("last");

        assertThat(invokeStatic(type, "safeName", new Class[] { String.class }, " Root Agent! ")).isEqualTo("root-agent");
        assertThat(invokeStatic(type, "safeName", new Class[] { String.class }, (Object) null)).isEqualTo("agent");
    }

    private Object textToolCallNormalizingChatModel(ChatModel delegate, Set<String> toolNames) throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$TextToolCallNormalizingChatModel");
        Constructor<?> constructor = type.getDeclaredConstructor(RuntimeChatService.class, ChatModel.class, Set.class);
        constructor.setAccessible(true);
        return constructor.newInstance(service, delegate, toolNames);
    }

    private Object lazySupervisorAction(java.util.function.Supplier<RuntimeAgent> supplier, String fallbackMessage)
            throws Exception {
        Class<?> type = Class.forName(RuntimeChatService.class.getName() + "$LazySupervisorAgenticAction");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, String.class, java.util.function.Supplier.class,
                String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("Lazy Agent", "Lazy description", supplier, "group-1", fallbackMessage);
    }

    private Object localAgenticAction() throws Exception {
        Class<?> actionType = Class.forName(RuntimeChatService.class.getName() + "$LocalAgenticAction");
        Class<?> agentType = Class.forName(RuntimeChatService.class.getName() + "$LocalChatAgent");
        Object chatAgent = java.lang.reflect.Proxy.newProxyInstance(agentType.getClassLoader(), new Class[] { agentType },
                (proxy, method, args) -> args != null && args.length > 0 ? args[0] : "");
        Constructor<?> constructor = actionType.getDeclaredConstructor(String.class, String.class, agentType,
                java.util.function.UnaryOperator.class);
        constructor.setAccessible(true);
        return constructor.newInstance("Local Agent", "Local description", chatAgent,
                java.util.function.UnaryOperator.identity());
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
        DispatchConfig.MCPConfig mcpConfig = mock(DispatchConfig.MCPConfig.class);
        when(mcpConfig.maxIterations()).thenReturn(maxIterations);
        when(dispatchConfig.mcpConfig()).thenReturn(mcpConfig);
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
}
