package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.CaseVerdict;
import tools.jackson.databind.JsonNode;

/**
 * The second gate bar, read from the eval Job's log — as data, not prose.
 *
 * <p>agent-evals (≥ 0.2.0) ends every run with one machine-readable line,
 * {@code VERDICT-JSON: {...}} (schema {@code agent-evals/verdict/v1}). The Job
 * pod log is the one channel the operator already exposes to medic — no shared
 * volume, no extra RBAC, no sidecar — and a tagged, schema-versioned JSON line
 * on it survives every human-facing format change that scraping {@code [PASS]}
 * lines would not.
 *
 * <p>Fail-closed throughout: a log with no verdict line (wrong evals image,
 * truncation), a malformed verdict, or a verdict that does not contain the
 * incident case is a broken gate — {@code FAILED}, never a pass. Only a log
 * that is not yet readable at all returns {@code UNKNOWN} (check again).
 * An advisory (non-required) incident case that failed comes back as
 * {@code FAILED_ADVISORY}: the gate legitimately passed without it, and a
 * human — not medic — decides what a flaky case's failure means.
 */
public final class IncidentCaseCheck {

    static final String VERDICT_TAG = "VERDICT-JSON: ";

    private IncidentCaseCheck() {
    }

    public static CaseVerdict verdict(String evalJobLog, String incidentCaseId) {
        if (evalJobLog == null || evalJobLog.isBlank() || incidentCaseId == null || incidentCaseId.isBlank()) {
            return CaseVerdict.UNKNOWN; // log not (yet) available — check again next pass
        }
        String json = evalJobLog.lines()
                .filter(line -> line.startsWith(VERDICT_TAG))
                .reduce((first, second) -> second) // the final verdict line wins
                .map(line -> line.substring(VERDICT_TAG.length()))
                .orElse(null);
        if (json == null) {
            return CaseVerdict.FAILED; // gate ran but emitted no verdict: broken gate, not a pass
        }
        JsonNode verdict;
        try {
            verdict = TraceEvent.mapper().readTree(json);
        } catch (Exception e) {
            return CaseVerdict.FAILED; // unparseable verdict: broken gate
        }
        for (JsonNode caze : verdict.path("cases")) {
            if (incidentCaseId.equals(caze.path("id").asText())) {
                if (caze.path("passed").asBoolean(false)) {
                    return CaseVerdict.PASSED;
                }
                return caze.path("required").asBoolean(false)
                        ? CaseVerdict.FAILED
                        : CaseVerdict.FAILED_ADVISORY;
            }
        }
        return CaseVerdict.FAILED; // the incident case never ran: broken gate
    }
}
