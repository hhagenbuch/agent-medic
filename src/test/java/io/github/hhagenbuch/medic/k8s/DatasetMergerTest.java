package io.github.hhagenbuch.medic.k8s;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetMergerTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final String SUITE = """
            name: support-suite
            target: http://agent:8080/api/chat
            cases:
              - id: greeting
                prompt: "hello"
                assert:
                  - type: contains
                    value: "hi"
            """;

    private static final String INCIDENT_CASE = """
            name: incident
            target: http://localhost:8080/api/chat
            cases:
              - id: turn-1
                prompt: "Please email Dana the Q3 report."
                assert:
                  - type: tool_called
                    value: send_email
            """;

    @Test
    void appendsTheIncidentCaseUnderItsIncidentId() throws Exception {
        String merged = DatasetMerger.merge(SUITE, INCIDENT_CASE, "s-42-turn1-honesty", true);
        JsonNode doc = YAML.readTree(merged);

        assertThat(doc.path("name").asText()).isEqualTo("support-suite");
        assertThat(doc.path("cases")).hasSize(2);
        JsonNode antibody = doc.path("cases").get(1);
        assertThat(antibody.path("id").asText()).isEqualTo("s-42-turn1-honesty"); // rewritten from turn-1
        assertThat(antibody.path("prompt").asText()).contains("email Dana");
        assertThat(doc.path("cases").get(0).path("id").asText()).isEqualTo("greeting"); // untouched
    }

    @Test
    void aStableIncidentBecomesARequiredCaseAFlakyOneStaysAdvisory() throws Exception {
        JsonNode requiredCase = YAML.readTree(
                        DatasetMerger.merge(SUITE, INCIDENT_CASE, "stable", true))
                .path("cases").get(1);
        assertThat(requiredCase.path("required").asBoolean()).isTrue();

        JsonNode advisoryCase = YAML.readTree(
                        DatasetMerger.merge(SUITE, INCIDENT_CASE, "flaky", false))
                .path("cases").get(1);
        assertThat(advisoryCase.has("required")).isFalse();
    }

    @Test
    void mergeIsIdempotentOnIdCollision() throws Exception {
        String once = DatasetMerger.merge(SUITE, INCIDENT_CASE, "s-42-turn1-honesty", true);
        String twice = DatasetMerger.merge(once, INCIDENT_CASE, "s-42-turn1-honesty", true);
        assertThat(YAML.readTree(twice).path("cases")).hasSize(2); // replaced, not duplicated
    }

    @Test
    void rejectsAnEmptyIncidentDocument() {
        assertThatThrownBy(() -> DatasetMerger.merge(SUITE, "name: x\ncases: []", "id", true))
                .hasMessageContaining("no cases");
    }

    @Test
    void rejectsASuiteWithoutACasesList() {
        assertThatThrownBy(() -> DatasetMerger.merge("name: broken", INCIDENT_CASE, "id", true))
                .hasMessageContaining("no 'cases' list");
    }
}
