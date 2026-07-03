package org.tkit.onecx.ai.provider.runtime.services.provider;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilderFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class OllamaProviderAdapter implements ProviderAdapter {

    @Inject
    DispatchConfig dispatchConfig;

    @Override
    public boolean supports(String type) {
        return "OLLAMA".equals(type);
    }

    @Override
    public boolean isConfigured(ProviderSnapshotDTO provider) {
        return provider != null && !isBlank(provider.getLlmUrl());
    }

    @Override
    public ChatModel createChatModel(AgentSnapshotDTO agent) {
        ProviderSnapshotDTO provider = agent.getModel().getProvider();
        String modelName = agent.getModel().getModelIdentifier();
        if (isBlank(provider.getLlmUrl())) {
            throw new IllegalArgumentException("Ollama provider has no LLM URL configured");
        }
        if (isBlank(modelName)) {
            throw new IllegalArgumentException("Agent model has no model identifier configured");
        }
        return OllamaChatModel.builder()
                .baseUrl(provider.getLlmUrl())
                .modelName(modelName)
                .customHeaders(createCustomHeaders(provider))
                .timeout(Duration.ofSeconds(dispatchConfig.providerConfig().timeout()))
                .maxRetries(providerMaxRetries())
                .logRequests(dispatchConfig.providerConfig().logRequests())
                .logResponses(dispatchConfig.providerConfig().logResponse())
                .httpClientBuilder(new JaxRsHttpClientBuilderFactory().create())
                .build();
    }

    private Map<String, String> createCustomHeaders(ProviderSnapshotDTO provider) {
        Map<String, String> headers = new HashMap<>();
        if (provider != null && !isBlank(provider.getApiKey())) {
            headers.put("Authorization", provider.getApiKey());
        }
        return headers;
    }

    private int providerMaxRetries() {
        long configured = dispatchConfig.providerConfig().maxRetries();
        if (configured < 0) {
            log.warn("Invalid provider max-retries={}; using 0", configured);
            return 0;
        }
        return configured > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) configured;
    }

}
