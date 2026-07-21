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

    private final Path bundleDir;

    public Diagnoser(Path bundleDir) {
        this.bundleDir = bundleDir;
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
            incident.put("detectedAt", Instant.now().toString());
            Files.writeString(bundle.resolve("incident.json"),
                    incident.toPrettyString() + "\n", StandardCharsets.UTF_8);

            log.warn("incident {}: {} (bundle: {})", incidentId, finding.evidence(), bundle);
            return Optional.of(bundle);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write incident bundle " + bundle, e);
        }
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
