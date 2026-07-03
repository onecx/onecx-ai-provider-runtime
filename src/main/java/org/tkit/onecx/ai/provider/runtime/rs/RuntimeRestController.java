package org.tkit.onecx.ai.provider.runtime.rs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.tkit.onecx.ai.provider.runtime.services.RuntimeChatService;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.RuntimeInternalApi;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;

@ApplicationScoped
public class RuntimeRestController implements RuntimeInternalApi {

    @Inject
    RuntimeChatService runtimeChatService;

    @Override
    public Response chat(RuntimeChatRequestDTO runtimeChatRequestDTO) {
        return Response.ok(runtimeChatService.chat(runtimeChatRequestDTO)).build();
    }
}
