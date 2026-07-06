package org.tkit.onecx.ai.provider.runtime.rs.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.services.agent.RuntimeChatService;
import org.tkit.onecx.ai.provider.runtime.services.provider.ProviderHealthService;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatMessageDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthStatusDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthStatusDTO.StatusEnum;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatResponseDTO;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeRestControllerTest extends AbstractTest {

    @Inject
    RuntimeRestController controller;

    @InjectMock
    RuntimeChatService runtimeChatService;

    @InjectMock
    ProviderHealthService providerHealthService;

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
