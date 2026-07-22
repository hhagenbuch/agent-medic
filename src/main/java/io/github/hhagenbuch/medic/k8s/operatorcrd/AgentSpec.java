package io.github.hhagenbuch.medic.k8s.operatorcrd;

/** Mirror of agent-operator's AgentSpec — full field set, so nothing is dropped on (de)serialization. */
public class AgentSpec {
    public String image;
    public int replicas = 1;
    public String model;
    public SecretKeyRef apiKeySecretRef;
    public String activePromptVersion;
    public String systemPrompt;
    public EvalGate evalGate;
}
