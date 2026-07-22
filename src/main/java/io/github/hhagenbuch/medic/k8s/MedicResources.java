package io.github.hhagenbuch.medic.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.github.hhagenbuch.medic.k8s.model.MedicProposal;
import io.github.hhagenbuch.medic.k8s.operatorcrd.EvalGate;
import io.github.hhagenbuch.medic.k8s.operatorcrd.PromptVersion;
import io.github.hhagenbuch.medic.k8s.operatorcrd.PromptVersionSpec;

import java.util.Map;

/**
 * Pure builders for what medic puts on the cluster. The PromptVersion a repair
 * rides in is composed HERE, from the Agent's spec plus exactly one Surgeon
 * output (the prompt text): the proposal schema has no other field, and this
 * builder consults no other input — the structural half of the
 * prompts-only-authority rule.
 */
public final class MedicResources {

    /** Annotation on the MedicProposal: a human's approve/reject of the held repair. */
    public static final String MEDIC_APPROVED_ANNOTATION = "medic.hhagenbuch.io/approved";
    /** Annotation on the PromptVersion (operator contract): releases/rejects its hold. */
    public static final String OPERATOR_APPROVED_ANNOTATION = "agents.hhagenbuch.io/approved";
    public static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String AGENT_LABEL = "medic.hhagenbuch.io/agent";
    public static final String RULE_LABEL = "medic.hhagenbuch.io/rule";
    /**
     * The gate must run an agent-evals that emits the VERDICT-JSON log line and
     * enforces {@code required} cases (≥ 0.2.0); medic pins it via the override
     * rather than trusting whatever default the Agent or operator carries.
     */
    public static final String EVALS_IMAGE = "ghcr.io/hhagenbuch/agent-evals:0.2.0";
    private static final String DATASET_KEY = "dataset.yaml";

    private MedicResources() {
    }

    public static String promptVersionName(MedicProposal mp, int attempt) {
        return shortName(mp.getMetadata().getName()) + "-fix-a" + attempt;
    }

    public static String candidateConfigMapName(MedicProposal mp, int attempt) {
        return shortName(mp.getMetadata().getName()) + "-candidate-a" + attempt;
    }

    /**
     * Incident ids make good MedicProposal names but bad PromptVersion bases:
     * the operator derives {@code <pv>-canary} as an {@code app} LABEL VALUE,
     * and label values cap at 63 bytes — one long rule id and the canary apply
     * is rejected. Cap the base at 34 chars (27 + a stable 6-hex-digit hash of
     * the full name, so truncation cannot collide two incidents).
     */
    static String shortName(String name) {
        if (name.length() <= 34) {
            return name;
        }
        return name.substring(0, 27) + "-" + sha256Hex(name).substring(0, 6);
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Mirror of the operator's eval Job naming ({@code CanaryResources.evalJobName}). */
    public static String evalJobName(String promptVersionName) {
        return promptVersionName + "-eval";
    }

    public static ConfigMap candidateConfigMap(MedicProposal mp, int attempt, String namespace,
                                               String mergedDatasetYaml) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(candidateConfigMapName(mp, attempt))
                .withNamespace(namespace)
                .withLabels(labels(mp))
                .endMetadata()
                .withData(Map.of(DATASET_KEY, mergedDatasetYaml))
                .build();
    }

    public static PromptVersion promptVersion(MedicProposal mp, int attempt, String namespace,
                                              String proposedSystemPrompt) {
        PromptVersion pv = new PromptVersion();
        pv.getMetadata().setName(promptVersionName(mp, attempt));
        pv.getMetadata().setNamespace(namespace);
        pv.getMetadata().setLabels(labels(mp));

        PromptVersionSpec spec = new PromptVersionSpec();
        spec.agentRef = mp.getSpec().agentRef;
        spec.systemPrompt = proposedSystemPrompt;
        // No model, no tools, no config: the Surgeon has no channel for them and
        // this builder sets none — the Agent keeps everything else it already has.
        spec.requireApproval = true;
        EvalGate override = new EvalGate();
        override.datasetConfigMap = candidateConfigMapName(mp, attempt);
        override.image = EVALS_IMAGE;
        spec.evalGateOverride = override;
        pv.setSpec(spec);
        return pv;
    }

    public static Map<String, String> labels(MedicProposal mp) {
        return Map.of(
                MANAGED_BY, "agent-medic",
                AGENT_LABEL, mp.getSpec().agentRef,
                RULE_LABEL, sanitizeLabelValue(mp.getSpec().incident.rule));
    }

    /** Rule ids contain dots; label values must be alphanumeric with - _ . allowed. */
    public static String sanitizeLabelValue(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
