package org.tkit.onecx.ai.provider.runtime.rs.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.rs.mappers.ExceptionMapperImpl;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProblemDetailResponseDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolDiscoveryRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolDiscoveryResponseDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeRestControllerUnitTest {

    @Test
    void discoverTools_delegatesToMcpService() {
        var controller = new RuntimeRestController();
        controller.mcpService = mock(McpService.class);
        controller.exceptionMapper = new ExceptionMapperImpl();

        ToolDiscoveryRequestDTO request = new ToolDiscoveryRequestDTO();
        request.setUrl("http://mcp");
        ToolDiscoveryResponseDTO serviceResponse = new ToolDiscoveryResponseDTO();
        serviceResponse.setTools(List.of());

        when(controller.mcpService.discoverTools(request)).thenReturn(serviceResponse);

        try (Response response = controller.discoverTools(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.getEntity()).isSameAs(serviceResponse);
        }

        verify(controller.mcpService).discoverTools(request);
    }

    @Test
    void mcpDiscoveryException_mapsToBadGateway() {
        var controller = new RuntimeRestController();
        controller.exceptionMapper = new ExceptionMapperImpl();

        McpService.McpDiscoveryException ex = new McpService.McpDiscoveryException("discovery failed",
                new RuntimeException("cause"));

        try (RestResponse<ProblemDetailResponseDTO> response = controller.mcpDiscoveryException(ex)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_GATEWAY.getStatusCode());
            ProblemDetailResponseDTO entity = response.getEntity();
            assertThat(entity.getErrorCode()).isEqualTo("MCP_DISCOVERY_FAILED");
            assertThat(entity.getDetail()).isEqualTo("discovery failed");
        }
    }
}
