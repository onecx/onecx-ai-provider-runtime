package org.tkit.onecx.ai.provider.runtime.services.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ExternalAgentDiscoveryServiceTest {

    private ExternalAgentDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new ExternalAgentDiscoveryService();
        service.objectMapper = new ObjectMapper();
    }

    @Test
    void fetchAgentCard_nullOrBlankUrl_returnsNull() {
        assertThat(service.fetchAgentCard(null)).isNull();
        assertThat(service.fetchAgentCard("")).isNull();
        assertThat(service.fetchAgentCard("   ")).isNull();
    }

    @Test
    void fetchAgentCard_readsUrlFieldFromDiscoveryDocument() throws Exception {
        try (TestHttpServer server = TestHttpServer.withJson("""
                {
                  "name": "remote-bot",
                  "description": "A remote agent",
                  "url": "http://remote-agent/tasks"
                }
                """)) {
            AgentCard card = service.fetchAgentCard(server.url());

            assertThat(card.name()).isEqualTo("remote-bot");
            assertThat(card.description()).isEqualTo("A remote agent");
            assertThat(card.url()).isEqualTo("http://remote-agent/tasks");
            assertThat(card.hasInvokeUrl()).isTrue();
        }
    }

    @Test
    void fetchAgentCard_usesEndpointThenDiscoveryUrlFallback() throws Exception {
        try (TestHttpServer endpointServer = TestHttpServer.withJson("""
                { "endpoint": "http://remote-agent/invoke" }
                """);
                TestHttpServer fallbackServer = TestHttpServer.withJson("""
                        { "name": "fallback-agent" }
                        """)) {
            assertThat(service.fetchAgentCard(endpointServer.url()).url()).isEqualTo("http://remote-agent/invoke");
            assertThat(service.fetchAgentCard(fallbackServer.url()).url()).isEqualTo(fallbackServer.url());
        }
    }

    @Test
    void fetchAgentCard_invalidOrUnreachableUrl_returnsNull() {
        assertThat(service.fetchAgentCard("not a uri")).isNull();
        assertThat(service.fetchAgentCard("http://127.0.0.1:9/agent.json")).isNull();
    }

    @Test
    void agentCard_hasInvokeUrlRequiresNonBlankUrl() {
        assertThat(new AgentCard("agent", "desc", "http://agent/tasks").hasInvokeUrl()).isTrue();
        assertThat(new AgentCard("agent", "desc", null).hasInvokeUrl()).isFalse();
        assertThat(new AgentCard("agent", "desc", "  ").hasInvokeUrl()).isFalse();
    }

    private static final class TestHttpServer implements AutoCloseable {

        private final HttpServer server;

        private TestHttpServer(HttpServer server) {
            this.server = server;
        }

        static TestHttpServer withJson(String json) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/agent.json", exchange -> {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            return new TestHttpServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/agent.json";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
