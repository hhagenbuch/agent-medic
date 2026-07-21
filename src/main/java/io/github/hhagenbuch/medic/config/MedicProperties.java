package io.github.hhagenbuch.medic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * @param watchDir  directory of {@code *.trace.jsonl} files the Watcher tails
 * @param bundleDir directory incident bundles are written under (one subdir per incident)
 * @param rulesFile the failure rules (rules-as-data, YAML)
 */
@ConfigurationProperties(prefix = "medic")
public record MedicProperties(Path watchDir, Path bundleDir, Path rulesFile) {
}
