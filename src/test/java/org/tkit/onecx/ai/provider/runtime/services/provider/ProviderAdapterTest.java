package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.langchain4j.model.chat.ChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ProviderAdapterTest {

    private final ProviderAdapter adapter = new ProviderAdapter() {

        @Override
        public boolean supports(String type) {
            return false;
        }

        @Override
        public ChatModel createChatModel(AgentSnapshotDTO agent) {
            return null;
        }
    };

    @Test
    void defaultIsConfigured_requiresProviderType() {
        ProviderSnapshotDTO provider = new ProviderSnapshotDTO();
        provider.setType("OPENAI");

        assertThat(adapter.isConfigured(provider)).isTrue();
        assertThat(adapter.isConfigured(new ProviderSnapshotDTO())).isFalse();
        assertThat(adapter.isConfigured(null)).isFalse();
    }

    @Test
    void defaultHelpersHandleNullAndBlankValues() {
        ProviderSnapshotDTO provider = new ProviderSnapshotDTO();
        provider.setType("OLLAMA");

        assertThat(adapter.providerType(provider)).isEqualTo("OLLAMA");
        assertThat(adapter.providerType(null)).isNull();
        assertThat(adapter.isBlank(null)).isTrue();
        assertThat(adapter.isBlank("   ")).isTrue();
        assertThat(adapter.isBlank("value")).isFalse();
    }
}
