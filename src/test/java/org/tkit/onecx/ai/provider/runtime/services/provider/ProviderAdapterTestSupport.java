package org.tkit.onecx.ai.provider.runtime.services.provider;

import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ModelSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;

class ProviderAdapterTestSupport {

    private ProviderAdapterTestSupport() {
    }

    static DispatchConfig dispatchConfig() {
        return dispatchConfig(0);
    }

    static DispatchConfig dispatchConfig(long maxRetries) {
        return new DispatchConfig() {
            @Override
            public MCPConfig mcpConfig() {
                return null;
            }

            @Override
            public ProviderConfig providerConfig() {
                return new ProviderConfig() {
                    @Override
                    public long timeout() {
                        return 1;
                    }

                    @Override
                    public long maxRetries() {
                        return maxRetries;
                    }

                    @Override
                    public boolean logResponse() {
                        return false;
                    }

                    @Override
                    public boolean logRequests() {
                        return false;
                    }
                };
            }

            @Override
            public A2AConfig a2aConfig() {
                return null;
            }
        };
    }

    static AgentSnapshotDTO agent(String providerType, String baseUrl, String modelIdentifier, String apiKey) {
        var model = new ModelSnapshotDTO();
        model.setProvider(provider(providerType, baseUrl, apiKey));
        model.setModelIdentifier(modelIdentifier);

        var agent = new AgentSnapshotDTO();
        agent.setModel(model);
        return agent;
    }

    static ProviderSnapshotDTO provider(String type, String baseUrl, String apiKey) {
        var provider = new ProviderSnapshotDTO();
        provider.setType(type);
        provider.setLlmUrl(baseUrl);
        provider.setApiKey(apiKey);
        return provider;
    }
}
