package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ToolSnapshotDTO;
import io.quarkiverse.langchain4j.mcp.auth.McpClientAuthProvider;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class McpAuthHeaders {

    static final String AUTHORIZATION = "Authorization";

    Map<String, String> authorizationHeaders(ToolSnapshotDTO tool) {
        return authorizationHeaders(tool, Map.of());
    }

    Map<String, String> authorizationHeaders(ToolSnapshotDTO tool, Map<String, String> existingHeaders) {
        String authorization = resolveAuthorization(tool, existingHeaders);
        if (isBlank(authorization)) {
            return Map.of();
        }
        return Map.of(AUTHORIZATION, authorization);
    }

    private String resolveAuthorization(ToolSnapshotDTO tool, Map<String, String> existingHeaders) {
        return McpClientAuthProvider.resolve(null)
                .map(provider -> provider.getAuthorization(authInput(tool, existingHeaders)))
                .orElseGet(() -> {
                    log.warn("No default OAuth2 MCP auth provider available for MCP tool '{}'",
                            safeString(tool != null ? tool.getName() : null));
                    return null;
                });
    }

    private McpClientAuthProvider.Input authInput(ToolSnapshotDTO tool, Map<String, String> existingHeaders) {
        URI uri = URI.create(safeString(tool != null ? tool.getUrl() : null));
        Map<String, List<Object>> headers = existingHeaders.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return new McpAuthInput("POST", uri, headers);
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record McpAuthInput(String method, URI uri, Map<String, List<Object>> headers)
            implements
                McpClientAuthProvider.Input {
    }
}
