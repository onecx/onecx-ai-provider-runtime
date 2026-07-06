package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.agent;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.dispatchConfig;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.provider;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AnthropicProviderAdapterTest {

    @Inject
    AnthropicProviderAdapter adapter;

    @Test
    void supports_returnsTrueOnlyForAnthropic() {
        assertThat(adapter.supports("ANTHROPIC")).isTrue();
        assertThat(adapter.supports("OPENAI")).isFalse();
        assertThat(adapter.supports("OLLAMA")).isFalse();
    }

    @Test
    void createChatModel_withValidAgent_returnsModel() {
        assertThat(adapter
                .createChatModel(agent("ANTHROPIC", "http://localhost:8080", "claude-3-haiku-20240307", "sk-ant-test")))
                .isNotNull();
    }

    @Test
    void createChatModel_clampsInvalidRetryConfiguration() {
        AnthropicProviderAdapter localAdapter = new AnthropicProviderAdapter();
        localAdapter.dispatchConfig = dispatchConfig(-1);
        assertThat(localAdapter.createChatModel(agent("ANTHROPIC", null, "claude-3-haiku-20240307", "sk-ant-test")))
                .isNotNull();

        localAdapter.dispatchConfig = dispatchConfig((long) Integer.MAX_VALUE + 1L);
        assertThat(localAdapter.createChatModel(agent("ANTHROPIC", null, "claude-3-haiku-20240307", "sk-ant-test")))
                .isNotNull();
    }

    @Test
    void createChatModel_withoutApiKey_failsClearly() {
        assertThatThrownBy(() -> adapter
                .createChatModel(agent("ANTHROPIC", "http://localhost:8080", "claude-3-haiku-20240307", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Anthropic provider has no API key configured");
    }

    @Test
    void createChatModel_withoutModelIdentifier_failsClearly() {
        assertThatThrownBy(() -> adapter.createChatModel(agent("ANTHROPIC", "http://localhost:8080", "", "sk-ant-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent model has no model identifier configured");
    }

    @Test
    void isConfigured_requiresApiKey() {
        assertThat(adapter.isConfigured(provider("ANTHROPIC", "http://localhost:8080", "sk-ant-test"))).isTrue();
        assertThat(adapter.isConfigured(provider("ANTHROPIC", "http://localhost:8080", ""))).isFalse();
        assertThat(adapter.isConfigured(provider("ANTHROPIC", "http://localhost:8080", null))).isFalse();
        assertThat(adapter.isConfigured(null)).isFalse();
    }
}
