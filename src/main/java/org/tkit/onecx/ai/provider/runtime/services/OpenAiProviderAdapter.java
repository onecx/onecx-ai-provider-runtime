package org.tkit.onecx.ai.provider.runtime.services;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class OpenAiProviderAdapter implements ProviderAdapter {

    @Inject
    DispatchConfig dispatchConfig;

    @Override
    public boolean supports(String type) {
        return "OPENAI".equals(type);
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
                .maxRetries(providerMaxRetries())
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

    private int providerMaxRetries() {
        long configured = dispatchConfig.providerConfig().maxRetries();
        if (configured < 0) {
            log.warn("Invalid provider max-retries={}; using 0", configured);
            return 0;
        }
        return configured > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) configured;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
