package org.tkit.onecx.ai.provider.runtime.services.provider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;

@ApplicationScoped
public class ChatModelFactory {

    @Inject
    Instance<ProviderAdapter> providerAdapters;

    public ChatModel createChatModel(AgentSnapshotDTO agent) {
        if (agent == null || agent.getModel() == null || agent.getModel().getProvider() == null) {
            throw new IllegalArgumentException("Agent has no associated model or provider");
        }
        ProviderSnapshotDTO provider = agent.getModel().getProvider();
        String providerType = provider.getType() != null ? provider.getType().toString() : null;
        for (ProviderAdapter adapter : providerAdapters) {
            if (adapter.supports(providerType)) {
                return adapter.createChatModel(agent);
            }
        }
        throw new IllegalArgumentException("Provider type not supported by current runtime: " + providerType);
    }
}
