package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ChatRequestDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.RequestContextDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ScaffoldSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.SkillSnapshotDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ScaffoldPromptComposerTest {

    private final ScaffoldPromptComposer composer = new ScaffoldPromptComposer();

    @Test
    void compose_doesNotInlineScaffoldSkills() {
        ScaffoldSnapshotDTO scaffold = new ScaffoldSnapshotDTO();
        scaffold.setSystemPrompt("Base system prompt");
        scaffold.setSkills(List.of(
                skill("Zoo", "Animal facts", "Use zoology knowledge."),
                skill("Audit", "Compliance checks", "Use compliance rules.")));

        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setScaffold(scaffold);
        agent.setAdditionalPrompt("Agent prompt");

        String prompt = composer.compose(agent, null);

        assertThat(prompt).contains("Base system prompt");
        assertThat(prompt).contains("Agent prompt");
        assertThat(prompt).doesNotContain("Available scaffold skills");
        assertThat(prompt).doesNotContain("Audit");
        assertThat(prompt).doesNotContain("Use compliance rules.");
        assertThat(prompt).doesNotContain("Zoo");
        assertThat(prompt).doesNotContain("Use zoology knowledge.");
    }

    @Test
    void compose_trimsAndJoinsNonBlankPromptBlocks() {
        ScaffoldSnapshotDTO scaffold = new ScaffoldSnapshotDTO();
        scaffold.setSystemPrompt("  Base system prompt  ");

        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setScaffold(scaffold);
        agent.setAdditionalPrompt("  Agent prompt  ");

        assertThat(composer.compose(agent, null)).isEqualTo("Base system prompt\n\nAgent prompt");
    }

    @Test
    void compose_includesAiContextDirective() {
        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setAdditionalPrompt("Agent prompt");

        ChatRequestDTO request = new ChatRequestDTO();
        RequestContextDTO context = new RequestContextDTO();
        context.setAiContext(Map.of(" APP_ID ", " onecx ", " locale ", " en-US "));
        request.setRequestContext(context);

        assertThat(composer.compose(agent, request))
                .isEqualTo("Agent prompt\n\nAI context:\nAPP_ID=onecx\nlocale=en-US");
    }

    @Test
    void compose_ignoresBlankPartsAndIncompleteRequestContext() {
        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setAdditionalPrompt(" ");

        ChatRequestDTO request = new ChatRequestDTO();
        RequestContextDTO context = new RequestContextDTO();
        context.setAiContext(Map.of("APP_ID", " "));
        request.setRequestContext(context);

        assertThat(composer.compose(agent, request)).isEmpty();
        assertThat(composer.compose(null, null)).isEmpty();
    }

    private SkillSnapshotDTO skill(String name, String description, String instruction) {
        SkillSnapshotDTO skill = new SkillSnapshotDTO();
        skill.setName(name);
        skill.setDescription(description);
        skill.setInstruction(instruction);
        return skill;
    }
}
