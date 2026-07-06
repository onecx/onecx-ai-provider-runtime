package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.agent;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.dispatchConfig;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class OllamaProviderAdapterTest extends AbstractTest {

    OllamaProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new OllamaProviderAdapter();
        adapter.dispatchConfig = dispatchConfig();
    }

    @Test
    void supports_returnsTrueOnlyForOllama() {
        assertThat(adapter.supports("OLLAMA")).isTrue();
        assertThat(adapter.supports("OPENAI")).isFalse();
    }

    @Test
    void createChatModel_withValidAgent_returnsModel() {
        assertThat(adapter.createChatModel(agent("OLLAMA", "http://localhost:11434", "mistral", null))).isNotNull();
    }

    @Test
    void createChatModel_clampsInvalidRetryConfiguration() {
        adapter.dispatchConfig = dispatchConfig(-1);
        assertThat(adapter.createChatModel(agent("OLLAMA", "http://localhost:11434", "mistral", null))).isNotNull();

        adapter.dispatchConfig = dispatchConfig((long) Integer.MAX_VALUE + 1L);
        assertThat(adapter.createChatModel(agent("OLLAMA", "http://localhost:11434", "mistral", "Bearer token")))
                .isNotNull();
    }

    @Test
    void createChatModel_withoutLlmUrl_failsClearly() {
        assertThatThrownBy(() -> adapter.createChatModel(agent("OLLAMA", "", "mistral", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ollama provider has no LLM URL configured");
    }

    @Test
    void createChatModel_withoutModelIdentifier_failsClearly() {
        assertThatThrownBy(() -> adapter.createChatModel(agent("OLLAMA", "http://localhost:11434", "", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent model has no model identifier configured");
    }

    @Test
    void isConfigured_requiresProviderUrl() {
        assertThat(adapter.isConfigured(provider("OLLAMA", "http://localhost:11434", null))).isTrue();
        assertThat(adapter.isConfigured(provider("OLLAMA", "", null))).isFalse();
        assertThat(adapter.isConfigured(provider("OLLAMA", null, null))).isFalse();
        assertThat(adapter.isConfigured(null)).isFalse();
    }
}
