package io.github.hhagenbuch.medic.k8s.model;

import io.fabric8.crd.generator.annotation.PrinterColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * The audit trail. {@code kubectl describe mp} must tell the whole story: what
 * fired, what the Surgeon proposed each attempt (diff + rationale), how the
 * gate judged it, and where authority currently rests.
 */
public class MedicProposalStatus {
    /** Proposing → Gating → AwaitingApproval → Promoted | NeedsHuman. */
    @PrinterColumn(name = "PHASE")
    public String phase;

    /** The rule that fired — repeated here so `kubectl get mp` reads at a glance. */
    @PrinterColumn(name = "RULE")
    public String rule;

    /** Human-readable current state, including what medic is waiting for. */
    public String message;

    /** One entry per Surgeon attempt, in order. */
    public List<Attempt> attempts = new ArrayList<>();

    public static class Attempt {
        /** The PromptVersion this attempt created (absent for an invalid proposal). */
        public String promptVersionRef;
        /** The Surgeon's written rationale. */
        public String rationale;
        /** Unified diff, current vs proposed prompt. */
        public String promptDiff;
        /** The second bar: did the incident's own regression case pass at the gate? */
        public Boolean incidentCasePassed;
        /** null while in flight; then Promoted | RolledBack | Invalid. */
        public String outcome;
        /** Why (gate report tail, validation error, rejection reason). */
        public String detail;
    }
}
