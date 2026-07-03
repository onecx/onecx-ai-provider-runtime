package org.tkit.onecx.ai.provider.runtime.rs.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.tkit.onecx.ai.provider.runtime.services.agent.RuntimeChatService;
import org.tkit.onecx.ai.provider.runtime.services.provider.ProviderHealthService;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.RuntimeInternalApi;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;

@ApplicationScoped
public class RuntimeRestController implements RuntimeInternalApi {

    @Inject
    RuntimeChatService runtimeChatService;

    @Inject
    ProviderHealthService providerHealthService;

    @Override
    public Response chat(RuntimeChatRequestDTO runtimeChatRequestDTO) {
        return Response.ok(runtimeChatService.chat(runtimeChatRequestDTO)).build();
    }

    @Override
    public Response getProviderHealthStatus(ProviderHealthRequestDTO providerHealthRequestDTO) {
        return Response.ok(providerHealthService.getProviderHealthStatus(providerHealthRequestDTO)).build();
    }
}
