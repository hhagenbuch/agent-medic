package io.github.hhagenbuch.medic.surgeon;

import io.github.hhagenbuch.blackbox.core.TraceReader;
import io.github.hhagenbuch.medic.diagnose.Diagnoser;
import io.github.hhagenbuch.medic.rules.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The full Surgeon circuit with a scripted model and a REAL MCP round-trip:
 * the fake LLM requests tool calls, the starter's MCP client executes them
 * against a spawned {@code MedicMcpServer} subprocess over stdio, and the
 * results flow back into the conversation. Only the model is fake.
 */
class SurgeonTest {

    private static final String PROPOSAL_JSON = """
            {"systemPrompt": "You are a support agent. NEVER state that an action succeeded \
            unless the tool result confirms it; if a tool call fails or defers, say exactly that.", \
            "rationale": "The trace shows send_email returned error=true (queued, not delivered) \
            yet the reply claimed the report was sent. The added instruction forbids success \
            claims without a confirming tool result."}""";

    @TempDir
    Path dir;

    Path bundle;
    Path promptFile;
    Path historyDir;

    @BeforeEach
    void seedOperatingRoom() throws IOException {
        // A REAL bundle, produced by the phase-1 Diagnoser from the example incident.
        Path trace = Path.of("examples/honesty-incident.trace.jsonl");
        bundle = new Diagnoser(Files.createDirectories(dir.resolve("incidents")))
                .diagnose(trace, TraceReader.readEvents(trace),
                        new Finding("honesty.claimed-sent-but-queued", 1,
                                "assistant claimed sent; send_email failed"))
                .orElseThrow();
        promptFile = Files.writeString(dir.resolve("prompt.txt"),
                "You are a support agent. Be brief and reassuring.");
        historyDir = Files.createDirectories(dir.resolve("history"));
        Files.writeString(historyDir.resolve("2026-07-20-gate.md"), "suite pass rate 0.94 (17/18)");
    }

    @Test
    void consultsAllThreeToolsOverRealStdioAndProposesAValidatedRepair() {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .callsTool("get_incident")
                .callsTool("get_current_prompt")
                .callsTool("get_eval_history")
                .answers(PROPOSAL_JSON);

        Surgeon.Repair repair = new Surgeon(llm).propose(bundle, promptFile, historyDir)
                .block(java.time.Duration.ofSeconds(60));

        assertThat(repair.toolsUsed())
                .containsExactly("get_incident", "get_current_prompt", "get_eval_history");
        assertThat(repair.proposal().systemPrompt()).contains("NEVER state that an action succeeded");
        assertThat(repair.proposal().rationale()).contains("send_email");
        assertThat(repair.promptDiff())
                .contains("--- current-prompt")
                .contains("+++ proposed-prompt")
                .contains("-You are a support agent. Be brief and reassuring.");

        // The evidence really crossed the stdio boundary: the last model call's
        // message history must contain tool results with the incident evidence,
        // the current prompt, and the eval history.
        String lastCall = llm.observedMessages.get(llm.observedMessages.size() - 1);
        assertThat(lastCall)
                .contains("honesty.claimed-sent-but-queued")   // from incident.json via get_incident
                .contains("Be brief and reassuring")           // from get_current_prompt
                .contains("suite pass rate 0.94");             // from get_eval_history
    }

    @Test
    void rejectsAProposalThatOverstepsItsAuthority() {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .answers("{\"systemPrompt\": \"x\", \"rationale\": \"y\", \"tools\": [\"shell\"]}");

        assertThatThrownBy(() -> new Surgeon(llm).propose(bundle, promptFile, historyDir)
                .block(java.time.Duration.ofSeconds(60)))
                .hasMessageContaining("tools")
                .hasMessageContaining("authority");
    }

    @Test
    void rejectsANoOpRepair() {
        ScriptedLlmClient llm = new ScriptedLlmClient()
                .answers("{\"systemPrompt\": \"You are a support agent. Be brief and reassuring.\", "
                        + "\"rationale\": \"looks fine to me\"}");

        assertThatThrownBy(() -> new Surgeon(llm).propose(bundle, promptFile, historyDir)
                .block(java.time.Duration.ofSeconds(60)))
                .hasMessageContaining("identical");
    }
}
