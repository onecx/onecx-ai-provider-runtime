package org.tkit.onecx.ai.provider.runtime.services.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

public record McpTool(
        String serverName,
        String serverUrl,
        ToolSpecification toolSpecification,
        McpClient client) {

    public String execute(ToolExecutionRequest request) {
        ToolExecutionResult result = client.executeTool(request);
        return result.resultText();
    }
}
