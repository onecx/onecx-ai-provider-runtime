package org.tkit.onecx.ai.provider.runtime.services.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import gen.org.tkit.onecx.permission.api.PermissionApi;
import gen.org.tkit.onecx.permission.model.ApplicationPermissions;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;

@QuarkusTest
class PermissionLookupServiceTest {

    @InjectMock
    @RestClient
    PermissionApi permissionApi;

    @Inject
    PermissionLookupService service;

    @Test
    void getApplicationPermissions_cachesSuccessfulLookupForSameProductAppAndToken() {
        String product = "product-cached";
        when(permissionApi.getApplicationPermissions(eq(product), anyString(), any()))
                .thenReturn(Uni.createFrom().item(permissions()));

        var first = service.getApplicationPermissions(product, "app", "token-a");
        var second = service.getApplicationPermissions(product, "app", "token-a");

        assertThat(second).isSameAs(first);
        verify(permissionApi, times(1)).getApplicationPermissions(eq(product), eq("app"), any());
    }

    @Test
    void getApplicationPermissions_missesCacheForDifferentToken() {
        String product = "product-per-token";
        when(permissionApi.getApplicationPermissions(eq(product), anyString(), any()))
                .thenReturn(Uni.createFrom().item(permissions()));

        service.getApplicationPermissions(product, "app", "token-a");
        service.getApplicationPermissions(product, "app", "token-b");

        verify(permissionApi, times(2)).getApplicationPermissions(eq(product), eq("app"), any());
    }

    @Test
    void getApplicationPermissions_sharesOneRemoteCallAcrossConcurrentRequests() throws Exception {
        String product = "product-single-flight";
        AtomicInteger remoteCalls = new AtomicInteger();
        when(permissionApi.getApplicationPermissions(eq(product), anyString(), any())).thenAnswer(invocation -> {
            remoteCalls.incrementAndGet();
            Thread.sleep(200);
            return Uni.createFrom().item(permissions());
        });

        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<ApplicationPermissions>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> service.getApplicationPermissions(product, "app", "token-a")));
            }
            for (Future<ApplicationPermissions> future : futures) {
                assertThat(future.get()).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(remoteCalls.get()).isEqualTo(1);
    }

    @Test
    void getApplicationPermissions_doesNotCacheFailures() {
        String product = "product-failure";
        when(permissionApi.getApplicationPermissions(eq(product), anyString(), any()))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("permission-svc down")))
                .thenReturn(Uni.createFrom().item(permissions()));

        assertThatThrownBy(() -> service.getApplicationPermissions(product, "app", "token-a"))
                .isInstanceOf(RuntimeException.class);
        assertThat(service.getApplicationPermissions(product, "app", "token-a")).isNotNull();

        verify(permissionApi, times(2)).getApplicationPermissions(eq(product), eq("app"), any());
    }

    private static ApplicationPermissions permissions() {
        return new ApplicationPermissions().permissions(Map.of("onecx:event", Set.of("mcp-read")));
    }
}
