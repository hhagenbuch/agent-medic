package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.medic.k8s.MedicStateMachine.CaseVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentCaseCheckTest {

    private static String logWithVerdict(String casesJson) {
        return """
                [PASS] greeting (120 ms)
                [FAIL] s-42-turn1-honesty (301 ms)

                suite: 2/3 cases passed (min-pass-rate 0.60) — report: eval-report.md
                VERDICT-JSON: {"schema":"agent-evals/verdict/v1","dataset":"suite","cases":[%s]}
                """.formatted(casesJson);
    }

    @Test
    void readsThePassVerdictFromTheJsonLine() {
        String log = logWithVerdict("""
                {"id":"s-42-turn1-honesty","passed":true,"required":true,"millis":301}""");
        assertThat(IncidentCaseCheck.verdict(log, "s-42-turn1-honesty")).isEqualTo(CaseVerdict.PASSED);
    }

    @Test
    void aFailedRequiredCaseIsFailed() {
        String log = logWithVerdict("""
                {"id":"s-42-turn1-honesty","passed":false,"required":true,"millis":301}""");
        assertThat(IncidentCaseCheck.verdict(log, "s-42-turn1-honesty")).isEqualTo(CaseVerdict.FAILED);
    }

    @Test
    void aFailedAdvisoryCaseIsSurfacedNotVetoed() {
        String log = logWithVerdict("""
                {"id":"s-42-turn1-honesty","passed":false,"required":false,"millis":301}""");
        assertThat(IncidentCaseCheck.verdict(log, "s-42-turn1-honesty"))
                .isEqualTo(CaseVerdict.FAILED_ADVISORY);
    }

    @Test
    void theHumanReadableLinesAreIgnored() {
        // The [FAIL] line above the verdict must not influence the outcome —
        // only the machine-readable verdict counts.
        String log = logWithVerdict("""
                {"id":"s-42-turn1-honesty","passed":true,"required":true,"millis":301}""");
        assertThat(log).contains("[FAIL] s-42-turn1-honesty"); // scraping would say FAILED
        assertThat(IncidentCaseCheck.verdict(log, "s-42-turn1-honesty")).isEqualTo(CaseVerdict.PASSED);
    }

    @Test
    void aCaseAbsentFromTheVerdictIsABrokenGate() {
        String log = logWithVerdict("""
                {"id":"something-else","passed":true,"required":false,"millis":10}""");
        assertThat(IncidentCaseCheck.verdict(log, "s-42-turn1-honesty")).isEqualTo(CaseVerdict.FAILED);
    }

    @Test
    void aCompletedLogWithoutAVerdictLineIsABrokenGate() {
        // e.g. the gate ran a pre-0.2.0 evals image: fail closed, never pass.
        String log = "[PASS] greeting (120 ms)\nsuite: 1/1 cases passed\n";
        assertThat(IncidentCaseCheck.verdict(log, "greeting")).isEqualTo(CaseVerdict.FAILED);
    }

    @Test
    void anUnparseableVerdictIsABrokenGate() {
        String log = "VERDICT-JSON: {not json\n";
        assertThat(IncidentCaseCheck.verdict(log, "x")).isEqualTo(CaseVerdict.FAILED);
    }

    @Test
    void theLastVerdictLineWins() {
        String log = logWithVerdict("""
                {"id":"c","passed":false,"required":true,"millis":1}""")
                + "VERDICT-JSON: {\"cases\":[{\"id\":\"c\",\"passed\":true,\"required\":true,\"millis\":1}]}\n";
        assertThat(IncidentCaseCheck.verdict(log, "c")).isEqualTo(CaseVerdict.PASSED);
    }

    @Test
    void missingLogMeansCheckAgainLater() {
        assertThat(IncidentCaseCheck.verdict(null, "x")).isEqualTo(CaseVerdict.UNKNOWN);
        assertThat(IncidentCaseCheck.verdict("", "x")).isEqualTo(CaseVerdict.UNKNOWN);
        assertThat(IncidentCaseCheck.verdict("some log", "")).isEqualTo(CaseVerdict.UNKNOWN);
    }
}
