package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.blackbox.core.TraceReader;
import io.github.hhagenbuch.medic.diagnose.Diagnoser;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.RepairState;
import io.github.hhagenbuch.medic.rules.Finding;
import io.github.hhagenbuch.medic.surgeon.Surgeon;
import io.github.hhagenbuch.medic.surgeon.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The executor's contract with the reconciler: NONE before start, RUNNING
 * while the (real, subprocess-backed) Surgeon works, then READY with the
 * repair or FAILED with the reason — and the materialized operating room
 * serves the prior attempts as history.
 */
class SurgeonExecutorTest {

    @TempDir
    Path dir;

    private Path realBundle() throws Exception {
        Path trace = Path.of("examples/honesty-incident.trace.jsonl");
        return new Diagnoser(Files.createDirectories(dir.resolve("incidents")))
                .diagnose(trace, TraceReader.readEvents(trace),
                        new Finding("honesty.claimed-sent-but-queued", 1, "claimed sent; send failed"))
                .orElseThrow();
    }

    @Test
    void runsAnAttemptToReadyAndServesHistoryToTheSurgeon() throws Exception {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .callsTool("get_eval_history")
                .answers("{\"systemPrompt\": \"Never claim success without a confirming tool result.\", "
                        + "\"rationale\": \"the trace shows a failed send\"}");
        SurgeonExecutor executor = new SurgeonExecutor(new Surgeon(llm), dir.resolve("work"));

        assertThat(executor.state("mp-a1")).isEqualTo(RepairState.NONE);
        executor.start("mp-a1", realBundle(), "Be brief and reassuring.",
                List.of(new SurgeonExecutor.HistoryEntry("attempt-1.md",
                        "outcome: RolledBack\ndetail: gate failed at 0.5")));

        await().until(() -> executor.state("mp-a1") != RepairState.RUNNING);
        assertThat(executor.state("mp-a1")).isEqualTo(RepairState.READY);
        assertThat(executor.repair("mp-a1").proposal().systemPrompt()).contains("Never claim success");

        // The prior attempt's gate report crossed the MCP boundary as eval history.
        String lastCall = llm.observedMessages.get(llm.observedMessages.size() - 1);
        assertThat(lastCall).contains("gate failed at 0.5");
    }

    @Test
    void anInvalidProposalEndsFailedWithTheValidatorsReason() throws Exception {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .answers("{\"systemPrompt\": \"x\", \"rationale\": \"y\", \"model\": \"claude-opus-4-8\"}");
        SurgeonExecutor executor = new SurgeonExecutor(new Surgeon(llm), dir.resolve("work"));

        executor.start("mp-a1", realBundle(), "Be brief.", List.of());
        await().until(() -> executor.state("mp-a1") != RepairState.RUNNING);

        assertThat(executor.state("mp-a1")).isEqualTo(RepairState.FAILED);
        assertThat(executor.failure("mp-a1")).contains("model").contains("authority");
    }

    @Test
    void startIsIdempotentPerAttemptKey() throws Exception {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .answers("{\"systemPrompt\": \"Better prompt.\", \"rationale\": \"why\"}");
        SurgeonExecutor executor = new SurgeonExecutor(new Surgeon(llm), dir.resolve("work"));
        Path bundle = realBundle();

        executor.start("mp-a1", bundle, "Be brief.", List.of());
        executor.start("mp-a1", bundle, "Be brief.", List.of()); // second start: no second run
        await().until(() -> executor.state("mp-a1") != RepairState.RUNNING);

        assertThat(executor.state("mp-a1")).isEqualTo(RepairState.READY);
        // One scripted answer was enough — a duplicate run would have drained the script.
    }
}
