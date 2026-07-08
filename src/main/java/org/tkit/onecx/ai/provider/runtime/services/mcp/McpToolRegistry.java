package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.util.List;

import dev.langchain4j.agent.tool.ToolSpecification;

public record McpToolRegistry(List<McpTool> tools) implements AutoCloseable {

    public static McpToolRegistry empty() {
        return new McpToolRegistry(List.of());
    }

    public List<ToolSpecification> getToolSpecifications() {
        return tools.stream().map(McpTool::toolSpecification).toList();
    }

    @Override
    public void close() {
        for (McpTool tool : tools) {
            try {
                tool.client().close();
            } catch (Exception ignored) {
                // Best-effort MCP client cleanup.
            }
        }
    }
}
