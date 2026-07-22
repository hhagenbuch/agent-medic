package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.medic.k8s.MedicStateMachine.RepairState;
import io.github.hhagenbuch.medic.surgeon.Surgeon;
import io.github.hhagenbuch.medic.surgeon.Surgeon.Repair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Runs Surgeon attempts off the reconcile thread. A repair takes as long as an
 * LLM takes; a reconciler must not block on it, so the reconciler starts an
 * attempt here and polls {@link #state} on requeue.
 *
 * <p>Each attempt gets a materialized operating room under the work root: the
 * current prompt (from the Agent's spec — the in-cluster source of truth) as a
 * file, and the gate history of prior attempts as the history directory the
 * medic MCP server serves. A failed attempt's report thereby becomes evidence
 * for the next attempt, without ever mutating the incident bundle.
 *
 * <p>State is in-memory: after a medic restart an in-flight attempt is simply
 * re-run — attempts are idempotent up to the gate, and the gate is idempotent
 * by PromptVersion name.
 */
public class SurgeonExecutor {

    private static final Logger log = LoggerFactory.getLogger(SurgeonExecutor.class);
    private static final Duration REPAIR_TIMEOUT = Duration.ofMinutes(5);

    private final Surgeon surgeon;
    private final Path workRoot;
    private final Map<String, CompletableFuture<Repair>> attempts = new ConcurrentHashMap<>();

    public SurgeonExecutor(Surgeon surgeon, Path workRoot) {
        this.surgeon = surgeon;
        this.workRoot = workRoot;
    }

    /** Record of a prior attempt, served to the Surgeon as eval history. */
    public record HistoryEntry(String name, String content) {
    }

    public void start(String attemptKey, Path bundleDir, String currentPrompt, List<HistoryEntry> history) {
        attempts.computeIfAbsent(attemptKey, key -> {
            Path room = materialize(key, currentPrompt, history);
            log.info("surgeon attempt '{}' starting (bundle {})", key, bundleDir.getFileName());
            return CompletableFuture.supplyAsync(() ->
                    surgeon.propose(bundleDir, room.resolve("prompt.txt"), room.resolve("history"))
                            .block(REPAIR_TIMEOUT));
        });
    }

    public RepairState state(String attemptKey) {
        CompletableFuture<Repair> future = attempts.get(attemptKey);
        if (future == null) {
            return RepairState.NONE;
        }
        if (!future.isDone()) {
            return RepairState.RUNNING;
        }
        return future.isCompletedExceptionally() ? RepairState.FAILED : RepairState.READY;
    }

    /** Only valid in state READY. */
    public Repair repair(String attemptKey) {
        try {
            return attempts.get(attemptKey).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading a completed repair", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("repair(" + attemptKey + ") called in a non-READY state", e);
        }
    }

    /** Only valid in state FAILED. */
    public String failure(String attemptKey) {
        try {
            attempts.get(attemptKey).get();
            throw new IllegalStateException("failure(" + attemptKey + ") called in a non-FAILED state");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading a failed repair", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        }
    }

    private Path materialize(String attemptKey, String currentPrompt, List<HistoryEntry> history) {
        try {
            Path room = Files.createDirectories(workRoot.resolve(attemptKey));
            Files.writeString(room.resolve("prompt.txt"), currentPrompt == null ? "" : currentPrompt);
            Path historyDir = Files.createDirectories(room.resolve("history"));
            for (HistoryEntry entry : history) {
                Files.writeString(historyDir.resolve(entry.name()), entry.content());
            }
            return room;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to materialize operating room for " + attemptKey, e);
        }
    }
}
