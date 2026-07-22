package io.github.hhagenbuch.medic.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.Approval;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.CaseVerdict;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.Decision;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.MedicPhase;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.PvState;
import io.github.hhagenbuch.medic.k8s.MedicStateMachine.RepairState;
import io.github.hhagenbuch.medic.k8s.model.MedicProposal;
import io.github.hhagenbuch.medic.k8s.model.MedicProposalStatus;
import io.github.hhagenbuch.medic.k8s.model.MedicProposalStatus.Attempt;
import io.github.hhagenbuch.medic.k8s.operatorcrd.Agent;
import io.github.hhagenbuch.medic.k8s.operatorcrd.PromptVersion;
import io.github.hhagenbuch.medic.surgeon.Surgeon.Repair;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes {@link MedicStateMachine} decisions against the cluster. All policy
 * lives in the state machine; all authority boundaries live in the parts this
 * class merely calls ({@code ProposalValidator}, {@code MedicResources}, the
 * operator's approval hold). What remains here is bookkeeping and I/O.
 *
 * <p>The gate reuses agent-operator's canary machinery wholesale: medic only
 * ever creates a {@code PromptVersion} (with {@code requireApproval} and the
 * candidate-dataset {@code evalGateOverride}) and annotates it. It never
 * touches Deployments, Jobs, or {@code Agent.spec}.
 */
@ControllerConfiguration
public class MedicProposalReconciler implements Reconciler<MedicProposal> {

    private static final Logger log = LoggerFactory.getLogger(MedicProposalReconciler.class);
    private static final Duration REQUEUE = Duration.ofSeconds(5);
    private static final String DATASET_KEY = "dataset.yaml";
    private static final String MEDIC_MANAGED_LABEL = "medic.hhagenbuch.io/managed";

    private final SurgeonExecutor surgeon;
    private final Path bundleRoot;

    public MedicProposalReconciler(SurgeonExecutor surgeon, Path bundleRoot) {
        this.surgeon = surgeon;
        this.bundleRoot = bundleRoot;
    }

    @Override
    public UpdateControl<MedicProposal> reconcile(MedicProposal mp, Context<MedicProposal> context) {
        KubernetesClient client = context.getClient();
        String ns = mp.getMetadata().getNamespace();
        MedicProposalStatus status = status(mp);

        MedicPhase phase = MedicPhase.fromStatus(status.phase);
        Attempt current = currentAttempt(status);
        int failedAttempts = (int) status.attempts.stream()
                .filter(a -> a.outcome != null && !"Promoted".equals(a.outcome)).count();
        int nextAttemptNo = status.attempts.size() + 1;

        RepairState repairState = phase == MedicPhase.PROPOSING
                ? surgeon.state(attemptKey(mp, nextAttemptNo))
                : RepairState.NONE;
        PromptVersion pv = current != null && current.promptVersionRef != null
                ? client.resources(PromptVersion.class).inNamespace(ns).withName(current.promptVersionRef).get()
                : null;
        CaseVerdict verdict = current == null || current.incidentCasePassed == null
                ? CaseVerdict.UNKNOWN
                : current.incidentCasePassed ? CaseVerdict.PASSED : CaseVerdict.FAILED;

        Decision decision = MedicStateMachine.decide(phase, repairState, pvState(pv), verdict,
                approvalOf(mp), failedAttempts, mp.getSpec().maxAttempts);

        switch (decision.action()) {
            case START_SURGEON -> startSurgeon(client, mp, ns, status, nextAttemptNo);
            case CREATE_GATE -> createGate(client, mp, ns, status, nextAttemptNo);
            case CHECK_INCIDENT_CASE -> checkIncidentCase(client, mp, ns, current);
            case HOLD_FOR_HUMAN -> status.message = "gate passed, incident case included — approve with: "
                    + "kubectl annotate mp " + mp.getMetadata().getName() + " "
                    + MedicResources.MEDIC_APPROVED_ANNOTATION + "=true (or =false to reject)";
            case APPROVE_PV -> {
                annotatePv(client, ns, current.promptVersionRef, "true");
                status.message = "approved — waiting for the operator to promote "
                        + current.promptVersionRef;
            }
            case REJECT_PV -> rejectPv(client, ns, status, current, phase);
            case RECORD_FAILURE -> recordFailure(mp, status, current, repairState, pv, nextAttemptNo);
            case MERGE_ANTIBODY -> mergeAntibody(client, mp, ns, status, current);
            case GIVE_UP -> status.message = "needs a human: " + failedAttempts + " repair attempt(s) "
                    + "did not survive the gate — evidence in status.attempts and bundle "
                    + mp.getSpec().incident.incidentId;
            case WAIT, DONE -> { /* nothing to do this pass */ }
        }

        status.phase = decision.nextPhase().value();
        status.rule = mp.getSpec().incident.rule;
        boolean terminal = decision.nextPhase() == MedicPhase.PROMOTED
                || decision.nextPhase() == MedicPhase.NEEDS_HUMAN;
        return terminal
                ? UpdateControl.patchStatus(mp)
                : UpdateControl.patchStatus(mp).rescheduleAfter(REQUEUE);
    }

    private void startSurgeon(KubernetesClient client, MedicProposal mp, String ns,
                              MedicProposalStatus status, int attemptNo) {
        Agent agent = client.resources(Agent.class).inNamespace(ns).withName(mp.getSpec().agentRef).get();
        if (agent == null) {
            status.message = "waiting: Agent '" + mp.getSpec().agentRef + "' not found";
            return;
        }
        surgeon.start(attemptKey(mp, attemptNo),
                bundleRoot.resolve(mp.getSpec().incident.incidentId),
                agent.getSpec().systemPrompt,
                historyOf(status));
        status.message = "Surgeon attempt " + attemptNo + "/" + mp.getSpec().maxAttempts + " running";
    }

    private void createGate(KubernetesClient client, MedicProposal mp, String ns,
                            MedicProposalStatus status, int attemptNo) {
        Repair repair = surgeon.repair(attemptKey(mp, attemptNo));
        Agent agent = client.resources(Agent.class).inNamespace(ns).withName(mp.getSpec().agentRef).get();
        String suiteCmName = agent == null || agent.getSpec().evalGate == null
                ? null : agent.getSpec().evalGate.datasetConfigMap;
        ConfigMap suiteCm = suiteCmName == null ? null
                : client.configMaps().inNamespace(ns).withName(suiteCmName).get();
        if (suiteCm == null || suiteCm.getData() == null || !suiteCm.getData().containsKey(DATASET_KEY)) {
            status.message = "waiting: suite dataset ConfigMap '" + suiteCmName + "' ("
                    + DATASET_KEY + ") not found";
            return;
        }
        String caseYaml = readCaseYaml(mp);
        if (caseYaml == null) {
            status.message = "cannot gate: incident bundle has no case.yaml ("
                    + mp.getSpec().incident.incidentId + ")";
            return;
        }

        String merged = DatasetMerger.merge(suiteCm.getData().get(DATASET_KEY), caseYaml,
                mp.getSpec().incident.incidentId);
        client.resource(MedicResources.candidateConfigMap(mp, attemptNo, ns, merged)).serverSideApply();
        client.resource(MedicResources.promptVersion(mp, attemptNo, ns, repair.proposal().systemPrompt()))
                .serverSideApply();

        Attempt attempt = new Attempt();
        attempt.promptVersionRef = MedicResources.promptVersionName(mp, attemptNo);
        attempt.rationale = repair.proposal().rationale();
        attempt.promptDiff = repair.promptDiff();
        status.attempts.add(attempt);
        status.message = "gating attempt " + attemptNo + " via PromptVersion "
                + attempt.promptVersionRef + " (suite + incident case)";
        log.info("MedicProposal '{}': gate created ({})", mp.getMetadata().getName(),
                attempt.promptVersionRef);
    }

    private void checkIncidentCase(KubernetesClient client, MedicProposal mp, String ns, Attempt current) {
        String jobLog;
        try {
            jobLog = client.batch().v1().jobs().inNamespace(ns)
                    .withName(MedicResources.evalJobName(current.promptVersionRef)).getLog();
        } catch (RuntimeException e) {
            jobLog = null; // not readable this pass — verdict stays UNKNOWN, we re-check
        }
        CaseVerdict verdict = IncidentCaseCheck.verdict(jobLog, mp.getSpec().incident.incidentId);
        if (verdict != CaseVerdict.UNKNOWN) {
            current.incidentCasePassed = verdict == CaseVerdict.PASSED;
        }
    }

    private void rejectPv(KubernetesClient client, String ns, MedicProposalStatus status,
                          Attempt current, MedicPhase phase) {
        annotatePv(client, ns, current.promptVersionRef, "false");
        if (phase == MedicPhase.AWAITING_APPROVAL) {
            current.outcome = "RolledBack";
            current.detail = "rejected by human (" + MedicResources.MEDIC_APPROVED_ANNOTATION + "=false)";
            status.message = "needs a human: the reviewer rejected the held repair";
        } else {
            current.detail = "incident case failed at the gate despite the aggregate pass rate";
            status.message = "rejecting attempt: the fix passes the suite but not the incident case";
        }
    }

    private void recordFailure(MedicProposal mp, MedicProposalStatus status, Attempt current,
                               RepairState repairState, PromptVersion pv, int nextAttemptNo) {
        if (repairState == RepairState.FAILED) {
            Attempt invalid = new Attempt();
            invalid.outcome = "Invalid";
            invalid.detail = surgeon.failure(attemptKey(mp, nextAttemptNo));
            status.attempts.add(invalid);
            status.message = "Surgeon attempt " + nextAttemptNo + " produced an invalid proposal: "
                    + invalid.detail;
        } else if (current != null) {
            current.outcome = "RolledBack";
            if (current.detail == null) {
                current.detail = pv != null && pv.getStatus() != null && pv.getStatus().message != null
                        ? pv.getStatus().message : "gate rolled back";
            }
            status.message = "attempt " + status.attempts.size() + " rolled back — " + current.detail;
        }
    }

    private void mergeAntibody(KubernetesClient client, MedicProposal mp, String ns,
                               MedicProposalStatus status, Attempt current) {
        Agent agent = client.resources(Agent.class).inNamespace(ns).withName(mp.getSpec().agentRef).get();
        String suiteCmName = agent.getSpec().evalGate.datasetConfigMap;
        ConfigMap suiteCm = client.configMaps().inNamespace(ns).withName(suiteCmName).get();
        String merged = DatasetMerger.merge(suiteCm.getData().get(DATASET_KEY), readCaseYaml(mp),
                mp.getSpec().incident.incidentId);
        suiteCm.getData().put(DATASET_KEY, merged);
        if (suiteCm.getMetadata().getLabels() == null) {
            suiteCm.getMetadata().setLabels(new java.util.HashMap<>());
        }
        suiteCm.getMetadata().getLabels().put(MEDIC_MANAGED_LABEL, "true");
        client.resource(suiteCm).update();

        current.outcome = "Promoted";
        status.message = "healed: " + current.promptVersionRef + " promoted; incident case '"
                + mp.getSpec().incident.incidentId + "' merged into suite '" + suiteCmName
                + "' (the antibody rule — the suite is one case stronger)";
        log.info("MedicProposal '{}': PROMOTED, antibody merged into {}",
                mp.getMetadata().getName(), suiteCmName);
    }

    private void annotatePv(KubernetesClient client, String ns, String pvName, String approved) {
        // Metadata-only JSON merge patch: medic must never rewrite a PromptVersion's spec.
        client.resources(PromptVersion.class).inNamespace(ns).withName(pvName)
                .patch(PatchContext.of(PatchType.JSON_MERGE),
                        "{\"metadata\":{\"annotations\":{\""
                                + MedicResources.OPERATOR_APPROVED_ANNOTATION + "\":\"" + approved + "\"}}}");
    }

    private List<SurgeonExecutor.HistoryEntry> historyOf(MedicProposalStatus status) {
        List<SurgeonExecutor.HistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < status.attempts.size(); i++) {
            Attempt a = status.attempts.get(i);
            history.add(new SurgeonExecutor.HistoryEntry("attempt-" + (i + 1) + ".md",
                    "outcome: " + a.outcome
                            + "\nincidentCasePassed: " + a.incidentCasePassed
                            + "\ndetail: " + (a.detail == null ? "" : a.detail)
                            + "\nrationale of that attempt: " + (a.rationale == null ? "" : a.rationale)));
        }
        return history;
    }

    private String readCaseYaml(MedicProposal mp) {
        Path caseFile = bundleRoot.resolve(mp.getSpec().incident.incidentId).resolve("case.yaml");
        try {
            return Files.isRegularFile(caseFile) ? Files.readString(caseFile) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static Attempt currentAttempt(MedicProposalStatus status) {
        if (status.attempts.isEmpty()) {
            return null;
        }
        Attempt last = status.attempts.get(status.attempts.size() - 1);
        return last.outcome == null ? last : null;
    }

    private static PvState pvState(PromptVersion pv) {
        if (pv == null) {
            return PvState.NONE;
        }
        String phase = pv.getStatus() == null ? null : pv.getStatus().phase;
        if (phase == null) {
            return PvState.IN_FLIGHT;
        }
        return switch (phase) {
            case "AwaitingApproval" -> PvState.AWAITING_APPROVAL;
            case "Promoted" -> PvState.PROMOTED;
            case "RolledBack" -> PvState.ROLLED_BACK;
            default -> PvState.IN_FLIGHT;
        };
    }

    private static Approval approvalOf(MedicProposal mp) {
        var annotations = mp.getMetadata().getAnnotations();
        String value = annotations == null ? null : annotations.get(MedicResources.MEDIC_APPROVED_ANNOTATION);
        if ("true".equalsIgnoreCase(value)) {
            return Approval.APPROVED;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Approval.REJECTED;
        }
        return Approval.NONE;
    }

    private static String attemptKey(MedicProposal mp, int attemptNo) {
        return mp.getMetadata().getNamespace() + "-" + mp.getMetadata().getName() + "-a" + attemptNo;
    }

    private static MedicProposalStatus status(MedicProposal mp) {
        if (mp.getStatus() == null) {
            mp.setStatus(new MedicProposalStatus());
        }
        return mp.getStatus();
    }
}
