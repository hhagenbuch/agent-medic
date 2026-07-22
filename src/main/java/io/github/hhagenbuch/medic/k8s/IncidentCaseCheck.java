package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.medic.k8s.MedicStateMachine.CaseVerdict;

/**
 * The second gate bar, read from the eval Job's log. agent-evals prints one
 * line per case — {@code [PASS] <caseId> (12 ms)} / {@code [FAIL] <caseId> ...}
 * — and medic requires the incident's own case to be on a PASS line. The
 * aggregate min-pass-rate already passed (the PromptVersion reached
 * AwaitingApproval); this catches the fix that clears the average while the
 * triggering case still fails.
 */
public final class IncidentCaseCheck {

    private IncidentCaseCheck() {
    }

    public static CaseVerdict verdict(String evalJobLog, String incidentCaseId) {
        if (evalJobLog == null || evalJobLog.isBlank() || incidentCaseId == null || incidentCaseId.isBlank()) {
            return CaseVerdict.UNKNOWN; // log not (yet) available — check again next pass
        }
        for (String line : evalJobLog.lines().toList()) {
            if (line.startsWith("[PASS] " + incidentCaseId + " ")
                    || line.equals("[PASS] " + incidentCaseId)) {
                return CaseVerdict.PASSED;
            }
            if (line.startsWith("[FAIL] " + incidentCaseId + " ")
                    || line.equals("[FAIL] " + incidentCaseId)) {
                return CaseVerdict.FAILED;
            }
        }
        // The case never ran (not in the dataset, log truncated): that is a
        // broken gate, not a passed one.
        return CaseVerdict.FAILED;
    }
}
