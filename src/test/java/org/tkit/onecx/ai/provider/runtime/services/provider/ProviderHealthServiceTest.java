package org.tkit.onecx.ai.provider.runtime.services.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.tkit.onecx.ai.provider.runtime.services.provider.ProviderAdapterTestSupport.provider;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tkit.onecx.ai.provider.runtime.test.AbstractTest;

import dev.langchain4j.model.chat.ChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthStatusDTO.StatusEnum;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ProviderHealthServiceTest extends AbstractTest {

    ProviderHealthService service;

    @BeforeEach
    void setUp() {
        service = new ProviderHealthService();
    }

    @Test
    void getProviderHealthStatus_matchingConfiguredAdapter_returnsHealthy() {
        service.providerAdapters = instance(List.of(new FakeProviderAdapter("OLLAMA", true)));

        assertThat(service.getProviderHealthStatus(request(provider("OLLAMA", "http://localhost:11434", null)))
                .getStatus()).isEqualTo(StatusEnum.HEALTHY);
    }

    @Test
    void getProviderHealthStatus_matchingUnconfiguredAdapter_returnsUnhealthy() {
        service.providerAdapters = instance(List.of(new FakeProviderAdapter("OLLAMA", false)));

        assertThat(service.getProviderHealthStatus(request(provider("OLLAMA", null, null))).getStatus())
                .isEqualTo(StatusEnum.UNHEALTHY);
    }

    @Test
    void getProviderHealthStatus_unsupportedProvider_returnsUnhealthy() {
        service.providerAdapters = instance(List.of(new FakeProviderAdapter("OLLAMA", true)));

        assertThat(service.getProviderHealthStatus(request(provider("OPENAI", null, "sk-test"))).getStatus())
                .isEqualTo(StatusEnum.UNHEALTHY);
    }

    @Test
    void getProviderHealthStatus_missingProvider_returnsUnhealthy() {
        service.providerAdapters = instance(List.of(new FakeProviderAdapter("OLLAMA", true)));

        assertThat(service.getProviderHealthStatus(request(null)).getStatus()).isEqualTo(StatusEnum.UNHEALTHY);
        assertThat(service.getProviderHealthStatus(null).getStatus()).isEqualTo(StatusEnum.UNHEALTHY);
    }

    private ProviderHealthRequestDTO request(ProviderSnapshotDTO provider) {
        var request = new ProviderHealthRequestDTO();
        request.setProvider(provider);
        return request;
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
        private final boolean configured;

        private FakeProviderAdapter(String supportedType, boolean configured) {
            this.supportedType = supportedType;
            this.configured = configured;
        }

        @Override
        public boolean supports(String type) {
            return supportedType.equals(type);
        }

        @Override
        public ChatModel createChatModel(AgentSnapshotDTO agent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isConfigured(ProviderSnapshotDTO provider) {
            return configured;
        }
    }
}
