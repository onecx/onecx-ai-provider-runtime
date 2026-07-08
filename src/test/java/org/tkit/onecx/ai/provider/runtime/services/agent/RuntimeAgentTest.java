package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import dev.langchain4j.agentic.UntypedAgent;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeAgentTest {

    @Test
    void convenienceConstructor_usesAgentAsInvoker() {
        UntypedAgent agent = mock(UntypedAgent.class);
        RuntimeAgent runtimeAgent = new RuntimeAgent("name", "desc", agent, null);

        assertThat(runtimeAgent.agent()).isSameAs(agent);
        assertThat(runtimeAgent.invoker()).isSameAs(agent);
    }

    @Test
    void close_ignoresMissingOrFailingResources() throws Exception {
        AutoCloseable resources = mock(AutoCloseable.class);
        doThrow(new Exception("close failed")).when(resources).close();

        assertThatCode(() -> new RuntimeAgent("name", "desc", mock(Object.class), mock(UntypedAgent.class), null).close())
                .doesNotThrowAnyException();
        assertThatCode(() -> new RuntimeAgent("name", "desc", mock(Object.class), mock(UntypedAgent.class), resources)
                .close()).doesNotThrowAnyException();

        verify(resources).close();
    }
}
