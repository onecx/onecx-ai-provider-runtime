package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PermissionCacheConfigTest {

    @ConfigProperty(name = "quarkus.cache.caffeine.\"onecx-tool-permissions\".expire-after-write")
    Duration cacheExpireAfterWrite;

    @Inject
    PermissionLookupService permissionLookupService;

    @Test
    void cacheTtlIsResolvedFromTheToolCacheProperty() {
        assertThat(cacheExpireAfterWrite).isEqualTo(Duration.ofMinutes(5));
        assertThat(permissionLookupService).isNotNull();
    }
}
