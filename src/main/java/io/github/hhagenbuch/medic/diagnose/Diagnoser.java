package io.github.hhagenbuch.medic.diagnose;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.eval.EvalExporter;
import io.github.hhagenbuch.medic.rules.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a {@link Finding} into an incident bundle — deterministically, with no
 * LLM anywhere in this stage: the bundle is the evidence the Surgeon reads, the
 * gate replays, and the human reviews, so its chain of custody from the trace
 * must be mechanical.
 *
 * <p>A bundle is a directory under the bundle root:
 * <pre>
 * {bundleDir}/{sessionId}-turn{N}-{ruleId}/
 *   trace.jsonl     the full recorded session (verbatim copy)
 *   case.yaml       the agent-evals regression case exported from the turn
 *   incident.json   what fired, where, and the evidence — written last, so its
 *                   presence marks the bundle complete (and is the dedupe key)
 * </pre>
 */
public class Diagnoser {

    private static final Logger log = LoggerFactory.getLogger(Diagnoser.class);
    static final int PROBE_RUNS = 3;

    private final Path bundleDir;
    private final CaseProbe probe;

    public Diagnoser(Path bundleDir) {
        this(bundleDir, null);
    }

    /**
     * With a {@link CaseProbe}, the exported case is re-run {@value PROBE_RUNS}×
     * against the live agent before it may become a REQUIRED antibody: only a
     * stable failure (reproduces every run) earns {@code required}; anything
     * flaky stays advisory and is surfaced for human review. This is the
     * autoimmune guard (DESIGN.md §5): a flaky case promoted to required would
     * permanently and randomly veto every future promotion.
     */
    public Diagnoser(Path bundleDir, CaseProbe probe) {
        this.bundleDir = bundleDir;
        this.probe = probe;
    }

    /**
     * Writes the bundle for this finding unless one already exists.
     * Returns the bundle path if written, empty if it was already there.
     */
    public Optional<Path> diagnose(Path traceFile, List<TraceEvent> events, Finding finding) {
        String incidentId = incidentId(events, traceFile, finding);
        Path bundle = bundleDir.resolve(incidentId);
        if (Files.exists(bundle.resolve("incident.json"))) {
            return Optional.empty(); // already diagnosed — one incident, one bundle
        }
        try {
            Files.createDirectories(bundle);
            Files.copy(traceFile, bundle.resolve("trace.jsonl"), StandardCopyOption.REPLACE_EXISTING);

            String caseSkippedReason = exportCase(bundle, events, finding, incidentId);
            Disposition disposition = caseSkippedReason == null
                    ? dispose(bundle, events)
                    : new Disposition("advisory", "no case exported", null);

            ObjectNode incident = TraceEvent.mapper().createObjectNode();
            incident.put("incidentId", incidentId);
            incident.put("traceRef", traceFile.toString());
            incident.put("sessionId", sessionField(events, "sessionId"));
            incident.put("agent", runtimeField(events, "app"));
            incident.put("model", runtimeField(events, "model"));
            incident.put("turn", finding.turn());
            incident.put("rule", finding.ruleId());
            incident.put("evidence", finding.evidence());
            incident.put("caseExported", caseSkippedReason == null);
            if (caseSkippedReason != null) {
                incident.put("caseSkippedReason", caseSkippedReason);
            }
            incident.put("caseDisposition", disposition.value());
            incident.put("caseDispositionReason", disposition.reason());
            if (disposition.probeRuns() != null) {
                var runs = incident.putArray("probeRuns");
                disposition.probeRuns().forEach(runs::add);
            }
            incident.put("detectedAt", Instant.now().toString());
            Files.writeString(bundle.resolve("incident.json"),
                    incident.toPrettyString() + "\n", StandardCharsets.UTF_8);

            log.warn("incident {}: {} (bundle: {})", incidentId, finding.evidence(), bundle);
            return Optional.of(bundle);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write incident bundle " + bundle, e);
        }
    }

    record Disposition(String value, String reason, List<String> probeRuns) {
    }

    /**
     * Decides required-vs-advisory for the exported case. Only a probe-confirmed
     * STABLE failure earns {@code required}; no probe configured keeps the
     * pre-probe semantics (required) because the file-only mode has nothing
     * live to probe against — the guard is an in-cluster refinement.
     */
    private Disposition dispose(Path bundle, List<TraceEvent> events) throws IOException {
        if (probe == null) {
            return new Disposition("required", "not probed (no probe configured)", null);
        }
        String caseYaml = Files.readString(bundle.resolve("case.yaml"));
        String agentApp = runtimeField(events, "app");
        List<String> runs = new ArrayList<>(PROBE_RUNS);
        int passes = 0;
        boolean unavailable = false;
        for (int i = 0; i < PROBE_RUNS; i++) {
            var outcome = probe.run(caseYaml, agentApp);
            if (outcome.isEmpty()) {
                runs.add("error");
                unavailable = true;
            } else if (outcome.get()) {
                runs.add("pass");
                passes++;
            } else {
                runs.add("fail");
            }
        }
        if (unavailable) {
            return new Disposition("required", "probe unavailable — defaulting to required", runs);
        }
        if (passes == 0) {
            return new Disposition("required",
                    "stable failure: reproduced " + PROBE_RUNS + "/" + PROBE_RUNS + " probe runs", runs);
        }
        return new Disposition("advisory",
                "flaky: case passed " + passes + "/" + PROBE_RUNS + " probe runs — "
                        + "review before it can become a required antibody", runs);
    }

    /** Returns null if the case was exported, else the reason it could not be. */
    private String exportCase(Path bundle, List<TraceEvent> events, Finding finding, String incidentId)
            throws IOException {
        try {
            String yaml = EvalExporter.exportYaml(events, finding.turn(), incidentId);
            Files.writeString(bundle.resolve("case.yaml"), yaml, StandardCharsets.UTF_8);
            return null;
        } catch (IllegalArgumentException e) {
            // e.g. a runtime error outside any user turn — there is no prompt to replay
            return e.getMessage();
        }
    }

    private static String incidentId(List<TraceEvent> events, Path traceFile, Finding finding) {
        String session = sessionField(events, "sessionId");
        if (session.isEmpty()) {
            session = traceFile.getFileName().toString().replace(".trace.jsonl", "");
        }
        return sanitize(session) + "-turn" + finding.turn() + "-" + sanitize(finding.ruleId());
    }

    private static String sessionField(List<TraceEvent> events, String field) {
        return events.stream()
                .filter(e -> e.type().equals("session_start"))
                .map(e -> e.node().path(field).asText(""))
                .findFirst().orElse("");
    }

    private static String runtimeField(List<TraceEvent> events, String field) {
        return events.stream()
                .filter(e -> e.type().equals("session_start"))
                .map(e -> e.node().path("runtime").path(field).asText(""))
                .findFirst().orElse("");
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
