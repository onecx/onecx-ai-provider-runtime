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
                        .map(spec -> new McpTool(tool.getId(), tool.getUrl(), spec, client))
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

        if (!isBlank(tool.getApiKey())) {
            transportBuilder.customHeaders(Map.of("Authorization", tool.getApiKey()));
        }

        return DefaultMcpClient.builder()
                .transport(transportBuilder.build())
                .build();
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
