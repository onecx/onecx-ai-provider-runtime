package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

@QuarkusTest
class McpPropagatedHeadersTest {

    @Test
    void currentHeaders_returnsApmPrincipalTokenFromCurrentRequest() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        headers.routingContext = routingContext("principal-token");

        assertThat(headers.currentHeaders()).containsEntry("apm-principal-token", "principal-token");
    }

    @Test
    void currentHeaders_returnsEmpty_whenApmPrincipalTokenMissing() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        headers.routingContext = routingContext(null);

        assertThat(headers.currentHeaders()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Instance<RoutingContext> routingContext(String token) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader("apm-principal-token")).thenReturn(token);
        RoutingContext context = mock(RoutingContext.class);
        when(context.request()).thenReturn(request);
        Instance<RoutingContext> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(context);
        return instance;
    }
}
