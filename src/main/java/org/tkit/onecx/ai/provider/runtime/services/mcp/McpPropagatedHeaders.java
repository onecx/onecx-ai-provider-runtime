package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.util.Map;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.vertx.ext.web.RoutingContext;

/**
 * Request-scoped snapshot of the headers that need to be propagated to MCP servers.
 *
 * <p>
 * The Vert.x {@link RoutingContext} is bound to the original request thread and is <b>not</b>
 * propagated by {@link org.eclipse.microprofile.context.ManagedExecutor}. Because
 * {@code RuntimeChatService.chat} dispatches the actual invocation to a managed executor
 * ({@code CompletableFuture.supplyAsync}), reading the incoming headers directly from
 * {@code RoutingContext} on the async thread fails and the {@code apm-principal-token}
 * is silently lost.
 *
 * <p>
 * This bean captures the headers eagerly on the request thread (via
 * {@link #snapshot()}) and caches them for the whole request. The CDI request scope
 * <em>is</em> propagated by {@code ManagedExecutor}, so the cached snapshot is
 * transparently visible from the async thread and from the langchain4j tool-executor
 * thread when it later resolves this bean via CDI.
 */
@RequestScoped
public class McpPropagatedHeaders {

    static final String APM_PRINCIPAL_TOKEN = "apm-principal-token";

    @Inject
    Instance<RoutingContext> routingContext;

    private Map<String, String> cached;

    /**
     * Forces the propagated headers to be captured (on the caller's thread).
     * Must be invoked on the original request thread so the Vert.x {@link RoutingContext}
     * is still accessible. Subsequent calls to {@link #currentHeaders()} — including from
     * any thread that has the CDI request scope propagated — will return the cached snapshot.
     */
    public void snapshot() {
        if (cached == null) {
            cached = readFromRoutingContext();
        }
    }

    Map<String, String> currentHeaders() {
        if (cached != null) {
            return cached;
        }
        // Fallback: try to read directly from RoutingContext.
        // Works only if we still are on the original request thread (e.g. in unit tests or synchronous code paths).
        Map<String, String> headers = readFromRoutingContext();
        cached = headers;
        return headers;
    }

    private Map<String, String> readFromRoutingContext() {
        try {
            if (routingContext == null || routingContext.isUnsatisfied()) {
                return Map.of();
            }
            String token = routingContext.get().request().getHeader(APM_PRINCIPAL_TOKEN);
            if (isBlank(token)) {
                return Map.of();
            }
            return Map.of(APM_PRINCIPAL_TOKEN, token);
        } catch (ContextNotActiveException | IllegalStateException ex) {
            return Map.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
