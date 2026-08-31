package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.context.ContextNotActiveException;
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

    @Test
    void currentHeaders_returnsEmpty_whenRoutingContextNull() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        headers.routingContext = null;

        assertThat(headers.currentHeaders()).isEmpty();
    }

    @Test
    void currentHeaders_returnsEmpty_whenRoutingContextUnsatisfied() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        @SuppressWarnings("unchecked")
        Instance<RoutingContext> unsatisfied = mock(Instance.class);
        when(unsatisfied.isUnsatisfied()).thenReturn(true);
        headers.routingContext = unsatisfied;

        assertThat(headers.currentHeaders()).isEmpty();
    }

    @Test
    void currentHeaders_returnsEmpty_whenRoutingContextThrowsContextNotActiveException() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        @SuppressWarnings("unchecked")
        Instance<RoutingContext> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenThrow(new ContextNotActiveException("Context not active"));
        headers.routingContext = instance;

        assertThat(headers.currentHeaders()).isEmpty();
    }

    @Test
    void currentHeaders_returnsEmpty_whenRoutingContextThrowsIllegalStateException() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        @SuppressWarnings("unchecked")
        Instance<RoutingContext> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenThrow(new IllegalStateException("Illegal state"));
        headers.routingContext = instance;

        assertThat(headers.currentHeaders()).isEmpty();
    }

    @Test
    void snapshot_cachesHeaders_evenAfterRoutingContextBecomesUnavailable() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        headers.routingContext = routingContext("principal-token");

        headers.snapshot();

        // Simulate loss of RoutingContext (e.g. we moved to a ManagedExecutor thread).
        @SuppressWarnings("unchecked")
        Instance<RoutingContext> unavailable = mock(Instance.class);
        when(unavailable.isUnsatisfied()).thenReturn(true);
        headers.routingContext = unavailable;

        assertThat(headers.currentHeaders()).containsEntry("apm-principal-token", "principal-token");
    }

    @Test
    void snapshot_cachesCachedValue_onMultipleCalls() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        headers.routingContext = routingContext("principal-token");

        // First call captures
        headers.snapshot();
        assertThat(headers.currentHeaders()).containsEntry("apm-principal-token", "principal-token");

        // Change routing context to unsatisfied
        @SuppressWarnings("unchecked")
        Instance<RoutingContext> unavailable = mock(Instance.class);
        when(unavailable.isUnsatisfied()).thenReturn(true);
        headers.routingContext = unavailable;

        // Second snapshot call should not re-read from routing context
        headers.snapshot();
        assertThat(headers.currentHeaders()).containsEntry("apm-principal-token", "principal-token");
    }

    @Test
    void snapshot_cachesEmpty_whenHeaderMissing() {
        McpPropagatedHeaders headers = new McpPropagatedHeaders();
        headers.routingContext = routingContext(null);

        headers.snapshot();

        // Even with a new routing context available, cache should be used
        headers.routingContext = routingContext("new-token");

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
