package io.github.hhagenbuch.medic.watch;

import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.blackbox.core.TraceReader;
import io.github.hhagenbuch.medic.diagnose.Diagnoser;
import io.github.hhagenbuch.medic.rules.Finding;
import io.github.hhagenbuch.medic.rules.RulesEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Tails the blackbox trace directory. Polling (not inotify) on purpose: traces
 * are append-only JSONL on what may be a shared volume, size-change detection
 * is enough, and a poll loop behaves identically everywhere kind runs.
 *
 * <p>Each pass re-reads any grown file in full and re-runs the rules; findings
 * dedupe naturally because the {@link Diagnoser} keys bundles by
 * {@code (session, turn, rule)} and writes each bundle once. {@link TraceReader}
 * tolerates a truncated final line, so reading mid-write is safe.
 */
public class TraceWatcher {

    private static final Logger log = LoggerFactory.getLogger(TraceWatcher.class);

    private final Path watchDir;
    private final RulesEngine engine;
    private final Diagnoser diagnoser;
    private final Map<Path, Long> seenSizes = new ConcurrentHashMap<>();

    public TraceWatcher(Path watchDir, RulesEngine engine, Diagnoser diagnoser) {
        this.watchDir = watchDir;
        this.engine = engine;
        this.diagnoser = diagnoser;
    }

    @Scheduled(fixedDelayString = "${medic.poll-millis:2000}")
    public void poll() {
        if (!Files.isDirectory(watchDir)) {
            return; // nothing recorded yet — the directory appears with the first trace
        }
        try (Stream<Path> files = Files.list(watchDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".trace.jsonl"))
                    .forEach(this::pollFile);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list " + watchDir, e);
        }
    }

    private void pollFile(Path trace) {
        long size;
        try {
            size = Files.size(trace);
        } catch (IOException e) {
            log.warn("cannot stat {} — skipping this pass", trace, e);
            return;
        }
        if (Long.valueOf(size).equals(seenSizes.get(trace))) {
            return; // unchanged since last pass
        }
        List<TraceEvent> events;
        try {
            events = TraceReader.readEvents(trace);
        } catch (IllegalStateException corrupt) {
            // Mid-file corruption is not a truncated tail; it will not heal on re-read.
            log.error("corrupt trace {} — skipping until it changes: {}", trace, corrupt.getMessage());
            seenSizes.put(trace, size);
            return;
        }
        for (Finding finding : engine.evaluate(events)) {
            diagnoser.diagnose(trace, events, finding);
        }
        seenSizes.put(trace, size);
    }
}
