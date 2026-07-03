package org.tkit.onecx.ai.provider.runtime.services.external;

public record AgentCard(String name, String description, String url) {

    public boolean hasInvokeUrl() {
        return url != null && !url.isBlank();
    }
}
