package org.tkit.onecx.ai.provider.runtime.services.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;
import org.tkit.onecx.ai.provider.runtime.services.external.AgentCard;
import org.tkit.onecx.ai.provider.runtime.services.external.ExternalAgentDiscoveryService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpTool;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpToolRegistry;
import org.tkit.onecx.ai.provider.runtime.services.provider.ChatModelFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.internal.AgentExecutor;
import dev.langchain4j.agentic.internal.AgentInvoker;
import dev.langchain4j.agentic.internal.AgentSpecsProvider;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.scope.AgentInvocation;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorContextStrategy;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.skills.Skills;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentGroupSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ExternalAgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatResponseDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class RuntimeChatService {

    static final String INPUT_REQUEST = "request";

    @Inject
    ChatModelFactory chatModelFactory;

    @Inject
    ScaffoldPromptComposer scaffoldPromptComposer;

    @Inject
    RuntimeSkillService runtimeSkillService;

    @Inject
    McpService mcpService;

    @Inject
    ExternalAgentDiscoveryService externalAgentDiscoveryService;

    @Inject
    DispatchConfig dispatchConfig;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ManagedExecutor managedExecutor;

    @Inject
    @ConfigProperty(name = "onecx.ai.dispatch.runtime.timeout", defaultValue = "1200")
    long runtimeTimeout;

    public RuntimeChatResponseDTO chat(RuntimeChatRequestDTO request) {
        if (request == null || request.getRootAgent() == null) {
            throw new RuntimeChatException("RUNTIME_CHAT_REQUEST_INVALID", "IllegalArgumentException",
                    "Root agent snapshot is required", Response.Status.BAD_REQUEST);
        }
        CompletableFuture<RuntimeChatResponseDTO> future = CompletableFuture.supplyAsync(() -> invoke(request),
                runtimeExecutor());
        try {
            return future.get(runtimeTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception ex) {
            Throwable cause = rootCause(ex);
            throw runtimeChatException(cause, "Runtime chat invocation failed");
        }
    }

    private RuntimeChatResponseDTO invoke(RuntimeChatRequestDTO request) {
        try {
            String message = invokeRootResponse(request.getRootAgent(), request.getChatRequest());
            RuntimeChatResponseDTO response = new RuntimeChatResponseDTO();
            response.setMessage(message != null ? message : "");
            return response;
        } catch (Exception ex) {
            Throwable cause = rootCause(ex);
            log.warn("Runtime invocation failed for root agent '{}': {}: {}", request.getRootAgent().getName(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            log.debug("Runtime invocation failure details", cause);
            throw runtimeChatException(cause, "Runtime chat invocation failed");
        }
    }

    private String invokeRootResponse(AgentSnapshotDTO agent, ChatRequestDTO request) {
        if (Boolean.TRUE.equals(agent.getA2aEnabled()) && agent.getGroups() != null && !agent.getGroups().isEmpty()) {
            String groupResponse = executeGroups(agent, request);
            if (!isBlank(groupResponse)) {
                return groupResponse;
            }
        }
        try (RuntimeAgent rootAgent = rootAgent(agent, request)) {
            return invokeSingleAgent(rootAgent, request);
        }
    }

    private String executeGroups(AgentSnapshotDTO agent, ChatRequestDTO request) {
        return agent.getGroups().stream()
                .filter(group -> group != null && group.getName() != null)
                .sorted(Comparator.comparing(group -> safeString(group.getName()).toLowerCase()))
                .map(group -> executeGroup(agent, group, request))
                .filter(result -> !isBlank(result))
                .map(String::trim)
                .findFirst()
                .orElse("");
    }

    private String executeGroup(AgentSnapshotDTO rootAgent, AgentGroupSnapshotDTO group, ChatRequestDTO request) {
        String mode = !isBlank(safeString(group.getOrchestrationMode()))
                ? safeString(group.getOrchestrationMode())
                : "LEAD_DELEGATES";
        if ("SUPERVISOR_ROUTED".equals(mode)) {
            return executeSupervisorRoutedGroup(rootAgent, group, request);
        }
        if ("SEQUENTIAL".equals(mode) || "PARALLEL".equals(mode)) {
            List<RuntimeAgent> agents = delegatesForGroup(group, request).stream()
                    .map(RuntimeAgentDelegate::open)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (agents.isEmpty()) {
                return "";
            }
            try (RuntimeAgent root = rootAgent(rootAgent, request)) {
                agents.add(0, root);
                return "SEQUENTIAL".equals(mode)
                        ? executeSequentialGroup(group, agents, request)
                        : executeParallelGroup(group, agents, request);
            } finally {
                agents.forEach(RuntimeAgent::close);
            }
        }
        List<RuntimeAgentDelegate> delegates = delegatesForGroup(group, request);
        if (delegates.isEmpty()) {
            return "";
        }
        try (RuntimeAgent leadAgent = buildLocalAgent(rootAgent, request, delegates)) {
            return invokeSingleAgent(leadAgent, request);
        }
    }

    private String executeSupervisorRoutedGroup(AgentSnapshotDTO rootAgent, AgentGroupSnapshotDTO group,
            ChatRequestDTO request) {
        List<RuntimeAgent> candidates = new ArrayList<>();
        try {
            candidates.add(lazySupervisorCandidate(runtimeName(rootAgent), runtimeDescription(rootAgent),
                    () -> rootAgent(rootAgent, request), group, extractUserMessage(request)));
            for (RuntimeAgentDelegate delegate : delegatesForGroup(group, request)) {
                candidates.add(lazySupervisorCandidate(delegate.name(), delegate.description(), delegate::open, group,
                        extractUserMessage(request)));
            }
            if (candidates.isEmpty()) {
                return "";
            }
            SupervisorAgent supervisor = AgenticServices.supervisorBuilder()
                    .name("a2a-supervisor-" + safeString(group.getName()))
                    .description("Routes the user request to the most relevant configured agents")
                    .chatModel(chatModelFactory.createChatModel(rootAgent))
                    .subAgents(candidates.stream().map(RuntimeAgent::agent).toList())
                    .responseStrategy(toSupervisorResponseStrategy(group.getResponseStrategy()))
                    .contextGenerationStrategy(SupervisorContextStrategy.CHAT_MEMORY_AND_SUMMARIZATION)
                    .maxAgentsInvocations(Math.max(1, candidates.size()))
                    .requestGenerator(scope -> supervisorRequest(rootAgent, group, request, candidates))
                    .errorHandler(error -> ErrorRecoveryResult.result(""))
                    .build();
            ResultWithAgenticScope<String> result = supervisor.invokeWithAgenticScope(
                    supervisorRequest(rootAgent, group, request, candidates));
            return result != null && result.result() != null ? result.result() : "";
        } finally {
            candidates.forEach(RuntimeAgent::close);
        }
    }

    private String executeSequentialGroup(AgentGroupSnapshotDTO group, List<RuntimeAgent> runtimeAgents,
            ChatRequestDTO request) {
        UntypedAgent workflow = AgenticServices.sequenceBuilder()
                .name("a2a-sequence-" + safeString(group.getName()))
                .description("Runs explicitly ordered agents for the configured group")
                .subAgents(runtimeAgents.stream().map(RuntimeAgent::agent).toList())
                .beforeCall(scope -> scope.writeStates(agentInput(request)))
                .output(this::outputFromScope)
                .build();
        Object result = workflow.invoke(agentInput(request));
        return result != null ? result.toString() : "";
    }

    private String executeParallelGroup(AgentGroupSnapshotDTO group, List<RuntimeAgent> runtimeAgents,
            ChatRequestDTO request) {
        UntypedAgent workflow = AgenticServices.parallelBuilder()
                .name("a2a-parallel-" + safeString(group.getName()))
                .description("Runs explicitly additive agents for the configured group")
                .subAgents(runtimeAgents.stream().map(RuntimeAgent::agent).toList())
                .beforeCall(scope -> scope.writeStates(agentInput(request)))
                .output(this::outputFromScope)
                .build();
        Object result = workflow.invoke(agentInput(request));
        return result != null ? result.toString() : "";
    }

    private RuntimeAgent rootAgent(AgentSnapshotDTO agent, ChatRequestDTO request) {
        return buildLocalAgent(agent, request, List.of());
    }

    private RuntimeAgent buildLocalAgent(AgentSnapshotDTO agent, ChatRequestDTO request,
            List<RuntimeAgentDelegate> delegateAgents) {
        ChatModel chatModel = chatModelFactory.createChatModel(agent);
        McpToolRegistry toolRegistry = mcpService.createToolRegistry(agent);
        Map<ToolSpecification, ToolExecutor> toolExecutors = toToolExecutors(toolRegistry);
        List<RuntimeAgentDelegate> delegates = delegateAgents != null ? delegateAgents : List.of();
        toolExecutors.putAll(toDelegateToolExecutors(delegates));
        Set<String> toolNames = toolExecutors.keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
        ChatModel effectiveChatModel = toolNames.isEmpty()
                ? chatModel
                : new TextToolCallNormalizingChatModel(chatModel, toolNames);
        Skills runtimeSkills = runtimeSkillService.runtimeSkills(agent);

        var builder = AiServices.builder(LocalChatAgent.class)
                .chatModel(effectiveChatModel)
                .systemMessage(systemMessage(agent, request, delegates, runtimeSkills))
                .userMessageProvider(input -> userMessage(request, inputMessage(input, extractUserMessage(request))))
                .maxSequentialToolsInvocations(maxSequentialToolInvocations());
        if (!toolExecutors.isEmpty()) {
            builder.tools(toolExecutors);
        }
        if (runtimeSkills != null) {
            builder.toolProvider(runtimeSkills.toolProvider());
        }

        LocalChatAgent chatAgent = builder.build();
        LocalAgenticAction action = new LocalAgenticAction(runtimeName(agent), runtimeDescription(agent), chatAgent,
                message -> userMessage(request, message));
        AgentExecutor executor = action.toAgentExecutor();
        return new RuntimeAgent(runtimeName(agent), runtimeDescription(agent), executor,
                new AgenticWorkflowInvocationAdapter(runtimeName(agent), executor), toolRegistry);
    }

    private List<RuntimeAgentDelegate> delegatesForGroup(AgentGroupSnapshotDTO group, ChatRequestDTO request) {
        if (group == null) {
            return List.of();
        }
        List<RuntimeAgentDelegate> agents = new ArrayList<>();
        if (group.getAgents() != null) {
            for (AgentSnapshotDTO agent : group.getAgents()) {
                if (agent != null) {
                    agents.add(new RuntimeAgentDelegate(runtimeName(agent), runtimeDescription(agent),
                            () -> buildLocalAgent(agent, request, List.of())));
                }
            }
        }
        if (group.getExternalAgents() != null) {
            for (ExternalAgentSnapshotDTO externalAgent : group.getExternalAgents()) {
                if (isCallableExternalAgent(externalAgent)) {
                    agents.add(new RuntimeAgentDelegate(runtimeName(externalAgent), runtimeDescription(externalAgent),
                            () -> buildRemoteAgent(externalAgent)));
                }
            }
        }
        agents.sort(Comparator.comparing(agent -> safeString(agent.name()).toLowerCase()));
        return agents;
    }

    private RuntimeAgent buildRemoteAgent(ExternalAgentSnapshotDTO externalAgent) {
        if (!isCallableExternalAgent(externalAgent)) {
            return null;
        }
        if (!isBlank(externalAgent.getApiKey())) {
            log.warn("Skipping remote A2A agent '{}': authenticated A2A is not supported", externalAgent.getName());
            return null;
        }
        AgentCard card = externalAgentDiscoveryService.fetchAgentCard(externalAgent.getDiscoveryUrl());
        if (card == null || !card.hasInvokeUrl()) {
            log.warn("Skipping remote A2A agent '{}': discovery failed or returned no invoke URL",
                    externalAgent.getName());
            return null;
        }
        UntypedAgent a2aAgent = AgenticServices.a2aBuilder(card.url())
                .inputKeys("message")
                .outputKey("response")
                .build();
        return new RuntimeAgent(runtimeName(externalAgent), runtimeDescription(externalAgent), a2aAgent,
                new AgenticWorkflowInvocationAdapter(runtimeName(externalAgent), a2aAgent), null);
    }

    private Map<ToolSpecification, ToolExecutor> toToolExecutors(McpToolRegistry toolRegistry) {
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (McpTool tool : toolRegistry.tools()) {
            executors.put(tool.toolSpecification(), (request, memoryId) -> {
                log.info("Executing MCP tool call: tool={}, argumentsPresent={}, arguments={}", request.name(),
                        !isBlank(request.arguments()), request.arguments());
                return tool.execute(request);
            });
        }
        return executors;
    }

    private Map<ToolSpecification, ToolExecutor> toDelegateToolExecutors(List<RuntimeAgentDelegate> delegateAgents) {
        if (delegateAgents == null || delegateAgents.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> duplicateCounts = delegateAgents.stream()
                .map(agent -> delegateToolBaseName(agent.name()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Integer> seen = new LinkedHashMap<>();
        Map<ToolSpecification, ToolExecutor> executors = new LinkedHashMap<>();
        for (RuntimeAgentDelegate delegate : delegateAgents) {
            String baseName = delegateToolBaseName(delegate.name());
            int index = seen.merge(baseName, 1, Integer::sum);
            String toolName = duplicateCounts.getOrDefault(baseName, 0L) > 1 ? baseName + "_" + index : baseName;
            ToolSpecification specification = ToolSpecification.builder()
                    .name(toolName)
                    .description(delegateToolDescription(delegate))
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("message",
                                    "A focused request for this agent. Include all context the agent needs.")
                            .required("message")
                            .additionalProperties(false)
                            .build())
                    .build();
            executors.put(specification,
                    (request, memoryId) -> invokeDelegate(delegate, extractToolMessage(request.arguments())));
        }
        return executors;
    }

    private String invokeDelegate(RuntimeAgentDelegate delegate, String message) {
        try (RuntimeAgent runtimeAgent = delegate.open()) {
            if (runtimeAgent == null) {
                return "";
            }
            Object result = runtimeAgent.invoker()
                    .invokeWithAgenticScope(Map.of("message", safeString(message)))
                    .result();
            return result != null ? result.toString() : "";
        } catch (Exception ex) {
            Throwable cause = rootCause(ex);
            log.warn("Delegate agent '{}' failed: {}: {}", delegate.name(), cause.getClass().getSimpleName(),
                    cause.getMessage());
            log.debug("Delegate agent '{}' failure details", delegate.name(), ex);
            return "The peer agent '%s' could not complete the delegated request. Continue with the available information."
                    .formatted(safeString(delegate.name()));
        }
    }

    private String invokeSingleAgent(RuntimeAgent agent, ChatRequestDTO request) {
        Object result = agent.invoker()
                .invokeWithAgenticScope(agentInput(request))
                .result();
        return result != null ? result.toString() : "";
    }

    private Map<String, Object> agentInput(ChatRequestDTO request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(INPUT_REQUEST, request);
        input.put("message", extractUserMessage(request));
        return input;
    }

    private String systemMessage(AgentSnapshotDTO agent, ChatRequestDTO request,
            List<RuntimeAgentDelegate> delegateAgents, Skills runtimeSkills) {
        String composed = scaffoldPromptComposer.compose(agent, request);
        String base = !isBlank(composed) ? composed : "You are a helpful assistant.";
        if (runtimeSkills != null) {
            base = base + System.lineSeparator() + System.lineSeparator()
                    + runtimeSkillService.activationPrompt(runtimeSkills);
        }
        if (delegateAgents == null || delegateAgents.isEmpty()) {
            return base;
        }
        return base + System.lineSeparator() + System.lineSeparator() + delegationPolicy(delegateAgents);
    }

    private String delegationPolicy(List<RuntimeAgentDelegate> delegateAgents) {
        StringBuilder sb = new StringBuilder(
                """
                        Optional peer agents are available as tools.
                        You are the lead agent and own the final answer.
                        Use a peer agent only when the user's request clearly matches the peer's name, description, domain, data source, or specialty.
                        If you call a peer, use its result as private working context and return one final assistant message.
                        Available peer agents:""");
        for (RuntimeAgentDelegate delegate : delegateAgents) {
            sb.append(System.lineSeparator()).append("- ").append(safeString(delegate.name()));
            if (!isBlank(delegate.description())) {
                sb.append(": ").append(delegate.description().trim());
            }
        }
        return sb.toString();
    }

    private String userMessage(ChatRequestDTO request, String currentMessage) {
        StringBuilder message = new StringBuilder();
        if (request != null && request.getConversation() != null && request.getConversation().getHistory() != null
                && !request.getConversation().getHistory().isEmpty()) {
            message.append("Conversation history:")
                    .append(System.lineSeparator())
                    .append(formatHistory(request.getConversation().getHistory()))
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        message.append("Current user message:")
                .append(System.lineSeparator())
                .append(!isBlank(currentMessage) ? currentMessage : extractUserMessage(request));
        return message.toString();
    }

    private String supervisorRequest(AgentSnapshotDTO rootAgent, AgentGroupSnapshotDTO group, ChatRequestDTO request,
            List<RuntimeAgent> runtimeAgents) {
        StringBuilder sb = new StringBuilder();
        sb.append("Route and answer the current user request using the most relevant configured agents.")
                .append(System.lineSeparator())
                .append("Do not call agents that are unrelated to the request.")
                .append(System.lineSeparator())
                .append("Return one final assistant message.")
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Current user message:")
                .append(System.lineSeparator())
                .append(extractUserMessage(request))
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Initially dispatched agent: ")
                .append(safeString(rootAgent.getName()))
                .append(System.lineSeparator());
        if (!isBlank(group.getDescription())) {
            sb.append("Group description: ").append(group.getDescription().trim()).append(System.lineSeparator());
        }
        if (!isBlank(group.getRoutingInstructions())) {
            sb.append("Group routing instructions: ").append(group.getRoutingInstructions().trim())
                    .append(System.lineSeparator());
        }
        sb.append("Available agents:");
        for (RuntimeAgent agent : runtimeAgents) {
            sb.append(System.lineSeparator()).append("- ").append(safeString(agent.name()));
            if (!isBlank(agent.description())) {
                sb.append(": ").append(agent.description().trim());
            }
        }
        return sb.toString();
    }

    private RuntimeAgent lazySupervisorCandidate(String name, String description, Supplier<RuntimeAgent> supplier,
            AgentGroupSnapshotDTO group, String fallbackMessage) {
        LazySupervisorAgenticAction action = new LazySupervisorAgenticAction(name, description, supplier,
                group != null ? group.getName() : null, fallbackMessage);
        AgentExecutor executor = action.toAgentExecutor();
        return new RuntimeAgent(name, description, executor, new AgenticWorkflowInvocationAdapter(name, executor), null);
    }

    private SupervisorResponseStrategy toSupervisorResponseStrategy(Object strategy) {
        String value = safeString(strategy);
        if ("LAST".equals(value)) {
            return SupervisorResponseStrategy.LAST;
        }
        if ("SCORED".equals(value)) {
            return SupervisorResponseStrategy.SCORED;
        }
        return SupervisorResponseStrategy.SUMMARY;
    }

    private String outputFromScope(AgenticScope scope) {
        if (scope == null || scope.agentInvocations() == null) {
            return "";
        }
        return scope.agentInvocations().stream()
                .map(AgentInvocation::output)
                .filter(output -> output != null && !isBlank(output.toString()))
                .map(output -> output.toString().trim())
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private List<ToolExecutionRequest> extractTextToolCalls(String text, Set<String> availableToolNames) {
        if (isBlank(text) || availableToolNames == null || availableToolNames.isEmpty()) {
            return List.of();
        }
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (String candidate : jsonCandidates(text)) {
            JsonNode root;
            try {
                root = objectMapper.readTree(candidate);
            } catch (Exception ex) {
                continue;
            }
            if (root.isArray()) {
                for (JsonNode item : root) {
                    addTextToolCall(item, availableToolNames, requests);
                }
            } else {
                addTextToolCall(root, availableToolNames, requests);
            }
            if (!requests.isEmpty()) {
                return requests;
            }
        }
        return List.of();
    }

    private List<String> jsonCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '[' || current == '{') {
                String candidate = balancedJsonAt(text, i);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private String balancedJsonAt(String text, int start) {
        char open = text.charAt(start);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private void addTextToolCall(JsonNode item, Set<String> availableToolNames, List<ToolExecutionRequest> requests) {
        if (item == null || !item.isObject()) {
            return;
        }
        String name = textField(item, "name");
        if (isBlank(name)) {
            name = textField(item, "tool");
        }
        if (isBlank(name)) {
            name = textField(item, "tool_name");
        }
        if (isBlank(name) || !availableToolNames.contains(name)) {
            return;
        }
        requests.add(ToolExecutionRequest.builder()
                .id("text-tool-call-" + (requests.size() + 1))
                .name(name)
                .arguments(toolArguments(item))
                .build());
    }

    private String toolArguments(JsonNode item) {
        JsonNode arguments = item.get("arguments");
        if (arguments == null) {
            arguments = item.get("args");
        }
        if (arguments == null || arguments.isNull()) {
            return "{}";
        }
        if (arguments.isTextual()) {
            return !isBlank(arguments.asText()) ? arguments.asText() : "{}";
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String textField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private String extractToolMessage(String arguments) {
        if (isBlank(arguments)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(arguments);
            JsonNode message = root.get("message");
            return message != null && !message.isNull() ? message.asText() : arguments;
        } catch (Exception ex) {
            return arguments;
        }
    }

    private int maxSequentialToolInvocations() {
        long configured = dispatchConfig.mcpConfig().maxIterations();
        if (configured < 1) {
            return 1;
        }
        return configured > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) configured;
    }

    private boolean isCallableExternalAgent(ExternalAgentSnapshotDTO externalAgent) {
        return externalAgent != null && Boolean.TRUE.equals(externalAgent.getEnabled())
                && !isBlank(externalAgent.getDiscoveryUrl());
    }

    private String formatHistory(List<ChatMessageDTO> history) {
        return history.stream()
                .map(message -> safeString(message.getType()) + ": " + safeString(message.getMessage()))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    @SuppressWarnings("unchecked")
    private String inputMessage(Object input, String fallback) {
        if (input instanceof CharSequence text && !isBlank(text.toString())) {
            return text.toString();
        }
        if (input instanceof Map<?, ?> map) {
            Object message = ((Map<String, Object>) map).get("message");
            if (message != null && !isBlank(message.toString())) {
                return message.toString();
            }
        }
        return fallback;
    }

    private String extractUserMessage(ChatRequestDTO request) {
        if (request == null || request.getChatMessage() == null || request.getChatMessage().getMessage() == null) {
            return "";
        }
        return request.getChatMessage().getMessage();
    }

    private String delegateToolBaseName(String name) {
        String normalized = safeString(name).trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return "delegate_" + (!isBlank(normalized) ? normalized : "agent");
    }

    private String delegateToolDescription(RuntimeAgentDelegate delegate) {
        return "Delegate to agent '%s'. Agent specialty: %s"
                .formatted(safeString(delegate.name()), !isBlank(delegate.description())
                        ? delegate.description().trim()
                        : "configured peer agent");
    }

    private String runtimeName(AgentSnapshotDTO agent) {
        return agent != null && !isBlank(agent.getName()) ? agent.getName() : "local-agent";
    }

    private String runtimeDescription(AgentSnapshotDTO agent) {
        return agent != null && !isBlank(agent.getDescription()) ? agent.getDescription() : "Configured local agent";
    }

    private String runtimeName(ExternalAgentSnapshotDTO agent) {
        return agent != null && !isBlank(agent.getName()) ? agent.getName() : "remote-agent";
    }

    private String runtimeDescription(ExternalAgentSnapshotDTO agent) {
        return agent != null && !isBlank(agent.getDescription()) ? agent.getDescription()
                : "Discovered remote A2A agent";
    }

    private Executor runtimeExecutor() {
        return managedExecutor != null ? managedExecutor : CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS);
    }

    private long runtimeTimeoutSeconds() {
        return runtimeTimeout > 0 ? runtimeTimeout : 120;
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return new RuntimeException("unknown failure");
        }
        Throwable result = throwable;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private RuntimeChatException runtimeChatException(Throwable cause, String fallbackMessage) {
        if (cause instanceof RuntimeChatException runtimeChatException) {
            return runtimeChatException;
        }
        String message = !isBlank(cause != null ? cause.getMessage() : null)
                ? cause.getMessage()
                : fallbackMessage;
        String type = cause != null ? cause.getClass().getSimpleName() : RuntimeException.class.getSimpleName();
        String errorCode = cause instanceof TimeoutException ? "RUNTIME_CHAT_TIMEOUT" : "RUNTIME_CHAT_FAILED";
        Response.Status status = cause instanceof TimeoutException ? Response.Status.GATEWAY_TIMEOUT
                : Response.Status.INTERNAL_SERVER_ERROR;
        return new RuntimeChatException(errorCode, type, message, status);
    }

    private interface LocalChatAgent {

        String chat(@UserMessage String message);
    }

    private final class TextToolCallNormalizingChatModel implements ChatModel {

        private final ChatModel delegate;
        private final Set<String> availableToolNames;

        private TextToolCallNormalizingChatModel(ChatModel delegate, Set<String> availableToolNames) {
            this.delegate = delegate;
            this.availableToolNames = availableToolNames;
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            ChatResponse response = delegate.chat(chatRequest);
            if (response == null || response.aiMessage() == null || response.aiMessage().hasToolExecutionRequests()) {
                return response;
            }
            List<ToolExecutionRequest> toolRequests = extractTextToolCalls(response.aiMessage().text(), availableToolNames);
            if (toolRequests.isEmpty()) {
                return response;
            }
            return response.toBuilder()
                    .aiMessage(AiMessage.from(toolRequests))
                    .build();
        }
    }

    public static final class LocalAgenticAction implements AgentSpecsProvider {

        private static final java.lang.reflect.Method INVOKE_METHOD = invokeMethod();

        private final String name;
        private final String description;
        private final LocalChatAgent chatAgent;
        private final UnaryOperator<String> messageComposer;

        private LocalAgenticAction(String name, String description, LocalChatAgent chatAgent,
                UnaryOperator<String> messageComposer) {
            this.name = name;
            this.description = description;
            this.chatAgent = chatAgent;
            this.messageComposer = messageComposer != null ? messageComposer : UnaryOperator.identity();
        }

        public String invoke(AgenticScope scope) {
            Object fallback = scope != null ? scope.readState("message") : null;
            String resolvedMessage = fallback != null ? fallback.toString() : "";
            if (blank(resolvedMessage) && scope != null) {
                resolvedMessage = scope.contextAsConversation();
            }
            return chatAgent.chat(messageComposer.apply(resolvedMessage));
        }

        private AgentExecutor toAgentExecutor() {
            return new AgentExecutor(AgentInvoker.fromSpec(this, INVOKE_METHOD, name), this);
        }

        @Override
        public String outputKey() {
            return "response";
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public boolean async() {
            return false;
        }

        @Override
        public AgentListener listener() {
            return null;
        }

        private static java.lang.reflect.Method invokeMethod() {
            try {
                return LocalAgenticAction.class.getMethod("invoke", AgenticScope.class);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("Unable to resolve local agentic action method", ex);
            }
        }

        private static boolean blank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }

    public static final class LazySupervisorAgenticAction implements AgentSpecsProvider {

        private static final java.lang.reflect.Method INVOKE_METHOD = invokeMethod();

        private final String name;
        private final String description;
        private final Supplier<RuntimeAgent> supplier;
        private final String groupId;
        private final String fallbackMessage;

        private LazySupervisorAgenticAction(String name, String description, Supplier<RuntimeAgent> supplier,
                String groupId, String fallbackMessage) {
            this.name = name;
            this.description = description;
            this.supplier = supplier;
            this.groupId = groupId;
            this.fallbackMessage = fallbackMessage;
        }

        public String invoke(AgenticScope scope) {
            long startedAt = System.currentTimeMillis();
            log.info("Invoking supervisor-selected agent: groupId={}, agent={}", groupId, name);
            try (RuntimeAgent runtimeAgent = supplier.get()) {
                if (runtimeAgent == null) {
                    return "";
                }
                Object result = runtimeAgent.invoker()
                        .invokeWithAgenticScope(Map.of("message", resolveMessage(scope)))
                        .result();
                log.info("Completed supervisor-selected agent: groupId={}, agent={}, durationMs={}", groupId, name,
                        System.currentTimeMillis() - startedAt);
                return result != null ? result.toString() : "";
            }
        }

        private AgentExecutor toAgentExecutor() {
            return new AgentExecutor(AgentInvoker.fromSpec(this, INVOKE_METHOD, name), this);
        }

        @Override
        public String outputKey() {
            return "response";
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public boolean async() {
            return false;
        }

        @Override
        public AgentListener listener() {
            return null;
        }

        private String resolveMessage(AgenticScope scope) {
            Object message = scope != null ? scope.readState("message") : null;
            if (message != null && !blank(message.toString())) {
                return message.toString();
            }
            return fallbackMessage != null ? fallbackMessage : "";
        }

        private static java.lang.reflect.Method invokeMethod() {
            try {
                return LazySupervisorAgenticAction.class.getMethod("invoke", AgenticScope.class);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("Unable to resolve lazy supervisor agent method", ex);
            }
        }

        private static boolean blank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }

    private static final class AgenticWorkflowInvocationAdapter implements UntypedAgent {

        private final String name;
        private final Object delegate;

        private AgenticWorkflowInvocationAdapter(String name, Object delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Map<String, Object> input) {
            return invokeWithAgenticScope(input).result();
        }

        @Override
        public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
            Map<String, Object> safeInput = input != null ? input : Map.of();
            UntypedAgent workflow = AgenticServices.sequenceBuilder()
                    .name("single-agent-" + safeName(name))
                    .description("Invokes one configured agent with explicit runtime input")
                    .subAgents(List.of(delegate))
                    .beforeCall(scope -> scope.writeStates(safeInput))
                    .output(AgenticWorkflowInvocationAdapter::lastOutput)
                    .build();
            ResultWithAgenticScope<String> result = workflow.invokeWithAgenticScope(safeInput);
            return result != null ? result : new ResultWithAgenticScope<>(null, "");
        }

        @Override
        public AgenticScope getAgenticScope(Object memoryId) {
            return null;
        }

        @Override
        public boolean evictAgenticScope(Object memoryId) {
            return false;
        }

        private static String lastOutput(AgenticScope scope) {
            if (scope == null || scope.agentInvocations() == null || scope.agentInvocations().isEmpty()) {
                return "";
            }
            return scope.agentInvocations().stream()
                    .map(AgentInvocation::output)
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(output -> !output.isBlank())
                    .reduce((previous, current) -> current)
                    .orElse("");
        }

        private static String safeName(String name) {
            String normalized = name != null ? name.toLowerCase().replaceAll("[^a-z0-9]+", "-") : "";
            normalized = normalized.replaceAll("^-+|-+$", "");
            return !normalized.isBlank() ? normalized : "agent";
        }
    }
}
