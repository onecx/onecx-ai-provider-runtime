package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.vertx.ext.web.RoutingContext;

/**
 * Captures headers that must be forwarded to MCP servers (e.g. {@code apm-principal-token}).
 *
 * <p>
 * This bean is {@link ApplicationScoped} and must <strong>only</strong> be called from the
 * original HTTP request thread (before any {@code CompletableFuture.supplyAsync} dispatch).
 * Callers are responsible for capturing the returned {@code Map} and passing it explicitly
 * through the async call-chain — the Vert.x {@link RoutingContext} is bound to the event-loop
 * thread and is not accessible from worker / managed-executor threads.
 */
@ApplicationScoped
public class McpPropagatedHeaders {

    static final String APM_PRINCIPAL_TOKEN = "apm-principal-token";

    @Inject
    Instance<RoutingContext> routingContext;

    /**
     * Returns a snapshot of the propagated headers, reading from the current
     * {@link RoutingContext}. Must be called on the original request thread.
     */
    public Map<String, String> currentHeaders() {
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
