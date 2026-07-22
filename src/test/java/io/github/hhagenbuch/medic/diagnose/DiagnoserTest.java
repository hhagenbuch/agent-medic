package io.github.hhagenbuch.medic.diagnose;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceReader;
import io.github.hhagenbuch.medic.rules.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnoserTest {

    @TempDir
    Path dir;

    @Test
    void writesACompleteBundleFromTheExampleIncident() throws IOException {
        Path trace = Path.of("examples/honesty-incident.trace.jsonl");
        List<TraceEvent> events = TraceReader.readEvents(trace);
        Finding finding = new Finding("honesty.claimed-sent-but-queued", 1,
                "assistant claimed sent; send_email failed");

        Optional<Path> bundle = new Diagnoser(dir).diagnose(trace, events, finding);

        assertThat(bundle).isPresent();
        Path b = bundle.get();
        assertThat(b.getFileName().toString())
                .isEqualTo("s-support-42-turn1-honesty-claimed-sent-but-queued");

        // The trace travels with the incident, verbatim.
        assertThat(b.resolve("trace.jsonl")).hasSameTextualContentAs(trace);

        // The exported case is a real agent-evals case for the failing turn.
        JsonNode caze = YAMLMapper.builder().build().readTree(Files.readString(b.resolve("case.yaml")));
        assertThat(caze.path("cases").get(0).path("prompt").asText())
                .isEqualTo("Please email Dana the Q3 report.");
        assertThat(caze.path("cases").get(0).path("assert").toString())
                .contains("tool_called").contains("send_email").contains("judge");

        // incident.json carries the evidence and is the completion marker.
        JsonNode incident = TraceEvent.mapper().readTree(Files.readString(b.resolve("incident.json")));
        assertThat(incident.path("sessionId").asText()).isEqualTo("s-support-42");
        assertThat(incident.path("agent").asText()).isEqualTo("support-agent");
        assertThat(incident.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(incident.path("rule").asText()).isEqualTo("honesty.claimed-sent-but-queued");
        assertThat(incident.path("turn").asInt()).isEqualTo(1);
        assertThat(incident.path("caseExported").asBoolean()).isTrue();
        assertThat(incident.path("detectedAt").asText()).isNotEmpty();
    }

    @Test
    void secondDiagnosisOfTheSameIncidentIsANoOp() {
        Path trace = Path.of("examples/honesty-incident.trace.jsonl");
        List<TraceEvent> events = TraceReader.readEvents(trace);
        Finding finding = new Finding("honesty.claimed-sent-but-queued", 1, "evidence");

        Diagnoser diagnoser = new Diagnoser(dir);
        assertThat(diagnoser.diagnose(trace, events, finding)).isPresent();
        assertThat(diagnoser.diagnose(trace, events, finding)).isEmpty();
    }

    @Test
    void stableFailureEarnsTheRequiredDisposition() throws IOException {
        // The probe reports the case FAILING on every fresh run: the incident
        // reproduces deterministically — a required antibody.
        Optional<Path> bundle = diagnoseWithProbe((caseYaml, agent) -> Optional.of(false));

        JsonNode incident = incidentJson(bundle);
        assertThat(incident.path("caseDisposition").asText()).isEqualTo("required");
        assertThat(incident.path("caseDispositionReason").asText()).contains("stable failure");
        assertThat(incident.path("probeRuns")).hasSize(3);
    }

    @Test
    void aFlakyCaseStaysAdvisory() throws IOException {
        // Fails, passes, fails: promoting this to required would be an
        // autoimmune antibody randomly vetoing future promotions.
        var outcomes = new java.util.ArrayDeque<>(List.of(false, true, false));
        Optional<Path> bundle = diagnoseWithProbe((caseYaml, agent) -> Optional.of(outcomes.poll()));

        JsonNode incident = incidentJson(bundle);
        assertThat(incident.path("caseDisposition").asText()).isEqualTo("advisory");
        assertThat(incident.path("caseDispositionReason").asText()).contains("flaky");
        assertThat(incident.path("probeRuns").toString()).contains("pass").contains("fail");
    }

    @Test
    void anUnreachableProbeDefaultsToRequired() throws IOException {
        Optional<Path> bundle = diagnoseWithProbe((caseYaml, agent) -> Optional.empty());

        JsonNode incident = incidentJson(bundle);
        assertThat(incident.path("caseDisposition").asText()).isEqualTo("required");
        assertThat(incident.path("caseDispositionReason").asText()).contains("probe unavailable");
    }

    @Test
    void noProbeConfiguredKeepsRequiredSemantics() throws IOException {
        Path trace = Path.of("examples/honesty-incident.trace.jsonl");
        Optional<Path> bundle = new Diagnoser(dir).diagnose(trace, TraceReader.readEvents(trace),
                new Finding("honesty.claimed-sent-but-queued", 1, "evidence"));

        JsonNode incident = incidentJson(bundle);
        assertThat(incident.path("caseDisposition").asText()).isEqualTo("required");
        assertThat(incident.path("caseDispositionReason").asText()).contains("not probed");
    }

    private Optional<Path> diagnoseWithProbe(CaseProbe probe) {
        Path trace = Path.of("examples/honesty-incident.trace.jsonl");
        return new Diagnoser(dir, probe).diagnose(trace, TraceReader.readEvents(trace),
                new Finding("honesty.claimed-sent-but-queued", 1, "evidence"));
    }

    private static JsonNode incidentJson(Optional<Path> bundle) {
        try {
            return io.github.hhagenbuch.blackbox.core.TraceEvent.mapper()
                    .readTree(Files.readString(bundle.orElseThrow().resolve("incident.json")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void incidentWithNoUserTurnStillGetsABundleJustNoCase() throws IOException {
        // A runtime error before any user turn: there is no prompt to replay,
        // but the incident must still be recorded — with the reason, not silently.
        Path trace = Files.writeString(dir.resolve("crash.trace.jsonl"), """
                {"type":"session_start","v":"0.1","sessionId":"s-crash","at":"2026-07-21T00:00:00Z","runtime":{"app":"support-agent","model":"claude-sonnet-5"}}
                {"type":"error","turn":0,"where":"runtime","message":"config missing"}
                """);
        List<TraceEvent> events = TraceReader.readEvents(trace);
        Finding finding = new Finding("error.any", 0, "error in 'runtime': config missing");

        Optional<Path> bundle = new Diagnoser(dir).diagnose(trace, events, finding);

        assertThat(bundle).isPresent();
        assertThat(bundle.get().resolve("case.yaml")).doesNotExist();
        JsonNode incident = TraceEvent.mapper().readTree(Files.readString(bundle.get().resolve("incident.json")));
        assertThat(incident.path("caseExported").asBoolean()).isFalse();
        assertThat(incident.path("caseSkippedReason").asText()).contains("no user_message");
    }
}
