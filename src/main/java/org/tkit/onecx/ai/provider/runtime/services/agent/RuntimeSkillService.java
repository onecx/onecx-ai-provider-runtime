package org.tkit.onecx.ai.provider.runtime.services.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.skills.Skills;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.AgentSnapshotDTO;
import gen.org.tkit.onecx.ai.provider.runtime.rs.internal.model.SkillSnapshotDTO;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ApplicationScoped
public class RuntimeSkillService {

    public Skills runtimeSkills(AgentSnapshotDTO agent) {
        if (agent == null || agent.getScaffold() == null || agent.getScaffold().getSkills() == null) {
            return null;
        }
        List<dev.langchain4j.skills.Skill> skills = agent.getScaffold().getSkills().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(skill -> normalize(skill.getName()).toLowerCase()))
                .map(skill -> toRuntimeSkill(agent, skill))
                .filter(Objects::nonNull)
                .toList();
        return skills.isEmpty() ? null : Skills.from(skills);
    }

    public String activationPrompt(Skills runtimeSkills) {
        return "Available skills:" + System.lineSeparator()
                + runtimeSkills.formatAvailableSkills() + System.lineSeparator()
                + "Activate a relevant skill before applying its instructions.";
    }

    private dev.langchain4j.skills.Skill toRuntimeSkill(AgentSnapshotDTO agent, SkillSnapshotDTO skill) {
        String name = normalize(skill.getName());
        String instruction = normalize(skill.getInstruction());
        if (isBlank(name) || isBlank(instruction)) {
            log.warn("Skipping scaffold skill for agent '{}': skillId={}, skillName={}, hasInstruction={}",
                    runtimeName(agent), skill.getId(), skill.getName(), !isBlank(instruction));
            return null;
        }
        String description = normalize(skill.getDescription());
        return dev.langchain4j.skills.Skill.builder()
                .name(name)
                .description(!isBlank(description) ? description : name)
                .content(instruction)
                .build();
    }

    private String runtimeName(AgentSnapshotDTO agent) {
        return agent != null && !isBlank(agent.getName()) ? agent.getName() : "local-agent";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
