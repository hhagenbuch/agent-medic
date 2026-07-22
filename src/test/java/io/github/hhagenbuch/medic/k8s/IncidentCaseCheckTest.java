package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.medic.k8s.MedicStateMachine.CaseVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentCaseCheckTest {

    private static final String LOG = """
            [PASS] greeting (120 ms)
            [FAIL] s-42-turn1-honesty (301 ms)
            [PASS] refund-policy (88 ms)

            support-suite: 2/3 cases passed (min-pass-rate 0.60) — report: eval-report.md
            """;

    @Test
    void findsTheIncidentCaseVerdictById() {
        assertThat(IncidentCaseCheck.verdict(LOG, "s-42-turn1-honesty")).isEqualTo(CaseVerdict.FAILED);
        assertThat(IncidentCaseCheck.verdict(LOG, "greeting")).isEqualTo(CaseVerdict.PASSED);
    }

    @Test
    void idPrefixesDoNotFalseMatch() {
        // "greeting" must not match "[PASS] greeting-v2".
        String log = "[PASS] greeting-v2 (10 ms)\n[FAIL] greeting (11 ms)\n";
        assertThat(IncidentCaseCheck.verdict(log, "greeting")).isEqualTo(CaseVerdict.FAILED);
    }

    @Test
    void aCaseThatNeverRanIsABrokenGateNotAPass() {
        assertThat(IncidentCaseCheck.verdict(LOG, "not-in-the-dataset")).isEqualTo(CaseVerdict.FAILED);
    }

    @Test
    void missingLogMeansCheckAgainLater() {
        assertThat(IncidentCaseCheck.verdict(null, "x")).isEqualTo(CaseVerdict.UNKNOWN);
        assertThat(IncidentCaseCheck.verdict("", "x")).isEqualTo(CaseVerdict.UNKNOWN);
        assertThat(IncidentCaseCheck.verdict(LOG, "")).isEqualTo(CaseVerdict.UNKNOWN);
    }
}
