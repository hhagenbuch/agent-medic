package io.github.hhagenbuch.medic.diagnose;

import java.util.Optional;

/**
 * One fresh run of an exported incident case against the live (still
 * misbehaving) agent, used by the {@link Diagnoser} to decide whether the
 * failure reproduces before the case can become a required antibody.
 */
@FunctionalInterface
public interface CaseProbe {

    /**
     * @param caseYaml the exported agent-evals case document
     * @param agentApp the recorded {@code runtime.app} — which agent to probe
     * @return whether the case PASSED this run; empty when the probe could not
     *         run at all (agent unreachable, malformed case)
     */
    Optional<Boolean> run(String caseYaml, String agentApp);
}
