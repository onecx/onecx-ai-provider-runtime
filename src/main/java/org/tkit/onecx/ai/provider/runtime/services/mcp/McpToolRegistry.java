package org.tkit.onecx.ai.provider.runtime.services.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.ToolSpecification;

public record McpToolRegistry(List<McpTool> tools) implements AutoCloseable {

    public static McpToolRegistry empty() {
        return new McpToolRegistry(List.of());
    }

    public List<ToolSpecification> getToolSpecifications() {
        return tools.stream().map(McpTool::toolSpecification).toList();
    }

    /**
     * Deterministic SHA-256 over the sorted server/tool pairs, so equal discovered tool sets get equal hashes.
     * Each name is length-prefixed so that no name content can be confused with the field boundary.
     */
    public String toolSetHash() {
        String canonical = tools.stream()
                .map(tool -> lengthPrefixed(tool.serverName()) + lengthPrefixed(tool.toolSpecification().name()))
                .sorted()
                .collect(Collectors.joining());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    @Override
    public void close() {
        for (McpTool tool : tools) {
            try {
                tool.client().close();
            } catch (Exception ignored) {
                // Best-effort MCP client cleanup.
            }
        }
    }
}
