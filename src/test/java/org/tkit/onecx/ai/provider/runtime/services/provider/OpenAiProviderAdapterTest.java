package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.agent;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.provider;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;


import io.quarkus.test.junit.QuarkusTest;
class OpenAiProviderAdapterTest {

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
    void isConfigured_requiresApiKey() {
        assertThat(adapter.isConfigured(provider("OPENAI", "http://localhost:8080/v1", "sk-test"))).isTrue();
        assertThat(adapter.isConfigured(provider("OPENAI", "http://localhost:8080/v1", ""))).isFalse();
        assertThat(adapter.isConfigured(provider("OPENAI", "http://localhost:8080/v1", null))).isFalse();
        assertThat(adapter.isConfigured(null)).isFalse();
    }
}
