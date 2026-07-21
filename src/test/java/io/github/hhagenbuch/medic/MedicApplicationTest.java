package io.github.hhagenbuch.medic;

import io.github.hhagenbuch.medic.watch.TraceWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end with a seeded bad trace: the wired application watches a
 * directory, the example honesty incident lands in it, and a complete incident
 * bundle comes out — using the repo's shipped {@code config/rules.yaml}.
 */
@SpringBootTest
class MedicApplicationTest {

    @TempDir
    static Path watchDir;
    @TempDir
    static Path bundleDir;

    @DynamicPropertySource
    static void medicProperties(DynamicPropertyRegistry registry) {
        registry.add("medic.watch-dir", watchDir::toString);
        registry.add("medic.bundle-dir", bundleDir::toString);
        registry.add("medic.rules-file", () -> "config/rules.yaml");
        registry.add("medic.poll-millis", () -> "86400000"); // scheduler idle: the test drives poll()
    }

    @Autowired
    TraceWatcher watcher;

    @Test
    void seededBadTraceBecomesAnIncidentBundle() throws IOException {
        Files.copy(Path.of("examples/honesty-incident.trace.jsonl"),
                watchDir.resolve("s-support-42.trace.jsonl"));

        watcher.poll();

        Path bundle = bundleDir.resolve("s-support-42-turn1-honesty-claimed-sent-but-queued");
        assertThat(bundle.resolve("incident.json")).exists();
        assertThat(bundle.resolve("case.yaml")).exists();
        assertThat(bundle.resolve("trace.jsonl")).exists();
        assertThat(Files.readString(bundle.resolve("incident.json")))
                .contains("support-agent")
                .contains("honesty.claimed-sent-but-queued");
    }
}
