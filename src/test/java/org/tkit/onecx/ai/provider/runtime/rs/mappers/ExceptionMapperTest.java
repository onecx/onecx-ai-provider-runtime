package org.tkit.onecx.ai.provider.runtime.rs.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ExceptionMapperTest {

    @Test
    void mcpDiscovery_returnsBadGatewayWithErrorCodeAndMessage() {
        var mapper = new ExceptionMapperImpl();
        var ex = new McpService.McpDiscoveryException("Failed to discover tools from MCP server 'http://mcp': error",
                new RuntimeException("cause"));

        var response = mapper.mcpDiscovery(ex);

        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_GATEWAY.getStatusCode());
        assertThat(response.getEntity()).isNotNull();
        assertThat(response.getEntity().getErrorCode()).isEqualTo("MCP_DISCOVERY_FAILED");
        assertThat(response.getEntity().getDetail()).contains("Failed to discover tools");
        assertThat(response.getEntity().getDetail()).contains("http://mcp");
    }
}
