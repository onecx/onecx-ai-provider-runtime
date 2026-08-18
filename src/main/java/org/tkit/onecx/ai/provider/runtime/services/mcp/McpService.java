package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.DiscoveredToolAnnotationsDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.DiscoveredToolDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolDiscoveryRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolDiscoveryResponseDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolRuleSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class McpService {

    @Inject
    DispatchConfig dispatchConfig;

    @Inject
    McpAuthHeaders mcpAuthHeaders;

    @Inject
    McpPropagatedHeaders mcpPropagatedHeaders;

    @ConfigProperty(name = "onecx.ai.tools.enforcement-enabled", defaultValue = "true")
    boolean enforcementEnabled;

    @ConfigProperty(name = "onecx.ai.tools.legacy-allow-all", defaultValue = "true")
    boolean legacyAllowAll;

    public McpToolRegistry createToolRegistry(AgentSnapshotDTO agent) {
        if (agent == null || agent.getTools() == null || agent.getTools().isEmpty()) {
            return McpToolRegistry.empty();
        }

        List<McpTool> allTools = new ArrayList<>();
        for (ToolSnapshotDTO tool : agent.getTools()) {
            if ("MCP".equals(safeString(tool.getType()))) {
                allTools.addAll(discoverToolsFromServer(tool));
            }
        }
        return new McpToolRegistry(allTools);
    }

    private List<McpTool> discoverToolsFromServer(ToolSnapshotDTO tool) {
        try {
            McpClient client = createMcpClient(tool);
            try {
                client.checkHealth();
                List<ToolSpecification> specs = filterByRules(tool, receiveToolSpecifications(client));
                if (specs.isEmpty()) {
                    closeQuietly(client);
                    return List.of();
                }
                Map<String, ToolRuleSnapshotDTO> ruleMap = rulesByName(tool);
                String executionPolicy = tool.getExecutionPolicy() != null ? tool.getExecutionPolicy().value()
                        : null;
                return specs.stream()
                        .map(spec -> {
                            ToolRuleSnapshotDTO rule = ruleMap.get(spec.name());
                            String allowed = rule != null && rule.getAllowed() != null
                                    ? rule.getAllowed().value()
                                    : null;
                            return new McpTool(tool.getName(), tool.getUrl(), spec, client,
                                    executionPolicy, allowed);
                        })
                        .toList();
            } catch (Exception ex) {
                closeQuietly(client);
                log.warn("MCP server not available {}: {}: {}", tool.getUrl(), ex.getClass().getSimpleName(),
                        ex.getMessage());
                log.debug("MCP server availability failure details for {}", tool.getUrl(), ex);
                return List.of();
            }
        } catch (Exception ex) {
            log.warn("Error discovering tools from {}: {}: {}", tool.getUrl(), ex.getClass().getSimpleName(),
                    ex.getMessage());
            log.debug("MCP tool discovery failure details for {}", tool.getUrl(), ex);
            return List.of();
        }
    }

    @Retry
    @Fallback(fallbackMethod = "receiveToolSpecificationsFallback")
    protected List<ToolSpecification> receiveToolSpecifications(McpClient client) {
        return client.listTools();
    }

    protected List<ToolSpecification> receiveToolSpecificationsFallback(McpClient client) {
        log.warn("Failed to receive MCP tool specifications after retries: {}",
                dispatchConfig.mcpConfig().maxToolExecutionRetries());
        return List.of();
    }

    protected McpClient createMcpClient(ToolSnapshotDTO tool) {
        var transportBuilder = StreamableHttpMcpTransport.builder()
                .url(tool.getUrl())
                .timeout(Duration.ofSeconds(dispatchConfig.mcpConfig().maxTimeout()))
                .logRequests(dispatchConfig.mcpConfig().logRequests())
                .logResponses(dispatchConfig.mcpConfig().logResponse());

        Map<String, String> propagatedHeaders = mcpPropagatedHeaders.currentHeaders();
        if (isOAuth2(tool)) {
            Map<String, String> authorizationHeaders = mcpAuthHeaders.authorizationHeaders(tool, propagatedHeaders);
            if (authorizationHeaders.isEmpty()) {
                throw new IllegalStateException("OAuth2 MCP authorization is not available");
            }
            transportBuilder.customHeaders(context -> {
                Map<String, String> currentPropagatedHeaders = mcpPropagatedHeaders.currentHeaders();
                Map<String, String> refreshedAuthorizationHeaders = mcpAuthHeaders.authorizationHeaders(tool,
                        currentPropagatedHeaders);
                return mergeHeaders(currentPropagatedHeaders,
                        refreshedAuthorizationHeaders.isEmpty() ? authorizationHeaders : refreshedAuthorizationHeaders);
            });
        } else if (!isBlank(tool.getApiKey())) {
            transportBuilder.customHeaders(mergeHeaders(propagatedHeaders, Map.of("Authorization", tool.getApiKey())));
        } else if (!propagatedHeaders.isEmpty()) {
            transportBuilder.customHeaders(propagatedHeaders);
        }

        return DefaultMcpClient.builder()
                .transport(transportBuilder.build())
                .build();
    }

    public ToolDiscoveryResponseDTO discoverTools(ToolDiscoveryRequestDTO request) {
        ToolSnapshotDTO tool = new ToolSnapshotDTO();
        tool.setName("discovery");
        tool.setUrl(request.getUrl());
        tool.setApiKey(request.getApiKey());
        tool.setAuthMode(request.getAuthMode());
        try (McpClient client = createMcpClient(tool)) {
            List<ToolSpecification> specs = receiveToolSpecifications(client);
            List<DiscoveredToolDTO> tools = specs.stream().map(spec -> {
                DiscoveredToolDTO dto = new DiscoveredToolDTO();
                dto.setName(spec.name());
                dto.setDescription(spec.description());
                dto.setAnnotations(toAnnotations(spec.metadata()));
                return dto;
            }).toList();
            ToolDiscoveryResponseDTO response = new ToolDiscoveryResponseDTO();
            response.setTools(tools);
            return response;
        } catch (Exception ex) {
            throw new McpDiscoveryException("Failed to discover tools from MCP server '" + request.getUrl() + "': "
                    + ex.getMessage(), ex);
        }
    }

    private List<ToolSpecification> filterByRules(ToolSnapshotDTO tool, List<ToolSpecification> specifications) {
        if (!enforcementEnabled) {
            return specifications;
        }
        List<ToolRuleSnapshotDTO> rules = tool.getToolRules();
        if (rules == null || rules.isEmpty()) {
            if (legacyAllowAll) {
                log.warn("MCP server '{}' has no tool rules configured — legacy allow-all in effect",
                        tool.getName());
                return specifications;
            }
            return List.of();
        }
        Map<String, ToolRuleSnapshotDTO> ruleMap = rulesByName(tool);
        return specifications.stream()
                .filter(spec -> {
                    ToolRuleSnapshotDTO rule = ruleMap.get(spec.name());
                    if (rule == null) {
                        log.info("Tool '{}' on MCP server '{}' has no rule — denied by default", spec.name(),
                                tool.getName());
                        return false;
                    }
                    return rule.getAllowed() == ToolRuleSnapshotDTO.AllowedEnum.ALLOW
                            || rule.getAllowed() == ToolRuleSnapshotDTO.AllowedEnum.ALWAYS_ASK;
                })
                .toList();
    }

    private Map<String, ToolRuleSnapshotDTO> rulesByName(ToolSnapshotDTO tool) {
        List<ToolRuleSnapshotDTO> rules = tool.getToolRules();
        if (rules == null || rules.isEmpty()) {
            return Map.of();
        }
        return rules.stream()
                .collect(Collectors.toMap(ToolRuleSnapshotDTO::getToolName, r -> r, (a, b) -> a));
    }

    private DiscoveredToolAnnotationsDTO toAnnotations(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        DiscoveredToolAnnotationsDTO annotations = new DiscoveredToolAnnotationsDTO();
        annotations.setReadOnlyHint(boolMeta(metadata, McpToolMetadataKeys.READ_ONLY_HINT));
        annotations.setDestructiveHint(boolMeta(metadata, McpToolMetadataKeys.DESTRUCTIVE_HINT));
        annotations.setIdempotentHint(boolMeta(metadata, McpToolMetadataKeys.IDEMPOTENT_HINT));
        annotations.setOpenWorldHint(boolMeta(metadata, McpToolMetadataKeys.OPEN_WORLD_HINT));
        if (annotations.getReadOnlyHint() == null && annotations.getDestructiveHint() == null
                && annotations.getIdempotentHint() == null && annotations.getOpenWorldHint() == null) {
            return null;
        }
        return annotations;
    }

    private Boolean boolMeta(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Boolean b) {
            return b ? Boolean.TRUE : null;
        }
        return null;
    }

    private void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (Exception ex) {
            log.debug("Failed to close MCP client", ex);
        }
    }

    public static class McpDiscoveryException extends RuntimeException {
        public McpDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isOAuth2(ToolSnapshotDTO tool) {
        return "OAUTH".equalsIgnoreCase(safeString(tool != null ? tool.getAuthMode() : null));
    }

    private Map<String, String> mergeHeaders(Map<String, String> first, Map<String, String> second) {
        if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        if (first != null) {
            headers.putAll(first);
        }
        if (second != null) {
            headers.putAll(second);
        }
        return Map.copyOf(headers);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
