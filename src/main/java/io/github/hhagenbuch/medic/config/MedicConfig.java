package io.github.hhagenbuch.medic.config;

import io.github.hhagenbuch.medic.diagnose.Diagnoser;
import io.github.hhagenbuch.medic.rules.RulesEngine;
import io.github.hhagenbuch.medic.rules.RulesLoader;
import io.github.hhagenbuch.medic.watch.TraceWatcher;
import io.github.hhagenbuch.medic.watch.TraceWatcher.IncidentListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MedicConfig {

    @Bean
    RulesEngine rulesEngine(MedicProperties props) {
        // Fails startup on a missing or invalid rules file — a Watcher with no
        // rules silently watches for nothing, which is worse than not starting.
        return new RulesEngine(RulesLoader.load(props.rulesFile()));
    }

    @Bean
    Diagnoser diagnoser(MedicProperties props) {
        return new Diagnoser(props.bundleDir());
    }

    @Bean
    TraceWatcher traceWatcher(MedicProperties props, RulesEngine engine, Diagnoser diagnoser,
                              ObjectProvider<IncidentListener> listener) {
        // In controller mode the listener is the ProposalCreator; file-only mode has none.
        return new TraceWatcher(props.watchDir(), engine, diagnoser,
                listener.getIfAvailable(() -> (bundle, finding, events) -> { }));
    }
}
