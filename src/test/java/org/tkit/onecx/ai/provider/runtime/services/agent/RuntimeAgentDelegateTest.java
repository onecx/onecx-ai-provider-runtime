package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeAgentDelegateTest {

    @Test
    void open_returnsSuppliedRuntimeAgent() {
        RuntimeAgent runtimeAgent = mock(RuntimeAgent.class);
        RuntimeAgentDelegate delegate = new RuntimeAgentDelegate("name", "desc", () -> runtimeAgent);

        assertThat(delegate.open()).isSameAs(runtimeAgent);
    }
}
