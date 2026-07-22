package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Merges an exported incident case into an agent-evals dataset. Used twice,
 * with the same semantics both times:
 * <ul>
 *   <li>building the CANDIDATE dataset a repair is gated against
 *       (suite + the incident case), and</li>
 *   <li>the antibody rule — folding the case into the PERMANENT suite on
 *       promotion.</li>
 * </ul>
 * The case's id is rewritten to the incident id: unique across the suite, and
 * the exact string medic later greps for in the gate's {@code [PASS] <id>}
 * output. On id collision the incoming case replaces the old one — an antibody
 * is never silently dropped, and re-merging is idempotent.
 */
public final class DatasetMerger {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

    private DatasetMerger() {
    }

    public static String merge(String suiteYaml, String caseYaml, String incidentCaseId) {
        ObjectNode suite = readObject(suiteYaml, "suite dataset");
        ObjectNode caseDoc = readObject(caseYaml, "incident case");

        JsonNode incidentCases = caseDoc.path("cases");
        if (!incidentCases.isArray() || incidentCases.isEmpty()) {
            throw new IllegalArgumentException("incident case document has no cases");
        }
        ObjectNode incidentCase = ((ObjectNode) incidentCases.get(0)).deepCopy();
        incidentCase.put("id", incidentCaseId);

        JsonNode suiteCases = suite.path("cases");
        if (!suiteCases.isArray()) {
            throw new IllegalArgumentException("suite dataset has no 'cases' list");
        }
        ArrayNode merged = TraceEvent.mapper().createArrayNode();
        for (JsonNode existing : suiteCases) {
            if (!incidentCaseId.equals(existing.path("id").asText())) {
                merged.add(existing);
            }
        }
        merged.add(incidentCase);
        suite.set("cases", merged);
        try {
            return YAML.writeValueAsString(suite);
        } catch (Exception e) {
            throw new IllegalStateException("failed to render merged dataset", e);
        }
    }

    private static ObjectNode readObject(String yaml, String what) {
        try {
            JsonNode node = YAML.readTree(yaml);
            if (!node.isObject()) {
                throw new IllegalArgumentException(what + " is not a YAML object");
            }
            return (ObjectNode) node;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(what + " is not valid YAML: " + e.getMessage(), e);
        }
    }
}
