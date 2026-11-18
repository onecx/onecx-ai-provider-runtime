package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.tkit.onecx.ai.provider.runtime.config.DispatchConfig;

import gen.org.tkit.onecx.permission.api.PermissionApi;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;
import gen.org.tkit.onecx.permission.model.PermissionRequest;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;

/**
 * Cached lookup of a user's permissions for one product/application in the OneCX permission service.
 * Entries are keyed by product, application and a SHA-256 digest of the token, so raw tokens are never
 * stored as cache keys. Concurrent requests for the same key share one remote call (single-flight).
 * Failed lookups throw and are not cached.
 */
@ApplicationScoped
public class PermissionLookupService {

    public static final String CACHE_NAME = "onecx-tool-permissions";

    @Inject
    @RestClient
    PermissionApi permissionApi;

    @Inject
    DispatchConfig dispatchConfig;

    @Inject
    @CacheName(CACHE_NAME)
    Cache cache;

    public ApplicationPermissions getApplicationPermissions(String product, String app, String token) {
        Duration timeout = Duration.ofSeconds(dispatchConfig.toolConfig().maxTimeout());
        CacheKey key = new CacheKey(product, app, digest(token));
        return cache.get(key, k -> load(product, app, token)).await().atMost(timeout);
    }

    private ApplicationPermissions load(String product, String app, String token) {
        ApplicationPermissions result = permissionApi
                .getApplicationPermissions(product, app, new PermissionRequest().token(token))
                .await().atMost(Duration.ofSeconds(dispatchConfig.toolConfig().maxTimeout()));
        if (result == null) {
            throw new IllegalStateException("OneCX permission service returned no permissions for " + product + "/" + app);
        }
        return result;
    }

    static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    record CacheKey(String product, String app, String tokenDigest) {
    }
}
