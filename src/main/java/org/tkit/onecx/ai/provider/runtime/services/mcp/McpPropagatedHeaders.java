package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.vertx.ext.web.RoutingContext;

@ApplicationScoped
public class McpPropagatedHeaders {

    static final String APM_PRINCIPAL_TOKEN = "apm-principal-token";

    @Inject
    Instance<RoutingContext> routingContext;

    Map<String, String> currentHeaders() {
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
