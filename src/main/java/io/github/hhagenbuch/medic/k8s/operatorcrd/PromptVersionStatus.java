package io.github.hhagenbuch.medic.k8s.operatorcrd;

/** Mirror of agent-operator's PromptVersionStatus (read-only from medic's side). */
public class PromptVersionStatus {
    public String phase;
    public String evalPassRate;
    public String message;
    public String evalStartedAt;
}
