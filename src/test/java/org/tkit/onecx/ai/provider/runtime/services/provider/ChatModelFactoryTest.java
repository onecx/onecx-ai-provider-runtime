package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.agent;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;

import dev.langchain4j.model.chat.ChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChatModelFactoryTest extends AbstractTest {

    FakeProviderAdapter ollamaAdapter;
    ChatModelFactory factory;

    @BeforeEach
    void setUp() {
        ollamaAdapter = new FakeProviderAdapter("OLLAMA", chatModel());

        factory = new ChatModelFactory();
        factory.providerAdapters = instance(List.of(ollamaAdapter));
    }

    @Test
    void createChatModel_delegatesToMatchingAdapter() {
        var agent = agent("OLLAMA", "http://localhost:11434", "mistral", null);

        assertThat(factory.createChatModel(agent)).isSameAs(ollamaAdapter.chatModel);
        assertThat(ollamaAdapter.request).isSameAs(agent);
    }

    @Test
    void createChatModel_unsupportedProvider_failsClearly() {
        assertThatThrownBy(() -> factory.createChatModel(agent("OPENAI", null, "gpt-4o-mini", "sk-test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provider type not supported by current runtime: OPENAI");
    }

    @Test
    void createChatModel_withoutProvider_failsClearly() {
        assertThatThrownBy(() -> factory.createChatModel(new AgentSnapshotDTO()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent has no associated model or provider");
    }

    private static ChatModel chatModel() {
        return (ChatModel) Proxy.newProxyInstance(ChatModel.class.getClassLoader(), new Class[] { ChatModel.class },
                (_, _, _) -> null);
    }

    private static Instance<ProviderAdapter> instance(List<ProviderAdapter> adapters) {
        return new Instance<>() {
            @Override
            public Iterator<ProviderAdapter> iterator() {
                return adapters.iterator();
            }

            @Override
            public ProviderAdapter get() {
                return adapters.getFirst();
            }

            @Override
            public Instance<ProviderAdapter> select(TypeLiteral subtype, Annotation... qualifiers) {
                return this;
            }

            @Override
            public Instance<ProviderAdapter> select(Class subtype, Annotation... qualifiers) {
                return this;
            }

            @Override
            public Instance<ProviderAdapter> select(Annotation... qualifiers) {
                return this;
            }

            @Override
            public boolean isUnsatisfied() {
                return adapters.isEmpty();
            }

            @Override
            public boolean isAmbiguous() {
                return adapters.size() > 1;
            }

            @Override
            public void destroy(ProviderAdapter instance) {
            }

            @Override
            public Handle<ProviderAdapter> getHandle() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterable<? extends Handle<ProviderAdapter>> handles() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static class FakeProviderAdapter implements ProviderAdapter {

        private final String supportedType;
        private final ChatModel chatModel;
        private AgentSnapshotDTO request;

        private FakeProviderAdapter(String supportedType, ChatModel chatModel) {
            this.supportedType = supportedType;
            this.chatModel = chatModel;
        }

        @Override
        public boolean supports(String type) {
            return supportedType.equals(type);
        }

        @Override
        public ChatModel createChatModel(AgentSnapshotDTO agent) {
            request = agent;
            return chatModel;
        }
    }
}
