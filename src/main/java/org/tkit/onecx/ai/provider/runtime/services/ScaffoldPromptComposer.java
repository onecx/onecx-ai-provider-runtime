package org.tkit.onecx.ai.provider.runtime.services;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;

@ApplicationScoped
public class ScaffoldPromptComposer {

    private static final String REQUEST_CONTEXT_PREFIX = "Request context filter";

    public String compose(AgentSnapshotDTO agent, ChatRequestDTO chatRequest) {
        List<String> blocks = new ArrayList<>();
        if (agent != null && agent.getScaffold() != null) {
            addIfNotBlank(blocks, agent.getScaffold().getSystemPrompt());
        }
        if (agent != null) {
            addIfNotBlank(blocks, agent.getAdditionalPrompt());
        }
        addIfNotBlank(blocks, buildRequestContextDirective(chatRequest));
        return String.join("\n\n", blocks);
    }

    private String buildRequestContextDirective(ChatRequestDTO chatRequest) {
        if (chatRequest == null || chatRequest.getRequestContext() == null
                || chatRequest.getRequestContext().getFilter() == null) {
            return null;
        }
        String key = chatRequest.getRequestContext().getFilter().getKey();
        String value = chatRequest.getRequestContext().getFilter().getValue();
        if (isBlank(key) || isBlank(value)) {
            return null;
        }
        return REQUEST_CONTEXT_PREFIX + ": " + normalize(key) + "=" + normalize(value);
    }

    private void addIfNotBlank(List<String> blocks, String value) {
        if (!isBlank(value)) {
            blocks.add(normalize(value));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
