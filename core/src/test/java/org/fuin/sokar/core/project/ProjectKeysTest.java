package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests that every key a project file may hold is described once, and that what the description says is what the
 * reader does.
 */
class ProjectKeysTest {

    private static ProjectKey key(String section, String name) {
        return ProjectReader.keys().stream().filter(each -> each.section().equals(section) && each.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(section + "." + name + " is not described"));
    }

    @Test
    void everyKeyTheSchemaKnowsIsDescribedOnceAndNothingElseIs() {
        final List<String> described = ProjectReader.keys().stream().map(each -> each.section() + "|" + each.name())
                .toList();
        final List<String> known = ProjectReader.schema().entrySet().stream()
                .flatMap(section -> section.getValue().stream().map(name -> section.getKey() + "|" + name)).toList();

        assertThat(described).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(known);
        assertThat(ProjectReader.keys()).allSatisfy(each -> {
            assertThat(each.describe()).as(each.name()).isNotBlank().doesNotEndWith(" ");
            assertThat(each.type()).isIn("string", "boolean", "integer", "list", "map");
        });
    }

    @Test
    void theDefaultsSaidAreTheReadersOwn() {
        final Project read = ProjectReader.read(new StringReader("project:\n  name: \"p\"\n  security_class:"
                + " \"guarded\"\nimage:\n  base_image: \"ubuntu:24.04\"\n"), "project.yml");

        assertThat(key("limits", "memory").defaultValue()).isEqualTo(Limits.defaults().memory());
        assertThat(key("limits", "pids").defaultValue()).isEqualTo(String.valueOf(Limits.defaults().pids()));
        assertThat(key("mail.peers.<peer>", "per_day").defaultValue())
                .isEqualTo(String.valueOf(Mail.Peer.DEFAULT_PER_DAY));
        assertThat(key("image", "package_sources").defaultValue()).contains(Project.DEFAULT_PACKAGE_SOURCES.get(0));
    }

    @Test
    void whatIsSaidToBeRequiredIsAllAFirstProjectNeeds() {
        final Map<String, List<String>> required = new java.util.LinkedHashMap<>();
        ProjectReader.keys().stream().filter(ProjectKey::required).forEach(each ->
                required.computeIfAbsent(each.section(), section -> new java.util.ArrayList<>()).add(each.name()));

        assertThat(required).containsEntry("project", List.of("name", "security_class"))
                .containsEntry("image", List.of("base_image")).containsEntry("", List.of("project", "image"));
        assertThat(key("project", "security_class").values()).containsExactly("offline", "guarded", "online");
    }
}
