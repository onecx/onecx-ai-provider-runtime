package org.tkit.onecx.ai.provider.runtime.services.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;

public record McpTool(
        String serverName,
        String serverUrl,
        ToolSpecification toolSpecification,
        McpClient client,
        String executionPolicy,
        String allowed) {

    public McpTool(String serverName, String serverUrl, ToolSpecification toolSpecification, McpClient client) {
        this(serverName, serverUrl, toolSpecification, client, null, null);
    }

    public String execute(ToolExecutionRequest request) {
        ToolExecutionResult result = client.executeTool(request);
        return result.resultText();
    }
}
