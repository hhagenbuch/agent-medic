package io.github.hhagenbuch.medic.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.hhagenbuch.blackbox.core.TraceEvent;
import io.github.hhagenbuch.medic.k8s.model.MedicProposal;
import io.github.hhagenbuch.medic.k8s.model.MedicProposalSpec;
import io.github.hhagenbuch.medic.rules.Finding;
import io.github.hhagenbuch.medic.watch.TraceWatcher.IncidentListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Turns a newly diagnosed incident into a {@code MedicProposal} CR.
 *
 * <p>Storm dedupe (DESIGN.md §5): at most ONE open proposal per
 * {@code (agent, rule)} pair. A bad deploy fires the same rule on hundreds of
 * sessions; the first incident opens the proposal and the rest are recorded
 * only as bundles — evidence, not work items.
 */
public class ProposalCreator implements IncidentListener {

    private static final Logger log = LoggerFactory.getLogger(ProposalCreator.class);

    private final KubernetesClient client;
    private final String namespace;

    public ProposalCreator(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    @Override
    public void onIncident(Path bundle, Finding finding, List<TraceEvent> events) {
        String agent = runtimeApp(events);
        if (agent.isEmpty()) {
            log.warn("incident {} has no runtime.app in its trace — cannot target an Agent, skipping CR",
                    bundle.getFileName());
            return;
        }
        if (hasOpenProposal(agent, finding.ruleId())) {
            log.info("incident {}: open proposal already exists for ({}, {}) — bundle kept as evidence",
                    bundle.getFileName(), agent, finding.ruleId());
            return;
        }

        MedicProposal mp = new MedicProposal();
        mp.getMetadata().setName(bundle.getFileName().toString());
        mp.getMetadata().setNamespace(namespace);
        MedicProposalSpec spec = new MedicProposalSpec();
        spec.agentRef = agent;
        spec.incident.incidentId = bundle.getFileName().toString();
        spec.incident.rule = finding.ruleId();
        spec.incident.turn = finding.turn();
        spec.incident.traceRef = bundle.resolve("trace.jsonl").toString();
        mp.setSpec(spec);
        mp.getMetadata().setLabels(MedicResources.labels(mp));

        client.resource(mp).serverSideApply();
        log.warn("incident {} → MedicProposal created (agent={}, rule={})",
                bundle.getFileName(), agent, finding.ruleId());
    }

    private boolean hasOpenProposal(String agent, String rule) {
        return client.resources(MedicProposal.class).inNamespace(namespace)
                .withLabel(MedicResources.AGENT_LABEL, agent)
                .withLabel(MedicResources.RULE_LABEL, MedicResources.sanitizeLabelValue(rule))
                .list().getItems().stream()
                .anyMatch(mp -> {
                    String phase = mp.getStatus() == null ? null : mp.getStatus().phase;
                    return !"Promoted".equals(phase) && !"NeedsHuman".equals(phase);
                });
    }

    private static String runtimeApp(List<TraceEvent> events) {
        return events.stream()
                .filter(e -> e.type().equals("session_start"))
                .map(e -> e.node().path("runtime").path("app").asText(""))
                .findFirst().orElse("");
    }
}
