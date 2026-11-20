package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import gen.org.tkit.onecx.permission.api.PermissionApi;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;
import gen.org.tkit.onecx.permission.model.PermissionRequest;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;

/**
 * Cached lookup of a user's permissions for one product/application in the OneCX permission service.
 * Entries are keyed by product, application and the token. Concurrent requests for the same key share one
 * remote call (single-flight).
 * Failed lookups throw and are not cached.
 */
@ApplicationScoped
public class PermissionLookupService {

    public static final String CACHE_NAME = "onecx-tool-permissions";

    @Inject
    @RestClient
    PermissionApi permissionApi;

    @ConfigProperty(name = "onecx.ai.tool-permission-timeout", defaultValue = "PT10S")
    Duration permissionTimeout;

    @Inject
    @CacheName(CACHE_NAME)
    Cache cache;

    public ApplicationPermissions getApplicationPermissions(String product, String app, String token) {
        CacheKey key = new CacheKey(product, app, token);
        return cache.get(key, k -> load(product, app, token)).await().atMost(permissionTimeout);
    }

    private ApplicationPermissions load(String product, String app, String token) {
        ApplicationPermissions result = permissionApi
                .getApplicationPermissions(product, app, new PermissionRequest().token(token))
                .await().atMost(permissionTimeout);
        if (result == null) {
            throw new IllegalStateException("OneCX permission service returned no permissions for " + product + "/" + app);
        }
        return result;
    }

    record CacheKey(String product, String app, String token) {
    }
}
