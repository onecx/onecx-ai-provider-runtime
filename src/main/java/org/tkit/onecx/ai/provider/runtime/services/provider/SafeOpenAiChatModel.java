package org.tkit.onecx.ai.provider.runtime.services.provider;

import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;

class SafeOpenAiChatModel implements ChatModel {

    private final ChatModel delegate;
    private final int maxRetries;
    private static final Set<Integer> REJECTION_STATUSES = Set.of(400, 404, 405, 415, 422, 501);

    SafeOpenAiChatModel(ChatModel delegate, int maxRetries) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        try {
            return RetryUtils.withRetry(() -> {
                try {
                    if (delegate instanceof OpenAiResponsesChatModel
                            && request.messages().stream().anyMatch(message -> message instanceof AiMessage ai
                                    && ai.images() != null && !ai.images().isEmpty())) {
                        throw new UnsupportedFeatureException("Assistant images are not supported by Responses");
                    }
                    return delegate.doChat(request);
                } catch (RuntimeException ex) {
                    throw classify(ex);
                }
            }, maxRetries);
        } catch (RuntimeException ex) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof RuntimeChatException structured) {
                    throw structured;
                }
                cause = cause.getCause() == cause ? null : cause.getCause();
            }
            throw new RuntimeChatException("RUNTIME_MODEL_PROVIDER_FAILED", "ProviderFailure",
                    "OpenAI provider request failed", Response.Status.BAD_GATEWAY);
        }
    }

    private RuntimeException classify(RuntimeException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof RuntimeChatException structured) {
                return new NonRetriableException(structured);
            }
            if (cause instanceof UnsupportedFeatureException) {
                return new NonRetriableException(new RuntimeChatException("RUNTIME_MODEL_FEATURE_UNSUPPORTED",
                        "UnsupportedModelFeature", "Selected OpenAI API does not support this request feature",
                        Response.Status.BAD_REQUEST));
            }
            if (cause instanceof HttpException http && REJECTION_STATUSES.contains(http.statusCode())) {
                return new NonRetriableException(new RuntimeChatException("RUNTIME_MODEL_ENDPOINT_REJECTED",
                        "ProviderEndpointRejection",
                        "OpenAI endpoint rejected the selected API or request settings; check model configuration",
                        Response.Status.BAD_GATEWAY));
            }
            if (cause instanceof IllegalArgumentException) {
                return new NonRetriableException(new RuntimeChatException("RUNTIME_MODEL_REQUEST_INVALID",
                        "ModelRequestValidation", "OpenAI request is invalid for the selected API",
                        Response.Status.BAD_REQUEST));
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        // Do not retain provider bodies in retry logs or in the public exception chain.
        return exception instanceof NonRetriableException
                ? new NonRetriableException("OpenAI provider request failed")
                : new RetriableException("OpenAI provider request failed");
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
