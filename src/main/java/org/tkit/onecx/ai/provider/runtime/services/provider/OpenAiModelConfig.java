package org.tkit.onecx.ai.provider.runtime.services.provider;

import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.tkit.onecx.ai.provider.runtime.common.RuntimeChatException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

record OpenAiModelConfig(boolean responses, String reasoningEffort) {

    private static final Logger LOG = Logger.getLogger(OpenAiModelConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> KEYS = Set.of("api", "reasoningEffort");
    private static final OpenAiModelConfig DEFAULT = new OpenAiModelConfig(false, null);

    static OpenAiModelConfig parse(String value, String modelIdentifier) {
        if (value == null) {
            return DEFAULT;
        }
        if (value.length() > 255) {
            throw new RuntimeChatException("RUNTIME_MODEL_CONFIG_INVALID", "ModelConfiguration",
                    "OpenAI modelConfig exceeds the 255-character storage limit", Response.Status.BAD_REQUEST);
        }
        if (value.isBlank()) {
            return DEFAULT;
        }
        try {
            JsonNode config = JSON.readTree(value);
            if (!config.isObject()) {
                return legacy(modelIdentifier);
            }
            var fields = config.fieldNames();
            while (fields.hasNext()) {
                if (!KEYS.contains(fields.next())) {
                    return legacy(modelIdentifier);
                }
            }
            JsonNode api = config.get("api");
            if (api != null && (!api.isTextual()
                    || !Set.of("chat-completions", "responses").contains(api.textValue()))) {
                return legacy(modelIdentifier);
            }
            JsonNode effort = config.get("reasoningEffort");
            if (effort != null && !effort.isTextual()) {
                return legacy(modelIdentifier);
            }
            String reasoning = effort == null ? null : effort.textValue().trim();
            return new OpenAiModelConfig(api != null && "responses".equals(api.textValue()),
                    reasoning == null || reasoning.isEmpty() ? null : reasoning);
        } catch (JsonProcessingException ex) {
            return legacy(modelIdentifier);
        }
    }

    private static OpenAiModelConfig legacy(String modelIdentifier) {
        String safeIdentifier = modelIdentifier == null ? "unknown" : modelIdentifier.replaceAll("[\\p{Cntrl}]", "_");
        LOG.warnf("Legacy OpenAI modelConfig for model '%s' is unsupported and is not applied; "
                + "save supported settings to replace it", safeIdentifier);
        return DEFAULT;
    }
}
