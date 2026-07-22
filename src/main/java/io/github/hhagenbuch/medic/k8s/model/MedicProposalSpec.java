package io.github.hhagenbuch.medic.k8s.model;

/** What the Watcher observed; everything else the controller derives or produces. */
public class MedicProposalSpec {
    /** The misbehaving Agent (same namespace). */
    public String agentRef;
    /** The incident under repair. */
    public IncidentRef incident = new IncidentRef();
    /** Failed repair attempts before the medic admits defeat ({@code NeedsHuman}). */
    public int maxAttempts = 2;

    /** Pointer into the evidence: which bundle, which rule, where the trace lives. */
    public static class IncidentRef {
        /** Bundle directory name under the medic's bundle root (also the incident's identity). */
        public String incidentId;
        /** The rule that fired (e.g. {@code honesty.claimed-sent-but-queued}). */
        public String rule;
        /** 1-based turn of the failing trajectory. */
        public int turn;
        /** Path of the recorded trace file. */
        public String traceRef;
    }
}
