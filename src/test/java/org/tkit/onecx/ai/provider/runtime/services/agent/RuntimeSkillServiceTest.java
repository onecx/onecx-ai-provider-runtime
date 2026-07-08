package org.tkit.onecx.ai.provider.runtime.services.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.ScaffoldSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.SkillSnapshotDTO;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class RuntimeSkillServiceTest {

    private final RuntimeSkillService service = new RuntimeSkillService();

    @Test
    void runtimeSkills_mapsValidScaffoldSkillsWithoutInliningContent() {
        AgentSnapshotDTO agent = agentWithSkills(
                skill("Zoo", "Animal facts", "Use zoology knowledge."),
                skill("Audit", "Compliance checks", "Use compliance rules."));

        var runtimeSkills = service.runtimeSkills(agent);

        assertThat(runtimeSkills).isNotNull();
        assertThat(runtimeSkills.formatAvailableSkills()).contains("Audit", "Compliance checks");
        assertThat(runtimeSkills.formatAvailableSkills()).contains("Zoo", "Animal facts");
        assertThat(runtimeSkills.formatAvailableSkills()).doesNotContain("Use compliance rules.");
        assertThat(runtimeSkills.formatAvailableSkills()).doesNotContain("Use zoology knowledge.");
        assertThat(runtimeSkills.toolProvider()).isNotNull();
    }

    @Test
    void runtimeSkills_skipsInvalidSkillsAndUsesNameAsFallbackDescription() {
        AgentSnapshotDTO agent = agentWithSkills(
                skill("Valid", "", "Use valid skill instructions."),
                skill("", "Missing name", "Should be skipped."),
                skill("Missing instruction", "No instruction", ""));

        var runtimeSkills = service.runtimeSkills(agent);

        assertThat(runtimeSkills).isNotNull();
        assertThat(runtimeSkills.formatAvailableSkills()).contains("Valid");
        assertThat(runtimeSkills.formatAvailableSkills()).doesNotContain("Missing name");
        assertThat(runtimeSkills.formatAvailableSkills()).doesNotContain("Missing instruction");
    }

    @Test
    void runtimeSkills_withoutValidSkillsReturnsNull() {
        assertThat(service.runtimeSkills(null)).isNull();
        assertThat(service.runtimeSkills(new AgentSnapshotDTO())).isNull();
        assertThat(service.runtimeSkills(agentWithSkills(
                skill("", "Missing name", "Should be skipped."),
                skill("Missing instruction", "No instruction", "")))).isNull();
    }

    @Test
    void activationPrompt_containsCatalogAndActivationInstruction() {
        var runtimeSkills = service
                .runtimeSkills(agentWithSkills(skill("Audit", "Compliance checks", "Use compliance rules.")));

        assertThat(service.activationPrompt(runtimeSkills))
                .contains("Available skills:")
                .contains("Audit")
                .contains("Activate a relevant skill before applying its instructions.")
                .doesNotContain("Use compliance rules.");
    }

    private AgentSnapshotDTO agentWithSkills(SkillSnapshotDTO... skills) {
        ScaffoldSnapshotDTO scaffold = new ScaffoldSnapshotDTO();
        scaffold.setSkills(Arrays.asList(skills));

        AgentSnapshotDTO agent = new AgentSnapshotDTO();
        agent.setName("test-agent");
        agent.setScaffold(scaffold);
        return agent;
    }

    private SkillSnapshotDTO skill(String name, String description, String instruction) {
        SkillSnapshotDTO skill = new SkillSnapshotDTO();
        skill.setName(name);
        skill.setDescription(description);
        skill.setInstruction(instruction);
        return skill;
    }
}
