package dev.langchain4j.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies that the MCP tool {@code _meta} from {@code tools/list} reaches {@code ToolSpecification.metadata()}
 * unchanged, which the per-tool permission gate depends on.
 */
class ToolSpecificationMetaTest {

    @Test
    void toolSpecificationListFromMcpResponse_copiesMetaIntoMetadata() throws Exception {
        var tools = new ObjectMapper().readTree("""
                [
                  {
                    "name": "addLocationToEventMCPv1",
                    "description": "Adds a location",
                    "inputSchema": {"type": "object", "properties": {}},
                    "_meta": {"requiredPermission": "onecx:event#mcp-write"}
                  },
                  {
                    "name": "ping",
                    "description": "Ping",
                    "inputSchema": {"type": "object", "properties": {}}
                  }
                ]
                """);

        var specs = ToolSpecificationHelper.toolSpecificationListFromMcpResponse(
                (com.fasterxml.jackson.databind.node.ArrayNode) tools);

        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).metadata()).containsEntry("requiredPermission", "onecx:event#mcp-write");
        assertThat(specs.get(1).metadata()).doesNotContainKey("requiredPermission");
    }
}
