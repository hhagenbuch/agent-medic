package io.github.hhagenbuch.medic.k8s.operatorcrd;

/** Mirror of agent-operator's Rollout. */
public class Rollout {
    public String strategy = "EvalGatedCanary";
    public int canaryWeight = 0;
}
