package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
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
                List<ToolSpecification> specs = receiveToolSpecifications(client);
                return specs.stream()
                        .map(spec -> new McpTool(tool.getName(), tool.getUrl(), spec, client))
                        .toList();
            } catch (Exception ex) {
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
            transportBuilder.customHeaders(context -> mergeHeaders(propagatedHeaders,
                    mcpAuthHeaders.authorizationHeaders(tool, propagatedHeaders)));
        } else if (!isBlank(tool.getApiKey())) {
            transportBuilder.customHeaders(mergeHeaders(propagatedHeaders, Map.of("Authorization", tool.getApiKey())));
        } else if (!propagatedHeaders.isEmpty()) {
            transportBuilder.customHeaders(propagatedHeaders);
        }

        return DefaultMcpClient.builder()
                .transport(transportBuilder.build())
                .build();
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isOAuth2(ToolSnapshotDTO tool) {
        return "OAUTH2".equalsIgnoreCase(safeString(tool != null ? tool.getAuthMode() : null));
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
