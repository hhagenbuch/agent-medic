package io.github.hhagenbuch.medic.k8s;

/**
 * The MedicProposal lifecycle as a pure function (the operator repo's house
 * pattern): {@code (phase, repair, promptVersion, incidentCase, approval,
 * failedAttempts, maxAttempts) → (action, nextPhase)}. The reconciler only
 * executes the returned action; every policy decision lives here, exhaustively
 * unit-testable.
 *
 * <pre>
 * Proposing ──► Gating ──► AwaitingApproval ──► Promoted
 *     ▲            │               │
 *     │ (retry)    │ (gate fail /  │ (human rejects)
 *     └────────────┘  case fail)   ▼
 *          │                   NeedsHuman
 *          └── (maxAttempts) ──►
 * </pre>
 *
 * The two-bar gate: the operator's canary enforces the aggregate min-pass-rate;
 * medic additionally requires the INCIDENT case to pass by id ({@code
 * incidentCase}) — an aggregate 0.9 can be met while the one case that started
 * all this still fails. And two authority rules are encoded outright: a human
 * rejection is terminal (no retry loop past a human's "no"), and exhausted
 * attempts mean the machine says so instead of trying forever.
 */
public final class MedicStateMachine {

    public enum MedicPhase {
        PROPOSING("Proposing"),
        GATING("Gating"),
        AWAITING_APPROVAL("AwaitingApproval"),
        PROMOTED("Promoted"),
        NEEDS_HUMAN("NeedsHuman");

        private final String value;

        MedicPhase(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static MedicPhase fromStatus(String status) {
            if (status != null) {
                for (MedicPhase phase : values()) {
                    if (phase.value.equals(status)) {
                        return phase;
                    }
                }
            }
            return PROPOSING;
        }
    }

    /** Observed state of the current Surgeon attempt. */
    public enum RepairState {
        NONE, RUNNING, READY, FAILED
    }

    /** Observed phase of the current attempt's PromptVersion. */
    public enum PvState {
        NONE, IN_FLIGHT, AWAITING_APPROVAL, PROMOTED, ROLLED_BACK
    }

    /** The second gate bar: the incident's own case at the eval gate. */
    public enum CaseVerdict {
        UNKNOWN, PASSED, FAILED
    }

    /** The {@code medic.hhagenbuch.io/approved} annotation on the MedicProposal. */
    public enum Approval {
        NONE, APPROVED, REJECTED
    }

    public enum Action {
        START_SURGEON, WAIT, CREATE_GATE, CHECK_INCIDENT_CASE, HOLD_FOR_HUMAN,
        REJECT_PV, APPROVE_PV, RECORD_FAILURE, MERGE_ANTIBODY, GIVE_UP, DONE
    }

    public record Decision(Action action, MedicPhase nextPhase) {
    }

    private MedicStateMachine() {
    }

    public static Decision decide(MedicPhase phase, RepairState repair, PvState pv,
                                  CaseVerdict incidentCase, Approval approval,
                                  int failedAttempts, int maxAttempts) {
        return switch (phase) {
            case PROPOSING -> switch (repair) {
                case NONE -> failedAttempts >= maxAttempts
                        ? new Decision(Action.GIVE_UP, MedicPhase.NEEDS_HUMAN)
                        : new Decision(Action.START_SURGEON, MedicPhase.PROPOSING);
                case RUNNING -> new Decision(Action.WAIT, MedicPhase.PROPOSING);
                case READY -> new Decision(Action.CREATE_GATE, MedicPhase.GATING);
                // An invalid proposal consumes the attempt; the next pass retries or gives up.
                case FAILED -> new Decision(Action.RECORD_FAILURE, MedicPhase.PROPOSING);
            };
            case GATING -> switch (pv) {
                case NONE, IN_FLIGHT -> new Decision(Action.WAIT, MedicPhase.GATING);
                case AWAITING_APPROVAL -> switch (incidentCase) {
                    case UNKNOWN -> new Decision(Action.CHECK_INCIDENT_CASE, MedicPhase.GATING);
                    case PASSED -> new Decision(Action.HOLD_FOR_HUMAN, MedicPhase.AWAITING_APPROVAL);
                    // Aggregate bar met, incident bar not: this fix does not fix the incident.
                    case FAILED -> new Decision(Action.REJECT_PV, MedicPhase.GATING);
                };
                case ROLLED_BACK -> new Decision(Action.RECORD_FAILURE, MedicPhase.PROPOSING);
                // Defensive: requireApproval means the operator should never promote unbidden,
                // but if it happened the antibody must still be merged.
                case PROMOTED -> new Decision(Action.MERGE_ANTIBODY, MedicPhase.PROMOTED);
            };
            case AWAITING_APPROVAL -> switch (pv) {
                case PROMOTED -> new Decision(Action.MERGE_ANTIBODY, MedicPhase.PROMOTED);
                // A rollback here means a human rejected (directly on the PV, or via medic) or
                // the hold was broken out-of-band. Either way a human decided or must decide.
                case ROLLED_BACK -> new Decision(Action.GIVE_UP, MedicPhase.NEEDS_HUMAN);
                default -> switch (approval) {
                    case APPROVED -> new Decision(Action.APPROVE_PV, MedicPhase.AWAITING_APPROVAL);
                    // A human said no. That is terminal — medic never argues with its reviewer.
                    case REJECTED -> new Decision(Action.REJECT_PV, MedicPhase.NEEDS_HUMAN);
                    case NONE -> new Decision(Action.WAIT, MedicPhase.AWAITING_APPROVAL);
                };
            };
            case PROMOTED -> new Decision(Action.DONE, MedicPhase.PROMOTED);
            case NEEDS_HUMAN -> new Decision(Action.DONE, MedicPhase.NEEDS_HUMAN);
        };
    }
}
