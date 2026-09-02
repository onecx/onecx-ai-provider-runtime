package org.tkit.onecx.ai.provider.runtime.rs.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.services.agent.RuntimeChatService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.provider.ProviderHealthService;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;
import org.tkit.quarkus.security.test.GenerateKeycloakClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.*;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthStatusDTO.StatusEnum;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@GenerateKeycloakClient(clientName = "testClient", scopes = { "ocx-agent:read", "ocx-agent:write" })
class RuntimeRestControllerTest extends AbstractTest {

    @Inject
    RuntimeRestController controller;

    @InjectMock
    RuntimeChatService runtimeChatService;

    @InjectMock
    ProviderHealthService providerHealthService;

    @InjectMock
    McpService mcpService;

    @Test
    void chat_delegatesToRuntimeChatService() {
        RuntimeChatRequestDTO request = chatRequest();
        RuntimeChatResponseDTO serviceResponse = new RuntimeChatResponseDTO();
        serviceResponse.setMessage("ok");

        when(runtimeChatService.chat(request)).thenReturn(serviceResponse);

        try (Response response = controller.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.getEntity()).isSameAs(serviceResponse);
        }

        verify(runtimeChatService).chat(request);
    }

    @Test
    void getProviderHealthStatus_delegatesToProviderHealthService() {
        ProviderHealthRequestDTO request = providerHealthRequest();
        ProviderHealthStatusDTO serviceResponse = new ProviderHealthStatusDTO();
        serviceResponse.setStatus(StatusEnum.HEALTHY);

        when(providerHealthService.getProviderHealthStatus(request)).thenReturn(serviceResponse);

        try (Response response = controller.getProviderHealthStatus(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.getEntity()).isSameAs(serviceResponse);
        }

        verify(providerHealthService).getProviderHealthStatus(request);
    }

    @Test
    void runtimeChatException_mapsRuntimeChatException() {
        RuntimeChatException ex = new RuntimeChatException("RUNTIME_CHAT_FAILED", "IllegalStateException",
                "boom", Response.Status.INTERNAL_SERVER_ERROR);

        try (RestResponse<ProblemDetailResponseDTO> response = controller.runtimeChatException(ex)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            ProblemDetailResponseDTO entity = response.getEntity();
            assertThat(entity.getErrorCode()).isEqualTo("RUNTIME_CHAT_FAILED");
            assertThat(entity.getDetail()).isEqualTo("boom");
            assertThat(entity.getParams()).hasSize(1);
            assertThat(entity.getParams().getFirst().getKey()).isEqualTo("errorType");
            assertThat(entity.getParams().getFirst().getValue()).isEqualTo("IllegalStateException");
        }
    }

    @Test
    void discoverTools_delegatesToMcpService() {
        ToolDiscoveryRequestDTO request = new ToolDiscoveryRequestDTO();
        request.setUrl("http://mcp");
        ToolDiscoveryResponseDTO serviceResponse = new ToolDiscoveryResponseDTO();
        serviceResponse.setTools(List.of());

        when(mcpService.discoverTools(request)).thenReturn(serviceResponse);

        try (Response response = controller.discoverTools(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            assertThat(response.getEntity()).isSameAs(serviceResponse);
        }

        verify(mcpService).discoverTools(request);
    }

    @Test
    void mcpDiscoveryException_mapsToBadGateway() {
        McpService.McpDiscoveryException ex = new McpService.McpDiscoveryException("discovery failed",
                new RuntimeException("cause"));

        try (RestResponse<ProblemDetailResponseDTO> response = controller.mcpDiscoveryException(ex)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_GATEWAY.getStatusCode());
            ProblemDetailResponseDTO entity = response.getEntity();
            assertThat(entity.getErrorCode()).isEqualTo("MCP_DISCOVERY_FAILED");
            assertThat(entity.getDetail()).isEqualTo("discovery failed");
        }
    }

    // ---- contract compatibility: pin the typed text-dispatch and provider-health shapes ----

    @Test
    void compatibility_textDispatch_requestAndResponseShapesRemainValid() throws Exception {
        // A valid typed text dispatch request built from the current DTO fields.
        RuntimeChatRequestDTO request = chatRequest();
        RuntimeChatResponseDTO serviceResponse = new RuntimeChatResponseDTO();
        serviceResponse.setMessage("dispatched ok");

        when(runtimeChatService.chat(request)).thenReturn(serviceResponse);

        try (Response response = controller.chat(request)) {
            // Existing clients see the HTTP success status the controller currently returns.
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());

            RuntimeChatResponseDTO body = (RuntimeChatResponseDTO) response.getEntity();
            assertThat(body).isNotNull();
            // The typed response field expected by existing clients remains present and correct.
            assertThat(body.getMessage()).isEqualTo("dispatched ok");

            // The serialized response JSON still carries the existing typed response field.
            String json = new ObjectMapper().writeValueAsString(body);
            assertThat(json).contains("message").contains("dispatched ok");
        }

        verify(runtimeChatService).chat(request);
    }

    @Test
    void compatibility_textDispatch_requestAndResponseSchemaFieldsRemainTyped() {
        RuntimeChatRequestDTO request = chatRequest();

        // Request schema fields remain present and typed as the contract declares.
        assertThat(request.getChatRequest()).isNotNull();
        assertThat(request.getChatRequest().getChatMessage()).isNotNull();
        assertThat(request.getChatRequest().getChatMessage().getType()).isEqualTo("USER");
        assertThat(request.getChatRequest().getChatMessage().getMessage()).isEqualTo("hello");
        assertThat(request.getRootAgent()).isNotNull();
        assertThat(request.getRootAgent().getName()).isEqualTo("agent");

        // Response schema: a typed string message survives the controller round-trip.
        RuntimeChatResponseDTO serviceResponse = new RuntimeChatResponseDTO();
        serviceResponse.setMessage("dispatched ok");
        when(runtimeChatService.chat(request)).thenReturn(serviceResponse);

        try (Response response = controller.chat(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            RuntimeChatResponseDTO body = (RuntimeChatResponseDTO) response.getEntity();
            assertThat(body).isNotNull();
            assertThat(body.getMessage()).isInstanceOf(String.class);
            assertThat(body.getMessage()).isEqualTo("dispatched ok");
        }
    }

    @Test
    void compatibility_providerHealth_requestAndResponseSchemaFieldsRemainTyped() {
        ProviderHealthRequestDTO request = providerHealthRequest();

        // Request schema fields remain present and typed as the contract declares.
        assertThat(request.getProvider()).isNotNull();
        assertThat(request.getProvider().getType()).isEqualTo("OPENAI");

        // Response schema: a typed status enum survives the controller round-trip.
        ProviderHealthStatusDTO serviceResponse = new ProviderHealthStatusDTO();
        serviceResponse.setStatus(StatusEnum.HEALTHY);
        when(providerHealthService.getProviderHealthStatus(request)).thenReturn(serviceResponse);

        try (Response response = controller.getProviderHealthStatus(request)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            ProviderHealthStatusDTO body = (ProviderHealthStatusDTO) response.getEntity();
            assertThat(body).isNotNull();
            assertThat(body.getStatus()).isInstanceOf(StatusEnum.class);
            assertThat(body.getStatus()).isEqualTo(StatusEnum.HEALTHY);
        }
    }

    private RuntimeChatRequestDTO chatRequest() {
        ChatMessageDTO message = new ChatMessageDTO();
        message.setType("USER");
        message.setMessage("hello");

        ChatRequestDTO chatRequest = new ChatRequestDTO();
        chatRequest.setChatMessage(message);

        AgentSnapshotDTO rootAgent = new AgentSnapshotDTO();
        rootAgent.setName("agent");

        RuntimeChatRequestDTO request = new RuntimeChatRequestDTO();
        request.setChatRequest(chatRequest);
        request.setRootAgent(rootAgent);
        return request;
    }

    private ProviderHealthRequestDTO providerHealthRequest() {
        ProviderSnapshotDTO provider = new ProviderSnapshotDTO();
        provider.setType("OPENAI");

        ProviderHealthRequestDTO request = new ProviderHealthRequestDTO();
        request.setProvider(provider);
        return request;
    }
}
