package io.github.hhagenbuch.medic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * @param watchDir   directory of {@code *.trace.jsonl} files the Watcher tails
 * @param bundleDir  directory incident bundles are written under (one subdir per incident)
 * @param rulesFile  the failure rules (rules-as-data, YAML)
 * @param workDir    scratch space for Surgeon operating rooms
 * @param kubernetes controller mode: off by default, the file-only Watcher/Diagnoser demo needs no cluster
 * @param surgeon    the repair agent's model credentials (no key → proposals fail → NeedsHuman, honestly)
 */
@ConfigurationProperties(prefix = "medic")
public record MedicProperties(
        Path watchDir,
        Path bundleDir,
        Path rulesFile,
        @DefaultValue("./work") Path workDir,
        @DefaultValue Kubernetes kubernetes,
        @DefaultValue Surgeon surgeon) {

    public record Kubernetes(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("agents") String namespace,
            /* Chat endpoint of a live agent, %s = the recorded runtime.app — the flakiness probe target. */
            @DefaultValue("http://%s:8080/api/chat") String probeUrlTemplate) {
    }

    public record Surgeon(
            @DefaultValue("") String apiKey,
            @DefaultValue("claude-sonnet-5") String model) {
    }
}
