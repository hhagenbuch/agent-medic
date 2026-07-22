package io.github.hhagenbuch.medic.k8s.operatorcrd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Client-side mirror of agent-operator's {@code Agent} CRD (the CRD is the
 * contract; agent-operator owns it and its reconcilers). Medic only READS
 * Agents — never writes them.
 */
@Group("agents.hhagenbuch.io")
@Version("v1alpha1")
public class Agent extends CustomResource<AgentSpec, AgentStatus> implements Namespaced {
}
