package org.tkit.onecx.ai.provider.runtime.services.provider;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import io.quarkiverse.langchain4j.jaxrsclient.JaxRsHttpClientBuilder;

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
        OpenAiModelConfig config = OpenAiModelConfig.parse(agent.getModel().getModelConfig(), modelName);
        Duration timeout = Duration.ofSeconds(providerTimeoutSeconds());
        var httpClient = new JaxRsHttpClientBuilder();
        httpClient.connectTimeout(timeout).readTimeout(timeout);
        if (config.responses()) {
            var responses = OpenAiResponsesChatModel.builder()
                    .httpClientBuilder(httpClient)
                    .apiKey(provider.getApiKey())
                    .modelName(modelName)
                    .reasoningEffort(config.reasoningEffort())
                    .store(false)
                    .logRequests(dispatchConfig.providerConfig().logRequests())
                    .logResponses(dispatchConfig.providerConfig().logResponse());
            if (includesEncryptedReasoning(config.reasoningEffort())) {
                responses.include(List.of("reasoning.encrypted_content"));
            }
            if (!isBlank(provider.getLlmUrl())) {
                responses.baseUrl(provider.getLlmUrl());
            }
            return new SafeOpenAiChatModel(responses.build(), 0);
        }
        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(httpClient)
                .apiKey(provider.getApiKey())
                .modelName(modelName)
                .reasoningEffort(config.reasoningEffort())
                .timeout(timeout)
                // Apply the same SDK retry policy outside the model so 501/validation errors
                // can be classified before retrying, including Quarkus' custom OpenAI client.
                .maxRetries(0)
                .logRequests(dispatchConfig.providerConfig().logRequests())
                .logResponses(dispatchConfig.providerConfig().logResponse());
        if (!isBlank(provider.getLlmUrl())) {
            builder.baseUrl(provider.getLlmUrl());
        }
        return new SafeOpenAiChatModel(builder.build(), ProviderRetryConfig.maxRetries(dispatchConfig, "OPENAI"));
    }

    private long providerTimeoutSeconds() {
        return dispatchConfig.providerConfig().timeout();
    }

    private boolean includesEncryptedReasoning(String reasoningEffort) {
        return !isBlank(reasoningEffort) && !"none".equalsIgnoreCase(reasoningEffort);
    }

}
