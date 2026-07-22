package io.github.hhagenbuch.medic.k8s.operatorcrd;

/** Mirror of agent-operator's PromptVersionSpec — full field set. */
public class PromptVersionSpec {
    public String agentRef;
    public String systemPrompt;
    public String model;
    public Rollout rollout = new Rollout();
    public Boolean requireApproval;
    public EvalGate evalGateOverride;
}
