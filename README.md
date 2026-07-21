# agent-medic

> When your agent misbehaves in production, the incident becomes a failing
> regression test, a repair agent proposes the fix, and the fix ships only if
> it passes the gate — the new case AND the whole suite. Production failure to
> gated fix, with a human holding exactly one button: **approve**.

**The system's response to failure is to grow an antibody.** Every incident
permanently hardens the eval suite — medic never deletes or weakens a case.

**Status: Phase 1 — Watcher + Diagnoser.** Failing production traces become
incident bundles with a ready-to-run regression case. See
[`docs/DESIGN.md`](docs/DESIGN.md); roadmap below.

## Try it (30 seconds, no API key)

```sh
mvn spring-boot:run &
mkdir -p traces && cp examples/honesty-incident.trace.jsonl traces/
```

Within two seconds the Watcher flags the recorded honesty failure (the agent
said "I've sent the report" while `send_email` had failed) and writes
`incidents/s-support-42-turn1-honesty-claimed-sent-but-queued/` containing:

- `incident.json` — what fired, where, and the evidence
- `case.yaml` — an [agent-evals](https://github.com/hhagenbuch/agent-evals)
  regression case exported from the failing turn
- `trace.jsonl` — the full recorded session, verbatim

Rules live in [`config/rules.yaml`](config/rules.yaml) — rules-as-data, four
types: `error-event`, `claim-without-tool`, `expected-tool`,
`unexpected-tool`.

## The loop

```
   [Watcher]              [Diagnoser]                 [Surgeon]
blackbox traces  ──►  failing trajectory  ──►  repair agent (starter + MCP)
 (live tail)          export-eval case            proposes a prompt fix
                                                        │
   [Gate]                                               ▼
agent-evals suite  ◄──  agent-operator canary  ◄──  MedicProposal CR
(incident case + full suite must pass)                  │
        │ pass                                          │ fail
        ▼                                               ▼
  await HUMAN APPROVAL → Promoted            RolledBack + report attached
```

1. **Watch** — tail [agent-blackbox](https://github.com/hhagenbuch/agent-blackbox)
   traces; flag failing trajectories by rule (errors, honesty violations,
   tool-discipline breaks).
2. **Diagnose** — deterministically export the failing turn as an
   [agent-evals](https://github.com/hhagenbuch/agent-evals) case via
   `blackbox export-eval`. **No LLM in this stage** — evidence is never
   hallucinated.
3. **Repair** — the Surgeon, an agent built on
   [spring-ai-agent-starter](https://github.com/hhagenbuch/spring-ai-agent-starter),
   reads the incident through a read-only MCP server and proposes a prompt
   diff with a written rationale. It can change **prompt text only** — the
   controller rejects anything else structurally.
4. **Gate** — an [agent-operator](https://github.com/hhagenbuch/agent-operator)
   canary runs the full suite *plus* the new incident case. Fail → rolled
   back; two failed repairs → `NeedsHuman`.
5. **Approve** — a human reviews trace, diff, rationale, and eval report, then
   annotates approval. On promotion the incident case merges into the
   permanent suite: the antibody.

## What medic will never do

- **No auto-approve mode. Ever.** The single human approval is the safety
  story, not a missing feature.
- **No tool or code repair** — the Surgeon's authority is a hard allowlist:
  prompt text only, enforced by the controller, not by convention.
- **No unbounded retries** — two failed repairs means the machine is out of
  its depth, and it says so (`NeedsHuman`).

The medic is subject to the same observability as its patients: its own runs
are traced by blackbox and metered by
[agent-meter](https://github.com/hhagenbuch/agent-meter).

## Roadmap

- [x] Phase 0 — design ([`docs/DESIGN.md`](docs/DESIGN.md))
- [x] Phase 1 — Watcher + Diagnoser: trace tailing, failure rules, incident bundle
- [ ] Phase 2 — Surgeon: medic MCP server + the repair agent
- [ ] Phase 3 — MedicProposal controller: CRD, gate wiring, approval flow
- [ ] Phase 4 — the demo: sabotage → detect → propose → gate → approve → healed
- [ ] Phase 5 — `docs/STANDARDS.md`: the agent-engineering standards this
      portfolio builds by

## License

MIT
