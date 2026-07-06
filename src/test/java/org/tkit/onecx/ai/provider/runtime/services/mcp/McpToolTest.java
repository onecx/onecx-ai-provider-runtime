package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class McpToolTest {

    @Test
    void execute_delegatesToClientAndReturnsResultText() {
        McpClient client = mock(McpClient.class);
        ToolExecutionRequest request = mock(ToolExecutionRequest.class);
        ToolExecutionResult result = mock(ToolExecutionResult.class);

        when(client.executeTool(request)).thenReturn(result);
        when(result.resultText()).thenReturn("tool-result");

        McpTool tool = new McpTool("tool-id", "http://mcp", toolSpec("tool-a"), client);

        String output = tool.execute(request);

        assertThat(output).isEqualTo("tool-result");
        verify(client).executeTool(request);
        verify(result).resultText();
    }

    private static ToolSpecification toolSpec(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description("desc")
                .parameters(JsonObjectSchema.builder().build())
                .build();
    }
}
