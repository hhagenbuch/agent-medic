package io.github.hhagenbuch.medic.k8s.operatorcrd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Client-side mirror of agent-operator's {@code PromptVersion} CRD. Medic
 * CREATES these (the gate) and annotates them (approval/rejection via JSON
 * merge patch on metadata only — never a full-object update).
 */
@Group("agents.hhagenbuch.io")
@Version("v1alpha1")
public class PromptVersion extends CustomResource<PromptVersionSpec, PromptVersionStatus> implements Namespaced {
}
