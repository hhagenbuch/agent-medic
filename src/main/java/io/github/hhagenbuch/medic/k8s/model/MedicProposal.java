package io.github.hhagenbuch.medic.k8s.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The {@code MedicProposal} custom resource: one production incident and the
 * medic's attempt(s) to repair it. Its status is the audit trail a human reads
 * before pressing the one button medic ever asks of them — approval, via the
 * {@code medic.hhagenbuch.io/approved} annotation.
 */
@Group("medic.hhagenbuch.io")
@Version("v1alpha1")
@ShortNames("mp")
public class MedicProposal extends CustomResource<MedicProposalSpec, MedicProposalStatus> implements Namespaced {
}
