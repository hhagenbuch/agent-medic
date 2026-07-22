package io.github.hhagenbuch.medic.k8s;

import io.github.hhagenbuch.medic.k8s.model.MedicProposal;
import io.github.hhagenbuch.medic.k8s.model.MedicProposalSpec;
import io.github.hhagenbuch.medic.k8s.operatorcrd.PromptVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MedicResourcesTest {

    private MedicProposal proposal() {
        MedicProposal mp = new MedicProposal();
        mp.getMetadata().setName("s-42-turn1-honesty");
        MedicProposalSpec spec = new MedicProposalSpec();
        spec.agentRef = "support-agent";
        spec.incident.incidentId = "s-42-turn1-honesty";
        spec.incident.rule = "honesty.claimed-sent-but-queued";
        mp.setSpec(spec);
        return mp;
    }

    @Test
    void promptVersionCarriesExactlyTheSurgeonsOneOutput() {
        PromptVersion pv = MedicResources.promptVersion(proposal(), 1, "agents", "NEW PROMPT");

        assertThat(pv.getMetadata().getName()).isEqualTo("s-42-turn1-honesty-fix-a1");
        assertThat(pv.getSpec().agentRef).isEqualTo("support-agent");
        assertThat(pv.getSpec().systemPrompt).isEqualTo("NEW PROMPT");
        // The authority boundary, structurally: promotion is held for a human,
        // the gate runs suite+incident, and NOTHING else is set.
        assertThat(pv.getSpec().requireApproval).isTrue();
        assertThat(pv.getSpec().evalGateOverride.datasetConfigMap)
                .isEqualTo("s-42-turn1-honesty-candidate-a1");
        // The gate must run a verdict-emitting, required-enforcing evals.
        assertThat(pv.getSpec().evalGateOverride.image).isEqualTo(MedicResources.EVALS_IMAGE);
        assertThat(pv.getSpec().evalGateOverride.minPassRate).isNull();
        assertThat(pv.getSpec().model).isNull();
    }

    @Test
    void namesArePerAttemptAndMirrorTheOperatorsJobNaming() {
        MedicProposal mp = proposal();
        assertThat(MedicResources.promptVersionName(mp, 2)).isEqualTo("s-42-turn1-honesty-fix-a2");
        assertThat(MedicResources.candidateConfigMapName(mp, 2))
                .isEqualTo("s-42-turn1-honesty-candidate-a2");
        assertThat(MedicResources.evalJobName("s-42-turn1-honesty-fix-a2"))
                .isEqualTo("s-42-turn1-honesty-fix-a2-eval");
    }

    @Test
    void longIncidentNamesStayWithinTheOperatorsLabelBudget() {
        // The operator uses "<pv>-canary" as an app LABEL VALUE (63-byte cap);
        // a real incident id broke this at 64 — the fix must keep headroom.
        String longName = "s-support-42-turn1-honesty-claimed-sent-but-queued";
        MedicProposal mp = proposal();
        mp.getMetadata().setName(longName);

        String pvName = MedicResources.promptVersionName(mp, 1);
        assertThat(pvName.length() + "-canary".length()).isLessThanOrEqualTo(63);
        // Deterministic, and distinct from a different name with the same prefix.
        assertThat(pvName).isEqualTo(MedicResources.promptVersionName(mp, 1));
        MedicProposal other = proposal();
        other.getMetadata().setName(longName + "-x");
        assertThat(MedicResources.promptVersionName(other, 1)).isNotEqualTo(pvName);
        // Short names stay human-readable and untruncated.
        assertThat(MedicResources.shortName("s-42-turn1-honesty")).isEqualTo("s-42-turn1-honesty");
    }

    @Test
    void candidateConfigMapHoldsTheMergedDataset() {
        var cm = MedicResources.candidateConfigMap(proposal(), 1, "agents", "cases: []");
        assertThat(cm.getMetadata().getName()).isEqualTo("s-42-turn1-honesty-candidate-a1");
        assertThat(cm.getData()).containsEntry("dataset.yaml", "cases: []");
        assertThat(cm.getMetadata().getLabels())
                .containsEntry("app.kubernetes.io/managed-by", "agent-medic")
                .containsEntry(MedicResources.AGENT_LABEL, "support-agent");
    }

    @Test
    void ruleLabelValuesAreSanitized() {
        assertThat(MedicResources.sanitizeLabelValue("honesty.claimed-sent-but-queued"))
                .isEqualTo("honesty.claimed-sent-but-queued");
        assertThat(MedicResources.sanitizeLabelValue("weird rule/name"))
                .isEqualTo("weird-rule-name");
    }
}
