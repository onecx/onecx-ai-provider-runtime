package org.tkit.onecx.ai.provider.runtime.rs.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;
import org.tkit.onecx.ai.provider.runtime.rs.mappers.ExceptionMapper;
import org.tkit.onecx.ai.provider.runtime.services.agent.RuntimeChatService;
import org.tkit.onecx.ai.provider.runtime.services.mcp.McpService;
import org.tkit.onecx.ai.provider.runtime.services.provider.ProviderHealthService;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.RuntimeInternalApi;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProblemDetailResponseDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RuntimeChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolDiscoveryRequestDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class RuntimeRestController implements RuntimeInternalApi {

    @Inject
    RuntimeChatService runtimeChatService;

    @Inject
    ProviderHealthService providerHealthService;

    @Inject
    McpService mcpService;

    @Inject
    ExceptionMapper exceptionMapper;

    @Override
    public Response chat(RuntimeChatRequestDTO runtimeChatRequestDTO) {
        return Response.ok(runtimeChatService.chat(runtimeChatRequestDTO)).build();
    }

    @Override
    public Response getProviderHealthStatus(ProviderHealthRequestDTO providerHealthRequestDTO) {
        return Response.ok(providerHealthService.getProviderHealthStatus(providerHealthRequestDTO)).build();
    }

    @Override
    public Response discoverTools(ToolDiscoveryRequestDTO toolDiscoveryRequestDTO) {
        return Response.ok(mcpService.discoverTools(toolDiscoveryRequestDTO)).build();
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> mcpDiscoveryException(McpService.McpDiscoveryException ex) {
        return exceptionMapper.mcpDiscovery(ex);
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> constraint(ConstraintViolationException ex) {
        return exceptionMapper.constraint(ex);
    }

    @ServerExceptionMapper
    public RestResponse<ProblemDetailResponseDTO> runtimeChatException(RuntimeChatException ex) {
        log.error("Runtime chat failed: errorCode={}, errorType={}, detail={}", ex.getErrorCode(), ex.getErrorType(),
                ex.getDetail(), ex);
        return exceptionMapper.runtimeChat(ex);
    }
}
