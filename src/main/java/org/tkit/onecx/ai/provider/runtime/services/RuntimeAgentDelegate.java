package org.tkit.onecx.ai.provider.runtime.services;

import java.util.function.Supplier;

public record RuntimeAgentDelegate(
        String name,
        String description,
        Supplier<RuntimeAgent> agentSupplier) {

    public RuntimeAgent open() {
        return agentSupplier.get();
    }
}
