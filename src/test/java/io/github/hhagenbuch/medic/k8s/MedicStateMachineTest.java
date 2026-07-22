package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.medic.k8s.MedicStateMachine.Action;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.Approval;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.CaseVerdict;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.Decision;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.MedicPhase;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.PvState;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.RepairState;
import org.junit.jupiter.api.Test;

import static io.github.hhagenbuch.medic.k8s.MedicStateMachine.decide;
import static org.assertj.core.api.Assertions.assertThat;

class MedicStateMachineTest {

    private static final int MAX = 2;

    private static Decision at(MedicPhase phase, RepairState repair, PvState pv,
                               CaseVerdict verdict, Approval approval, int failed) {
        return decide(phase, repair, pv, verdict, approval, failed, MAX);
    }

    // --- Proposing ---

    @Test
    void freshProposalStartsTheSurgeon() {
        assertThat(at(MedicPhase.PROPOSING, RepairState.NONE, PvState.NONE,
                CaseVerdict.UNKNOWN, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.START_SURGEON, MedicPhase.PROPOSING));
    }

    @Test
    void runningSurgeonMeansWait() {
        assertThat(at(MedicPhase.PROPOSING, RepairState.RUNNING, PvState.NONE,
                CaseVerdict.UNKNOWN, Approval.NONE, 0).action()).isEqualTo(Action.WAIT);
    }

    @Test
    void readyRepairCreatesTheGate() {
        assertThat(at(MedicPhase.PROPOSING, RepairState.READY, PvState.NONE,
                CaseVerdict.UNKNOWN, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.CREATE_GATE, MedicPhase.GATING));
    }

    @Test
    void invalidProposalConsumesTheAttempt() {
        assertThat(at(MedicPhase.PROPOSING, RepairState.FAILED, PvState.NONE,
                CaseVerdict.UNKNOWN, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.RECORD_FAILURE, MedicPhase.PROPOSING));
    }

    @Test
    void exhaustedAttemptsMeanNeedsHuman() {
        // Two failed repairs: the machine is out of its depth and says so.
        assertThat(at(MedicPhase.PROPOSING, RepairState.NONE, PvState.NONE,
                CaseVerdict.UNKNOWN, Approval.NONE, MAX))
                .isEqualTo(new Decision(Action.GIVE_UP, MedicPhase.NEEDS_HUMAN));
    }

    @Test
    void oneFailureStillRetries() {
        assertThat(at(MedicPhase.PROPOSING, RepairState.NONE, PvState.NONE,
                CaseVerdict.UNKNOWN, Approval.NONE, 1).action()).isEqualTo(Action.START_SURGEON);
    }

    // --- Gating: the two-bar rule ---

    @Test
    void gatingWaitsWhileTheCanaryRuns() {
        assertThat(at(MedicPhase.GATING, RepairState.NONE, PvState.IN_FLIGHT,
                CaseVerdict.UNKNOWN, Approval.NONE, 0).action()).isEqualTo(Action.WAIT);
    }

    @Test
    void aggregatePassTriggersTheIncidentCaseCheck() {
        assertThat(at(MedicPhase.GATING, RepairState.NONE, PvState.AWAITING_APPROVAL,
                CaseVerdict.UNKNOWN, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.CHECK_INCIDENT_CASE, MedicPhase.GATING));
    }

    @Test
    void bothBarsPassedHoldsForTheHuman() {
        assertThat(at(MedicPhase.GATING, RepairState.NONE, PvState.AWAITING_APPROVAL,
                CaseVerdict.PASSED, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.HOLD_FOR_HUMAN, MedicPhase.AWAITING_APPROVAL));
    }

    @Test
    void aggregatePassButIncidentCaseFailIsRejected() {
        // The load-bearing case: a fix that clears the average but not the
        // incident is NOT a fix.
        assertThat(at(MedicPhase.GATING, RepairState.NONE, PvState.AWAITING_APPROVAL,
                CaseVerdict.FAILED, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.REJECT_PV, MedicPhase.GATING));
    }

    @Test
    void gateRollbackRecordsTheFailureAndReturnsToProposing() {
        assertThat(at(MedicPhase.GATING, RepairState.NONE, PvState.ROLLED_BACK,
                CaseVerdict.UNKNOWN, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.RECORD_FAILURE, MedicPhase.PROPOSING));
    }

    @Test
    void unexpectedPromotionStillMergesTheAntibody() {
        assertThat(at(MedicPhase.GATING, RepairState.NONE, PvState.PROMOTED,
                CaseVerdict.UNKNOWN, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.MERGE_ANTIBODY, MedicPhase.PROMOTED));
    }

    // --- Awaiting approval: where authority terminates ---

    @Test
    void noAnnotationMeansWaitIndefinitely() {
        assertThat(at(MedicPhase.AWAITING_APPROVAL, RepairState.NONE, PvState.AWAITING_APPROVAL,
                CaseVerdict.PASSED, Approval.NONE, 0).action()).isEqualTo(Action.WAIT);
    }

    @Test
    void humanApprovalReleasesThePromptVersion() {
        assertThat(at(MedicPhase.AWAITING_APPROVAL, RepairState.NONE, PvState.AWAITING_APPROVAL,
                CaseVerdict.PASSED, Approval.APPROVED, 0))
                .isEqualTo(new Decision(Action.APPROVE_PV, MedicPhase.AWAITING_APPROVAL));
    }

    @Test
    void humanRejectionIsTerminal() {
        // Medic never argues with its reviewer: a "no" ends the loop, no retry.
        assertThat(at(MedicPhase.AWAITING_APPROVAL, RepairState.NONE, PvState.AWAITING_APPROVAL,
                CaseVerdict.PASSED, Approval.REJECTED, 0))
                .isEqualTo(new Decision(Action.REJECT_PV, MedicPhase.NEEDS_HUMAN));
    }

    @Test
    void operatorPromotionCompletesWithTheAntibodyMerge() {
        assertThat(at(MedicPhase.AWAITING_APPROVAL, RepairState.NONE, PvState.PROMOTED,
                CaseVerdict.PASSED, Approval.APPROVED, 0))
                .isEqualTo(new Decision(Action.MERGE_ANTIBODY, MedicPhase.PROMOTED));
    }

    @Test
    void outOfBandRollbackWhileHoldingNeedsAHuman() {
        assertThat(at(MedicPhase.AWAITING_APPROVAL, RepairState.NONE, PvState.ROLLED_BACK,
                CaseVerdict.PASSED, Approval.NONE, 0))
                .isEqualTo(new Decision(Action.GIVE_UP, MedicPhase.NEEDS_HUMAN));
    }

    // --- Terminal ---

    @Test
    void terminalPhasesStayPut() {
        assertThat(at(MedicPhase.PROMOTED, RepairState.NONE, PvState.PROMOTED,
                CaseVerdict.PASSED, Approval.APPROVED, 0).action()).isEqualTo(Action.DONE);
        assertThat(at(MedicPhase.NEEDS_HUMAN, RepairState.NONE, PvState.ROLLED_BACK,
                CaseVerdict.FAILED, Approval.REJECTED, MAX).action()).isEqualTo(Action.DONE);
    }

    @Test
    void phaseParsesFromStatusDefaultingToProposing() {
        assertThat(MedicPhase.fromStatus(null)).isEqualTo(MedicPhase.PROPOSING);
        assertThat(MedicPhase.fromStatus("Gating")).isEqualTo(MedicPhase.GATING);
        assertThat(MedicPhase.fromStatus("AwaitingApproval")).isEqualTo(MedicPhase.AWAITING_APPROVAL);
        assertThat(MedicPhase.fromStatus("nonsense")).isEqualTo(MedicPhase.PROPOSING);
    }
}
