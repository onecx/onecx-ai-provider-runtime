package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.agent;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.dispatchConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class OpenAiTransportTest {

    @Inject
    ChatModelFactory factory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private OpenAiProviderAdapter adapter;
    private int status;
    private String response;
    private long delayMillis;

    @BeforeEach
    void startEndpoint() throws IOException {
        status = 200;
        response = """
                {"id":"chat-1","object":"chat.completion","model":"test","choices":[
                  {"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            requests.add(JSON.readTree(exchange.getRequestBody()));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        adapter = new OpenAiProviderAdapter();
        adapter.dispatchConfig = dispatchConfig(2);
    }

    @AfterEach
    void stopEndpoint() {
        server.stop(0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", " ", "{}", "{\"api\":\"chat-completions\"}",
            "{\"reasoningEffort\":\"  \"}", "legacy", "[]", "{\"api\":\"other\"}", "{\"api\":null}",
            "{\"unknown\":\"secret\"}", "{\"reasoningEffort\":null}", "{} {}",
            "{\"api\":\"chat-completions\",\"api\":\"responses\"}", "{\"reasoningEffort\":3}",
            "{\"api\":true}", "\"string\"", "null" })
    void defaultsAndLegacyUseChatCompletionsWithoutEffort(String config) {
        assertThat(model(config).chat("hello")).isEqualTo("done");
        assertThat(paths).containsExactly("/v1/chat/completions");
        assertThat(requests.getFirst().has("reasoning_effort")).isFalse();
        assertThat(requests.getFirst().has("temperature")).isFalse();
    }

    @Test
    void chatCompletionsTrimsConfiguredReasoningEffort() {
        model("{\"reasoningEffort\":\" medium \"}").chat("hello");
        assertThat(paths).containsExactly("/v1/chat/completions");
        assertThat(requests.getFirst().path("reasoning_effort").asText()).isEqualTo("medium");
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"api\":\"responses\"}", "{\"api\":\"responses\",\"reasoningEffort\":\" \"}",
            "{\"api\":\"responses\",\"reasoningEffort\":\" none \"}",
            "{\"api\":\"responses\",\"reasoningEffort\":\" medium \"}" })
    void responsesUsesSelectedPathAndReasoningEnvelope(String config) {
        response = responsesText();
        assertThat(model(config).chat("hello")).isEqualTo("done");
        assertThat(paths).containsExactly("/v1/responses");
        JsonNode payload = requests.getFirst();
        assertThat(payload.path("model").asText()).isEqualTo("test");
        assertThat(payload.path("input").isArray()).isTrue();
        assertThat(payload.has("temperature")).isFalse();
        assertThat(payload.has("top_p")).isFalse();
        assertThat(payload.has("reasoning_effort")).isFalse();
        if (config.contains("none")) {
            assertThat(payload.path("reasoning").path("effort").asText()).isEqualTo("none");
        } else if (config.contains("medium")) {
            assertThat(payload.path("reasoning").path("effort").asText()).isEqualTo("medium");
        } else {
            assertThat(payload.path("reasoning").has("effort")).isFalse();
        }
        if (config.contains("medium")) {
            assertThat(payload.path("include").toString()).contains("reasoning.encrypted_content");
        } else {
            assertThat(payload.has("include")).isFalse();
        }
        assertThat(payload.path("store").asBoolean(true)).isFalse();
    }

    @Test
    void oversizedConfigurationFailsExplicitlyBeforeAnyRequest() {
        assertThatThrownBy(() -> model("{\"reasoningEffort\":\"" + "x".repeat(256) + "\"}"))
                .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(400);
                    assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_CONFIG_INVALID");
                    assertThat(ex.getDetail()).contains("255");
                });
        assertThat(paths).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"api\":\"responses\"}", "{\"reasoningEffort\":\"medium\"}",
            "{\"api\":\"responses\",\"reasoningEffort\":\"medium\"}" })
    void endpointValidationRejectionIsSafeDistinctAndNotRetried(String config) {
        status = config.contains("reasoningEffort") ? 400 : 404;
        response = "{\"error\":{\"message\":\"secret provider body sk-private\",\"param\":\"reasoning_effort\"}}";
        assertThatThrownBy(() -> model(config).chat("hello"))
                .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(502);
                    assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_ENDPOINT_REJECTED");
                    assertThat(ex.getDetail()).isEqualTo("OpenAI endpoint rejected the request (HTTP " + status + ")");
                    assertThat(ex.getDetail()).doesNotContain("sk-private", "secret provider body");
                    assertThat(ex.getCause()).isNull();
                });
        assertThat(paths).hasSize(1);
        assertThat(paths.getFirst()).isEqualTo(config.contains("responses") ? "/v1/responses" : "/v1/chat/completions");
    }

    @Test
    void responsesServerFailuresAreSafeAndNotRetried() {
        status = 500;
        response = "{\"error\":{\"message\":\"sk-private\"}}";
        assertThatThrownBy(() -> model("{\"api\":\"responses\"}").chat("hello"))
                .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(502);
                    assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_PROVIDER_FAILED");
                    assertThat(ex.getDetail()).doesNotContain("sk-private");
                });
        assertThat(paths).containsExactly("/v1/responses");
    }

    @ParameterizedTest
    @ValueSource(ints = { 400, 404, 405, 415, 422, 501 })
    void selectedEndpointUnsupportedStatusesNeverRetry(int httpStatus) {
        status = httpStatus;
        response = "{\"error\":{\"message\":\"secret\"}}";
        for (String config : List.of("{}", "{\"api\":\"responses\"}")) {
            assertThatThrownBy(() -> model(config).chat("hello"))
                    .isInstanceOfSatisfying(RuntimeChatException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_ENDPOINT_REJECTED"));
        }
        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/responses");
    }

    @Test
    void chatCompletionsRetainsConfiguredRetriesForServerFailures() {
        status = 500;
        response = "{\"error\":{\"message\":\"secret\"}}";
        assertThatThrownBy(() -> model("{}").chat("hello")).isInstanceOf(RuntimeChatException.class);
        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/chat/completions", "/v1/chat/completions");
    }

    @Test
    void responsesRejectsAssistantImagesInsteadOfDroppingThem() {
        response = responsesText();
        var request = ChatRequest.builder().messages(
                AiMessage.builder().text("image").attributes(Map.of(AiMessage.GENERATED_IMAGES_KEY,
                        List.of(Image.builder().url("https://example.org/image").build())))
                        .build(),
                UserMessage.from("describe it")).build();
        assertThatThrownBy(() -> model("{\"api\":\"responses\"}").chat(request))
                .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_FEATURE_UNSUPPORTED");
                    assertThat(ex.getStatusCode()).isEqualTo(400);
                });
        assertThat(paths).isEmpty();
    }

    @Test
    void storageLimitAlsoAppliesToWhitespaceConfigurations() {
        assertThatThrownBy(() -> model(" ".repeat(256))).isInstanceOf(RuntimeChatException.class);
        assertThat(paths).isEmpty();
    }

    @Test
    void responsesHonorsConfiguredHttpTimeoutWithoutRetries() {
        delayMillis = 4000;
        response = responsesText();
        long start = System.nanoTime();
        assertThatThrownBy(() -> model("{\"api\":\"responses\"}").chat("hello"))
                .isInstanceOfSatisfying(RuntimeChatException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_PROVIDER_FAILED"));
        assertThat((System.nanoTime() - start) / 1_000_000).isBetween(500L, 3000L);
        assertThat(paths).containsExactly("/v1/responses");
    }

    @Test
    void configurationAtStorageLimitIsApplied() {
        String effort = "x".repeat(255 - "{\"reasoningEffort\":\"\"}".length());
        model("{\"reasoningEffort\":\"" + effort + "\"}").chat("hello");
        assertThat(requests.getFirst().path("reasoning_effort").asText()).isEqualTo(effort);
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 403 })
    void authenticationFailuresDoNotBecomeEndpointConfigurationRejectionsOrRetry(int httpStatus) {
        status = httpStatus;
        response = "{\"error\":{\"message\":\"secret sk-private\"}}";
        for (String config : List.of("{}", "{\"api\":\"responses\"}")) {
            assertThatThrownBy(() -> model(config).chat("hello"))
                    .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                        assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_PROVIDER_FAILED");
                        assertThat(ex.getDetail()).doesNotContain("secret", "sk-private");
                    });
        }
        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/responses");
    }

    @ParameterizedTest
    @ValueSource(strings = { "topK", "frequencyPenalty", "presencePenalty", "stopSequences" })
    void responsesRejectsSdkUnsupportedParametersBeforeHttp(String parameter) {
        var builder = ChatRequest.builder().messages(UserMessage.from("hello"));
        switch (parameter) {
            case "topK" -> builder.topK(1);
            case "frequencyPenalty" -> builder.frequencyPenalty(0.5);
            case "presencePenalty" -> builder.presencePenalty(0.5);
            case "stopSequences" -> builder.stopSequences(List.of("stop"));
            default -> throw new IllegalArgumentException(parameter);
        }
        assertThatThrownBy(() -> model("{\"api\":\"responses\"}").chat(builder.build()))
                .isInstanceOfSatisfying(RuntimeChatException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_FEATURE_UNSUPPORTED"));
        assertThat(paths).isEmpty();
    }

    @Test
    void responsesRejectsInvalidToolContinuationBeforeHttp() {
        var request = ChatRequest.builder().messages(
                AiMessage.from(ToolExecutionRequest.builder().name("lookup").arguments("{}").build()),
                UserMessage.from("continue")).build();
        assertThatThrownBy(() -> model("{\"api\":\"responses\"}").chat(request))
                .isInstanceOfSatisfying(RuntimeChatException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo("RUNTIME_MODEL_REQUEST_INVALID");
                    assertThat(ex.getStatusCode()).isEqualTo(400);
                });
        assertThat(paths).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "ANTHROPIC", "OLLAMA" })
    void otherProvidersIgnoreOpenAiSettingsAndKeepTheirRequestTransport(String providerType) {
        response = "ANTHROPIC".equals(providerType) ? """
                {"id":"msg-1","type":"message","role":"assistant","model":"test",
                 "content":[{"type":"text","text":"done"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """ : """
                {"model":"test","message":{"role":"assistant","content":"done"},"done":true,
                 "done_reason":"stop","prompt_eval_count":1,"eval_count":1}
                """;
        var snapshot = agent(providerType, "http://127.0.0.1:" + server.getAddress().getPort(), "test", "sk-test");
        for (String config : List.of("{\"api\":\"responses\",\"reasoningEffort\":\"medium\"}", "x".repeat(256))) {
            snapshot.getModel().setModelConfig(config);
            assertThat(factory.createChatModel(snapshot).chat("hello")).isEqualTo("done");
        }
        assertThat(paths).containsExactlyElementsOf(java.util.Collections.nCopies(2,
                "ANTHROPIC".equals(providerType) ? "/messages" : "/api/chat"));
        for (JsonNode request : requests) {
            assertThat(request.has("reasoning_effort")).isFalse();
            assertThat(request.has("reasoning")).isFalse();
        }
    }

    private ChatModel model(String config) {
        var snapshot = agent("OPENAI", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test", "sk-test");
        snapshot.getModel().setModelConfig(config);
        return adapter.createChatModel(snapshot);
    }

    private static String responsesText() {
        return """
                {"id":"resp-1","object":"response","model":"test","status":"completed","output":[
                {"id":"msg-1","type":"message","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":"done","annotations":[]}]}],
                 "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
                """;
    }
}
