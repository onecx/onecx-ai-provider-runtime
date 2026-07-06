package org.tkit.onecx.ai.provider.runtime.services.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;

@ApplicationScoped
public class ScaffoldPromptComposer {

    private static final String REQUEST_CONTEXT_PREFIX = "AI context";

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
        if (chatRequest == null || chatRequest.getRequestContext() == null) {
            return null;
        }
        Map<String, String> aiContext = chatRequest.getRequestContext().getAiContext();
        if (aiContext == null || aiContext.isEmpty()) {
            return null;
        }
        List<String> entries = aiContext.entrySet().stream()
                .filter(entry -> !isBlank(entry.getKey()) && !isBlank(entry.getValue()))
                .map(entry -> normalize(entry.getKey()) + "=" + normalize(entry.getValue()))
                .sorted()
                .toList();
        if (entries.isEmpty()) {
            return null;
        }
        return REQUEST_CONTEXT_PREFIX + ":\n" + String.join("\n", entries);
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
