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
     */
    public String toolSetHash() {
        String canonical = tools.stream()
                .map(tool -> tool.serverName() + "\t" + tool.toolSpecification().name())
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
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
