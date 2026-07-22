# agent-medic — the full closed loop, recorded. Run from the repo root:
#
#   hack/demo-kind.sh up        # kind cluster, operator, patient, medic
#   vhs hack/demo.tape          # produces docs/demo.gif
#   hack/demo-kind.sh down
#
# WHAT THIS RECORDING DOES AND DOES NOT SHOW
#
# This is the KEYLESS path, with two labeled stand-ins:
#   - the patient is a deterministic stub agent (hack/demo-agent/) whose honesty
#     depends on its prompt — no model behind it;
#   - the Surgeon's response is REPLAYED from hack/demo/surgeon-replay.json —
#     the reasoning is canned, though its MCP evidence round-trips are real.
# Everything else is real and is the point: the Watcher flags the recorded
# honesty failure, the Diagnoser exports the regression case, the controller
# gates the repair through a real agent-operator canary + agent-evals Job
# (two bars: aggregate AND the required incident case), promotion holds for a
# human annotation, and the antibody merges into the permanent suite.
# The CI live variant (.github/workflows/live-demo.yml) runs the same loop
# with a real model and a real spring-ai-agent-starter patient.

Output docs/demo.gif

Set Shell zsh
Set FontSize 15
Set Width 1180
Set Height 660
Set Framerate 10
Set TypingSpeed 12ms
Set Theme "Catppuccin Mocha"
Set Padding 14
Set Margin 8
Set WindowBar Colorful

# ---------------------------------------------------------------- setup (hidden)
Hide
Type `MP=s-support-42-turn1-honesty-claimed-sent-but-queued` Enter
Type `PV=s-support-42-turn1-honesty--2192f9-fix-a1` Enter
Type `clear && printf '\n  \033[1;36magent-medic\033[0m — production failure to gated, human-approved repair\n\n  \033[2mkeyless demo: stub patient, Surgeon replayed — the loop, gate, and approval are real\033[0m\n\n'` Enter
Sleep 2s
Show
Sleep 2500ms

# 1. Baseline: the patient is healthy and serving its original prompt.
Type `kubectl -n agents get agents,deploy` Enter
Sleep 4s

# 2. A recorded production failure lands: the agent said "I've sent it" while
#    send_email had failed. This trace is the incident.
Type `grep -o '"result":"[^"]*"' examples/honesty-incident.trace.jsonl; grep -o '"type":"assistant_message".*' examples/honesty-incident.trace.jsonl` Enter
Sleep 5s
Type `cp examples/honesty-incident.trace.jsonl /tmp/medic-demo/traces/s-support-42.trace.jsonl` Enter
Sleep 2s

# 3. Watcher → Diagnoser → Surgeon → canary gate, unattended. Watch the phases.
Type `for i in $(seq 1 16); do kubectl -n agents get mp $MP --no-headers \` Enter
Type `  -o custom-columns=PHASE:.status.phase,RULE:.status.rule 2>/dev/null; sleep 4; done` Enter
Sleep 66s

# 4. The gate's verdict — both bars: the suite AND the required incident case.
Type `kubectl -n agents logs job/$PV-eval | grep -E '^\[PASS|^\[FAIL'` Enter
Sleep 5s
Type `kubectl -n agents get mp $MP -o jsonpath='{.status.message}'; echo` Enter
Sleep 6s

# 5. The one human button.
Type `kubectl -n agents annotate mp $MP medic.hhagenbuch.io/approved=true` Enter
Sleep 3s
Type `for i in $(seq 1 8); do kubectl -n agents get mp $MP --no-headers \` Enter
Type `  -o custom-columns=PHASE:.status.phase; sleep 4; done` Enter
Sleep 34s

# 6. Healed — and the antibody rule: the suite is permanently one case stronger.
Type `kubectl -n agents get agents` Enter
Sleep 4s
Type `kubectl -n agents get cm support-golden-cases -o jsonpath='{.data.dataset\.yaml}' | tail -4` Enter
Sleep 5s
Type `kubectl -n agents get mp $MP -o jsonpath='{.status.message}'; echo` Enter
Sleep 7s
