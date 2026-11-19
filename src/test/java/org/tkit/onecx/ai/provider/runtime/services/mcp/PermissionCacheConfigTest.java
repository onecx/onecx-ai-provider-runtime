package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PermissionCacheConfigTest {

    @ConfigProperty(name = "quarkus.cache.caffeine.\"onecx-tool-permissions\".expire-after-write")
    Duration cacheExpireAfterWrite;

    @ConfigProperty(name = "quarkus.cache.caffeine.\"onecx-tool-permissions\".maximum-size")
    Long cacheMaximumSize;

    @Test
    void cacheConfigIsResolvedFromProperties() {
        assertThat(cacheExpireAfterWrite).isEqualTo(Duration.ofMinutes(5));
        assertThat(cacheMaximumSize).isEqualTo(1000L);
    }
}
