package io.github.hhagenbuch.medic.watch;

import io.github.hhagenbuch.blackbox.core.Redactor;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceWriter;
import io.github.hhagenbuch.medic.diagnose.Diagnoser;
import io.github.hhagenbuch.medic.rules.RuleSpec;
import io.github.hhagenbuch.medic.rules.RulesEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tailing contract: a trace that grows between polls is re-evaluated, each
 * incident is bundled exactly once, and a mid-write partial line never breaks a
 * pass.
 */
class TraceWatcherTest {

    @TempDir
    Path watchDir;
    @TempDir
    Path bundleDir;

    TraceWatcher watcher;

    @BeforeEach
    void setUp() {
        RulesEngine engine = new RulesEngine(List.of(
                new RuleSpec("error.any", RuleSpec.Type.ERROR_EVENT, null, null, null, null, null),
                new RuleSpec("honesty.claimed-sent", RuleSpec.Type.CLAIM_WITHOUT_TOOL,
                        Pattern.compile("(?i)\\bsent\\b"), "send_email", null, null, null)));
        watcher = new TraceWatcher(watchDir, engine, new Diagnoser(bundleDir));
    }

    @Test
    void flagsAGrowingTraceOncePerIncident() throws IOException {
        Path trace = watchDir.resolve("s-1.trace.jsonl");
        try (TraceWriter w = new TraceWriter(trace, Redactor.none(), false)) {
            w.write(TraceEvent.sessionStart("0.1", "s-1", "2026-07-21T00:00:00Z", "support-agent", "claude-sonnet-5"));
            w.write(TraceEvent.userMessage(1, "email Dana the report"));
            w.write(TraceEvent.assistantMessage(1, "Done, I've sent it."));

            watcher.poll();
            assertThat(bundles()).containsExactly("s-1-turn1-honesty-claimed-sent");

            // Unchanged file, repeated polls: nothing new.
            watcher.poll();
            watcher.poll();
            assertThat(bundles()).hasSize(1);

            // The trace grows with a second incident — only the new one is bundled.
            w.write(TraceEvent.userMessage(2, "and text Alex"));
            w.write(TraceEvent.error(2, "tool", "sms gateway down"));
            watcher.poll();
            assertThat(bundles()).containsExactlyInAnyOrder(
                    "s-1-turn1-honesty-claimed-sent", "s-1-turn2-error-any");
        }
    }

    @Test
    void toleratesAMidWritePartialFinalLine() throws IOException {
        Path trace = watchDir.resolve("s-2.trace.jsonl");
        try (TraceWriter w = new TraceWriter(trace, Redactor.none(), false)) {
            w.write(TraceEvent.sessionStart("0.1", "s-2", "2026-07-21T00:00:00Z", "support-agent", "claude-sonnet-5"));
            w.write(TraceEvent.error(1, "llm", "overloaded"));
        }
        // Simulate a writer caught mid-line: a truncated tail is expected, not corruption.
        Files.writeString(trace, "{\"type\":\"assistant_mes", java.nio.file.StandardOpenOption.APPEND);

        watcher.poll();
        assertThat(bundles()).containsExactly("s-2-turn1-error-any");
    }

    @Test
    void healthyTraceProducesNoBundles() throws IOException {
        try (TraceWriter w = new TraceWriter(watchDir.resolve("s-3.trace.jsonl"), Redactor.none(), false)) {
            w.write(TraceEvent.sessionStart("0.1", "s-3", "2026-07-21T00:00:00Z", "support-agent", "claude-sonnet-5"));
            w.write(TraceEvent.userMessage(1, "what is 2+2?"));
            w.write(TraceEvent.assistantMessage(1, "4."));
            w.write(TraceEvent.sessionEnd("2026-07-21T00:00:01Z"));
        }
        watcher.poll();
        assertThat(bundles()).isEmpty();
    }

    private List<String> bundles() throws IOException {
        try (Stream<Path> children = Files.list(bundleDir)) {
            return children.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
