package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.http.HttpServerRequest;
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

    @ConfigProperty(name = "tkit.rs.context.token.header-param")
    String principalTokenHeader;

    @ConfigProperty(name = "onecx.user-authorization-header")
    String userAuthorizationHeader;

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
            HttpServerRequest request = routingContext.get().request();
            java.util.LinkedHashMap<String, String> propagated = new java.util.LinkedHashMap<>();
            String principalToken = request.getHeader(principalTokenHeader);
            if (!isBlank(principalToken)) {
                propagated.put(principalTokenHeader, principalToken);
            }
            String userAuthorization = request.getHeader(userAuthorizationHeader);
            if (!isBlank(userAuthorization)) {
                propagated.put(userAuthorizationHeader, userAuthorization);
            }

            if (propagated.isEmpty()) {
                return Map.of();
            }

            return Map.copyOf(propagated);
        } catch (ContextNotActiveException | IllegalStateException ex) {
            return Map.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
