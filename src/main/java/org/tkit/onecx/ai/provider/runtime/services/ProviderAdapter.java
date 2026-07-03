package org.tkit.onecx.ai.provider.runtime.services;

import dev.langchain4j.model.chat.ChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;

public interface ProviderAdapter {

    boolean supports(String type);

    ChatModel createChatModel(AgentSnapshotDTO agent);

    default String providerType(ProviderSnapshotDTO provider) {
        return provider != null && provider.getType() != null ? provider.getType().toString() : null;
    }
}
