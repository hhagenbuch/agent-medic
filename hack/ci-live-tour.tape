# CI companion to the live e2e: after hack/e2e-kind.sh has run the FULL ARC
# with a real model (real Surgeon, real spring-ai-agent-starter patient, real
# judge tier), this tape tours the evidence the arc left on the cluster and
# renders the GIF the workflow uploads as an artifact.

Output live-demo.gif

Set Shell bash
Set FontSize 15
Set Width 1180
Set Height 660
Set Framerate 10
Set TypingSpeed 12ms
Set Theme "Catppuccin Mocha"
Set Padding 14
Set Margin 8
Set WindowBar Colorful

Hide
Type `MP=$(kubectl -n agents get mp -o jsonpath='{.items[0].metadata.name}')` Enter
Type `clear && printf '\n  \033[1;36magent-medic\033[0m — the full arc, LIVE (real model, real patient, real judge)\n\n  \033[2mrecorded after .github/workflows/live-demo.yml ran the loop end to end\033[0m\n\n'` Enter
Sleep 2s
Show
Sleep 2500ms

# The healed proposal and the promoted PromptVersion.
Type `kubectl -n agents get mp,promptversions` Enter
Sleep 5s

# The audit trail: what the Surgeon argued, and how the gate judged it.
Type `kubectl -n agents get mp $MP -o jsonpath='{.status.attempts[0].rationale}' | fold -s -w 110; echo` Enter
Sleep 8s
Type `kubectl -n agents get mp $MP -o jsonpath='{.status.attempts[0].incidentCaseVerdict}'; echo` Enter
Sleep 4s

# The prompt diff a human approved.
Type `kubectl -n agents get mp $MP -o jsonpath='{.status.attempts[0].promptDiff}' | head -20` Enter
Sleep 8s

# The antibody, permanent and required.
Type `kubectl -n agents get cm support-golden-cases -o jsonpath='{.data.dataset\.yaml}' | tail -6` Enter
Sleep 6s
Type `kubectl -n agents get mp $MP -o jsonpath='{.status.message}'; echo` Enter
Sleep 7s
