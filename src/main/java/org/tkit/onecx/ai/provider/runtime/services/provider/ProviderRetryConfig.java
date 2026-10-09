package org.tkit.onecx.ai.provider.runtime.services.provider;

import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ProviderRetryConfig {

    private ProviderRetryConfig() {
    }

    static int maxRetries(DispatchConfig dispatchConfig, String providerType) {
        long configured = dispatchConfig.providerConfig().maxRetries();
        if (configured < 0) {
            log.warn("Invalid provider max-retries={} for provider '{}'; using 0", configured, providerType);
            return 0;
        }
        return configured > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) configured;
    }
}
