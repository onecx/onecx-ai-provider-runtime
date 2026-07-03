package org.tkit.onecx.ai.provider.runtime.services;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "onecx.ai.dispatch")
public interface DispatchConfig {

    /**
     * MCP related configuration.
     */
    @WithName("mcp")
    MCPConfig mcpConfig();

    /**
     * LLM provider related configuration.
     */
    @WithName("provider")
    ProviderConfig providerConfig();

    /**
     * Agent-to-agent orchestration related configuration.
     */
    @WithName("a2a")
    A2AConfig a2aConfig();

    interface MCPConfig {

        /**
         * Maximum number of sequential tool-call iterations.
         */
        @WithName("max-iterations")
        @WithDefault("10")
        long maxIterations();

        /**
         * Maximum MCP server timeout in seconds.
         */
        @WithName("timeout")
        @WithDefault("60")
        long maxTimeout();

        /**
         * Whether MCP responses should be logged.
         */
        @WithName("log-response")
        @WithDefault("false")
        boolean logResponse();

        /**
         * Whether MCP requests should be logged.
         */
        @WithName("log-requests")
        @WithDefault("false")
        boolean logRequests();

        /**
         * Maximum retries for MCP tool discovery.
         */
        @WithName("max-tool-execution-retries")
        @WithDefault("3")
        long maxToolExecutionRetries();
    }

    interface ProviderConfig {

        /**
         * Maximum provider request timeout in seconds.
         */
        @WithName("timeout")
        @WithDefault("60")
        long timeout();

        /**
         * Maximum provider request retries.
         */
        @WithName("max-retries")
        @WithDefault("2")
        long maxRetries();

        /**
         * Whether provider responses should be logged.
         */
        @WithName("log-response")
        @WithDefault("false")
        boolean logResponse();

        /**
         * Whether provider requests should be logged.
         */
        @WithName("log-requests")
        @WithDefault("false")
        boolean logRequests();
    }

    interface A2AConfig {

        /**
         * Maximum recursive A2A orchestration depth.
         */
        @WithName("max-depth")
        @WithDefault("10")
        int maxDepth();
    }
}
