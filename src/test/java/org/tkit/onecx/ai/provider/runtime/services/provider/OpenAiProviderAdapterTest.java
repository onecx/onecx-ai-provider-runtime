package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.agent;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.dispatchConfig;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class OpenAiProviderAdapterTest extends AbstractTest {

    @Inject
    OpenAiProviderAdapter adapter;

    @Test
    void supports_returnsTrueOnlyForOpenAi() {
        assertThat(adapter.supports("OPENAI")).isTrue();
        assertThat(adapter.supports("OLLAMA")).isFalse();
    }

    @Test
    void createChatModel_withValidAgent_returnsModel() {
        assertThat(adapter.createChatModel(agent("OPENAI", "http://localhost:8080/v1", "gpt-4o-mini", "sk-test")))
                .isNotNull();
    }

    @Test
    void createChatModel_clampsInvalidRetryConfiguration() {
        OpenAiProviderAdapter localAdapter = new OpenAiProviderAdapter();
        localAdapter.dispatchConfig = dispatchConfig(-1);
        assertThat(localAdapter.createChatModel(agent("OPENAI", null, "gpt-4o-mini", "sk-test"))).isNotNull();

        localAdapter.dispatchConfig = dispatchConfig((long) Integer.MAX_VALUE + 1L);
        assertThat(localAdapter.createChatModel(agent("OPENAI", null, "gpt-4o-mini", "sk-test"))).isNotNull();
    }

    @Test
    void createChatModel_withoutApiKey_failsClearly() {
        assertThatThrownBy(() -> adapter.createChatModel(agent("OPENAI", "http://localhost:8080/v1", "gpt-4o-mini", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OpenAI provider has no API key configured");
    }

    @Test
    void createChatModel_withoutModelIdentifier_failsClearly() {
        assertThatThrownBy(() -> adapter.createChatModel(agent("OPENAI", "http://localhost:8080/v1", "", "sk-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent model has no model identifier configured");
    }

    @Test
    void legacyConfigurationWarningIdentifiesModelWithoutLoggingConfigurationOrCredentials() {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(OpenAiModelConfig.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            var snapshot = agent("OPENAI", null, "gpt-4o\r\nWARNING forged", "sk-private");
            snapshot.getModel().setModelConfig("{\"unknown\":\"private-config\",\"apiKey\":\"sk-private\"}");
            adapter.createChatModel(snapshot);
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).contains("gpt-4o__WARNING forged")
                .doesNotContain("private-config", "sk-private", "\r", "\n");
    }

    @Test
    void isConfigured_requiresApiKey() {
        assertThat(adapter.isConfigured(provider("OPENAI", "http://localhost:8080/v1", "sk-test"))).isTrue();
        assertThat(adapter.isConfigured(provider("OPENAI", "http://localhost:8080/v1", ""))).isFalse();
        assertThat(adapter.isConfigured(provider("OPENAI", "http://localhost:8080/v1", null))).isFalse();
        assertThat(adapter.isConfigured(null)).isFalse();
    }
}
