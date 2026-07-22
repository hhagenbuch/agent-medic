package io.github.hhagenbuch.medic.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.hhagenbuch.agent.config.AgentProperties;
import io.github.hhagenbuch.agent.llm.AnthropicClient;
import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.medic.k8s.MedicProposalReconciler;
import io.github.hhagenbuch.medic.k8s.ProposalCreator;
import io.github.hhagenbuch.medic.k8s.SurgeonExecutor;
import io.github.hhagenbuch.medic.surgeon.Surgeon;
import io.javaoperatorsdk.operator.Operator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Controller mode: everything that needs a cluster, behind
 * {@code medic.kubernetes.enabled=true}. Without it, medic is the file-only
 * Watcher/Diagnoser from phase 1 and this whole configuration stays inert.
 */
@Configuration
@ConditionalOnProperty("medic.kubernetes.enabled")
public class KubernetesConfig {

    private static final Logger log = LoggerFactory.getLogger(KubernetesConfig.class);

    @Bean(destroyMethod = "close")
    KubernetesClient kubernetesClient() {
        // Ambient context: in-cluster service account, or local kubeconfig against kind.
        return new KubernetesClientBuilder().build();
    }

    @Bean
    LlmClient surgeonLlm(MedicProperties props) {
        if (props.surgeon().apiKey().isBlank()) {
            log.warn("medic.surgeon.api-key is not set — Surgeon attempts will fail and proposals "
                    + "will end NeedsHuman (degraded but honest)");
            return (messages, tools) -> Mono.error(new IllegalStateException(
                    "no Surgeon LLM configured (medic.surgeon.api-key)"));
        }
        AgentProperties agentProps = new AgentProperties(
                props.surgeon().apiKey(), props.surgeon().model(), 4096, 6, 3, List.of());
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", agentProps.apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
        return new AnthropicClient(webClient, agentProps, new ObjectMapper());
    }

    @Bean
    SurgeonExecutor surgeonExecutor(LlmClient surgeonLlm, MedicProperties props) {
        return new SurgeonExecutor(new Surgeon(surgeonLlm), props.workDir());
    }

    @Bean
    MedicProposalReconciler medicProposalReconciler(SurgeonExecutor executor, MedicProperties props) {
        return new MedicProposalReconciler(executor, props.bundleDir());
    }

    @Bean(destroyMethod = "stop")
    Operator medicOperator(MedicProposalReconciler reconciler) {
        Operator operator = new Operator();
        operator.register(reconciler);
        operator.start();
        log.info("MedicProposal controller started");
        return operator;
    }

    @Bean
    ProposalCreator proposalCreator(KubernetesClient client, MedicProperties props) {
        return new ProposalCreator(client, props.kubernetes().namespace());
    }

    @Bean
    io.github.hhagenbuch.medic.diagnose.CaseProbe caseProbe(MedicProperties props) {
        // In-cluster the Diagnoser can re-run the exported case against the live
        // agent — the flakiness check that guards the required flag.
        return new io.github.hhagenbuch.medic.diagnose.HttpCaseProbe(
                props.kubernetes().probeUrlTemplate());
    }
}
