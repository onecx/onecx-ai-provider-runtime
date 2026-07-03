package org.tkit.onecx.ai.provider.runtime.services.agent;

import dev.langchain4j.agentic.UntypedAgent;

public record RuntimeAgent(
        String name,
        String description,
        Object agent,
        UntypedAgent invoker,
        AutoCloseable resources) implements AutoCloseable {

    public RuntimeAgent(String name, String description, UntypedAgent agent, AutoCloseable resources) {
        this(name, description, agent, agent, resources);
    }

    @Override
    public void close() {
        if (resources == null) {
            return;
        }
        try {
            resources.close();
        } catch (Exception ignored) {
            // Runtime cleanup must not hide invocation results.
        }
    }
}
