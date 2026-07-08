package org.tkit.onecx.ai.provider.runtime.services.provider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthStatusDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderHealthStatusDTO.StatusEnum;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ProviderSnapshotDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class ProviderHealthService {

    @Inject
    Instance<ProviderAdapter> providerAdapters;

    public ProviderHealthStatusDTO getProviderHealthStatus(ProviderHealthRequestDTO request) {
        var response = new ProviderHealthStatusDTO();
        response.setStatus(isHealthy(request != null ? request.getProvider() : null) ? StatusEnum.HEALTHY
                : StatusEnum.UNHEALTHY);
        return response;
    }

    private boolean isHealthy(ProviderSnapshotDTO provider) {
        String providerType = providerType(provider);
        if (providerType == null) {
            return false;
        }
        for (ProviderAdapter adapter : providerAdapters) {
            if (adapter.supports(providerType)) {
                return adapter.isConfigured(provider);
            }
        }
        log.warn("Provider type not supported by current runtime: {}", providerType);
        return false;
    }

    private String providerType(ProviderSnapshotDTO provider) {
        return provider != null && provider.getType() != null ? provider.getType().toString() : null;
    }
}
