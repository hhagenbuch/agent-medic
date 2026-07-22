# Agent engineering standards

Engineering standards for systems where an LLM holds any authority — calling
tools, answering users, or (as in this repo) proposing changes to other
agents. Written for GitHub-hosted teams: every rule names the mechanism that
enforces it, because a standard that lives only in a document is a suggestion.

This document lives in agent-medic deliberately: **medic programmatically
edits prompts, so these rules bind it hardest.** Each rule links the repo in
this platform that implements it — the standards are extracted from working
code, not aspiration.

---

## 1. Orchestration and prompts are separate artifacts

The agent loop (retry, tool dispatch, memory, error recovery) is code. The
prompt (what the agent is told to be) is configuration with behavioral blast
radius. Keep them in separate files with separate review paths — a prompt edit
must never ride along unnoticed inside a refactor, and a code change must not
silently reword the prompt.

- **Enforce:** prompts live under a dedicated path (`src/main/resources/prompts/`,
  `config/`), never inline in Java/TS string literals. A CI grep for
  `systemPrompt = "` -style inlining keeps them out of code.
- **Here:** the loop is [spring-ai-agent-starter](https://github.com/hhagenbuch/spring-ai-agent-starter)'s
  `AgentLoop`; medic's own repair prompt is `src/main/resources/prompts/surgeon.md`.
  In production the prompt is a versioned resource of its own
  ([agent-operator](https://github.com/hhagenbuch/agent-operator)'s `PromptVersion`).

## 2. Prompts are code: versioned, reviewed, gated, rolled back

A prompt edit changes agent behavior as much as a code change changes service
behavior. It gets the same lifecycle: a commit, a review, a gate, a rollback
story. "Someone edited the prompt in a dashboard" is an outage report waiting
to be written.

- **Enforce:** prompts change only via pull request. Deployment goes through
  the same pipeline as code — an eval gate (rule 6) and an owned rollout with
  automatic rollback.
- **Here:** a `PromptVersion` custom resource is the unit of change; promotion
  is eval-gated canary → promote-or-rollback, and `kubectl get promptversions`
  shows why anything rolled back.

## 3. Model output is untrusted input

Anything a model produces — text, JSON, tool arguments, "just a summary" — is
untrusted input to whatever consumes it. Parse defensively, validate against a
closed schema, cap sizes, and reject unknown fields by name. The dual is
authority-by-construction: if the model must not change X, its output format
has no field for X — validation you can't forget because the channel doesn't
exist.

- **Enforce:** every model-output consumer goes through a validator with tests
  for the malicious cases (extra fields, oversized payloads, format smuggling),
  not just the happy path.
- **Here:** medic's `ProposalValidator` accepts exactly
  `{systemPrompt, rationale}` — a proposal smuggling a `model` or `tools`
  field is rejected by name, and the test for that is load-bearing.

## 4. Tools are typed contracts with documented side effects

A tool is an API the model consumes: it needs a name, a description written
for the model, a JSON-Schema-typed input, and an explicit statement of side
effects. Destructive tools are idempotent or confirmation-gated. Tool schemas
are contracts — verify them like you verify any API contract, and treat an
unannounced schema change by a tool provider as a breaking change.

- **Enforce:** a tool interface that forces schema + description
  (`AgentTool`), and contract tests against the tool servers you mount.
- **Here:** the starter's `AgentTool`/MCP adapter carries typed schemas;
  [mcp-pact](https://github.com/hhagenbuch/mcp-pact) pins MCP server schemas
  and fails CI when a server drifts; medic's MCP server exposes exactly three
  read-only tools — the tool surface IS the authority boundary (rule 3's dual,
  applied to capability instead of format).

## 5. Prompt and tool changes take two reviewers

Code review catches broken logic; prompt and tool-surface review catches
broken *behavior*, and behavior is where agents fail. Any change to a prompt,
a tool description, a tool schema, or an agent's tool set takes two humans:
the author plus one reviewer who did not write it. No self-merge, no bot
merge, no "it's just wording."

- **Enforce:** `CODEOWNERS` on the prompt and tool paths plus branch
  protection requiring review from owners — the platform then refuses the
  merge, which beats any convention:

  ```
  # CODEOWNERS
  /prompts/          @org/agent-owners
  /tools/            @org/agent-owners
  /datasets/         @org/agent-owners
  ```
- **Here:** medic is the machine case of the same rule — it may *propose* a
  prompt (one author), but promotion requires a human approval annotation the
  controller cannot grant itself, and there is deliberately no auto-approve
  mode. The reviewer requirement is enforced by the deploy path, not by
  etiquette.

## 6. The eval gate is a blocking pipeline stage

Golden datasets — deterministic assertions first, LLM-judged criteria second —
run in CI and block the merge, and run again against the real canary and block
the promotion. A failing eval is a failing build. Cases exported from
production incidents are marked `required`: the gate fails on them regardless
of the aggregate pass rate, because an average must not absorb the one case
that pins a real failure.

- **Enforce:** an eval step in the pipeline whose exit code gates the merge,
  e.g.

  ```yaml
  - name: Eval gate
    run: |
      java -jar agent-evals.jar datasets/golden.yaml \
        --target http://localhost:8080/api/chat --min-pass-rate 0.9
      # exit 1 blocks the merge; required cases fail the gate regardless
  ```
- **Here:** [agent-evals](https://github.com/hhagenbuch/agent-evals) is the
  gate (with `required` cases and a machine-readable `verdict.json`); the
  operator runs it in-cluster against the canary; medic's antibody rule makes
  every production incident a permanent gate case.

## 7. Secrets never touch a repo

API keys, tokens, and connection strings live in a secret manager (GitHub
Actions secrets, Kubernetes Secrets), reach processes as environment
variables or mounted secrets, and appear in no committed file, no prompt, no
trace, no eval dataset. Recorded agent traffic is data exhaust — redact at
write time, because a trace that captured a secret has already leaked it to
every system that stores the trace.

- **Enforce:** secret-scanning on the repo, secrets injected only via the
  platform's secret store, and redaction in the recording path itself.
- **Here:** keys arrive via `secretKeyRef`/Actions secrets everywhere in this
  platform; [agent-blackbox](https://github.com/hhagenbuch/agent-blackbox)
  redacts before events reach disk; even this repo's clean-room CI check keeps
  its own match pattern in a secret rather than publishing it.

---

### The shape of all seven

One idea repeats: **make the safe thing structural.** A prompt that can't hide
inside a refactor, a model output that has no field for what it must not do, a
tool surface that is the permission set, a merge the platform refuses without
a second human, a gate whose exit code is the decision, a secret that has no
path into the tree. Documents drift; structure holds.
