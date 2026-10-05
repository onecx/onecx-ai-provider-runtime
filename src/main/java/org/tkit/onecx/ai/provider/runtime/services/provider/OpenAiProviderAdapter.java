package org.tkit.onecx.ai.provider.runtime.services.provider;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;

@ApplicationScoped
public class OpenAiProviderAdapter implements ProviderAdapter {

    @Inject
    DispatchConfig dispatchConfig;

    @Override
    public boolean supports(String type) {
        return "OPENAI".equals(type);
    }

    @Override
    public boolean isConfigured(ProviderSnapshotDTO provider) {
        return provider != null && !isBlank(provider.getApiKey());
    }

    @Override
    public ChatModel createChatModel(AgentSnapshotDTO agent) {
        ProviderSnapshotDTO provider = agent.getModel().getProvider();
        String modelName = agent.getModel().getModelIdentifier();
        if (isBlank(provider.getApiKey())) {
            throw new IllegalArgumentException("OpenAI provider has no API key configured");
        }
        if (isBlank(modelName)) {
            throw new IllegalArgumentException("Agent model has no model identifier configured");
        }
        var builder = OpenAiChatModel.builder()
                .apiKey(provider.getApiKey())
                .modelName(modelName)
                .timeout(Duration.ofSeconds(providerTimeoutSeconds()))
                .maxRetries(ProviderRetryConfig.maxRetries(dispatchConfig, "OPENAI"))
                .logRequests(dispatchConfig.providerConfig().logRequests())
                .logResponses(dispatchConfig.providerConfig().logResponse());
        if (!isBlank(provider.getLlmUrl())) {
            builder.baseUrl(provider.getLlmUrl());
        }
        return builder.build();
    }

    private long providerTimeoutSeconds() {
        return dispatchConfig.providerConfig().timeout();
    }

}
