#!/usr/bin/env bash
# The whole loop, end to end, on kind: sabotage → detect → export → propose →
# gate (suite + incident case) → AwaitingApproval → human annotates → Promoted →
# antibody merged into the suite.
#
# Requires: kind, kubectl, docker, mvn, java 25, ANTHROPIC_API_KEY in the env
# (the Surgeon and the judge tier are real here — this is the demo, not a mock),
# and sibling checkouts: ../agent-operator and ../spring-ai-agent-starter.
#
# Medic itself runs LOCALLY against the kind kubeconfig (like the operator's own
# demo.sh runs the operator) so the Watcher can tail a local trace directory.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANTHROPIC_API_KEY:?set ANTHROPIC_API_KEY — the Surgeon and judge run for real in this e2e}"
# Secrets pasted into stores routinely pick up a trailing newline, which is a
# prohibited character in an HTTP header — strip it here so every consumer
# (medic env, k8s secret) gets a clean value.
ANTHROPIC_API_KEY=$(printf '%s' "$ANTHROPIC_API_KEY" | tr -d '\r\n')

CLUSTER=${CLUSTER:-agent-medic-e2e}
NS=agents
OPERATOR_DIR=${OPERATOR_DIR:-../agent-operator}
STARTER_DIR=${STARTER_DIR:-../spring-ai-agent-starter}
STARTER_IMAGE=ghcr.io/hhagenbuch/spring-ai-agent-starter:0.3.0
WORK=$(mktemp -d)
TRACES="$WORK/traces"; BUNDLES="$WORK/incidents"
mkdir -p "$TRACES" "$BUNDLES"

echo "==> kind cluster"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"
kubectl config use-context "kind-$CLUSTER"

echo "==> build medic + operator; load the starter image"
mvn -q -DskipTests package
( cd "$OPERATOR_DIR" && mvn -q -DskipTests package )
( cd "$STARTER_DIR" && mvn -q -DskipTests spring-boot:build-image \
    -Dspring-boot.build-image.imageName="$STARTER_IMAGE" )
kind load docker-image "$STARTER_IMAGE" --name "$CLUSTER"

echo "==> install CRDs (operator's, incl. requireApproval/evalGateOverride, + medic's)"
kubectl apply -f "$OPERATOR_DIR/deploy/crds/"
kubectl apply -f deploy/crds/medicproposal-crd.yaml

echo "==> namespace, secret, Agent, suite dataset"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic anthropic-key \
  --from-literal=api-key="$ANTHROPIC_API_KEY" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" apply -f "$OPERATOR_DIR/examples/agent.yaml"
kubectl -n "$NS" create configmap support-golden-cases \
  --from-file=dataset.yaml="$OPERATOR_DIR/examples/golden-cases.yaml" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> run the operator (background, local)"
java -jar "$OPERATOR_DIR"/target/agent-operator-*.jar &
OPERATOR_PID=$!
echo "==> run medic (background, local, controller mode)"
MEDIC_KUBERNETES_ENABLED=true \
MEDIC_KUBERNETES_NAMESPACE="$NS" \
MEDIC_SURGEON_API_KEY="$ANTHROPIC_API_KEY" \
MEDIC_WATCH_DIR="$TRACES" MEDIC_BUNDLE_DIR="$BUNDLES" MEDIC_WORK_DIR="$WORK/rooms" \
MEDIC_RULES_FILE=config/rules.yaml \
java -cp target/classes:"target/lib/*" io.github.hhagenbuch.medic.MedicApplication &
MEDIC_PID=$!
trap 'kill $OPERATOR_PID $MEDIC_PID 2>/dev/null || true' EXIT
sleep 5

echo "==> seed the incident: the recorded honesty failure lands in the watched dir"
cp examples/honesty-incident.trace.jsonl "$TRACES/s-support-42.trace.jsonl"

MP=s-support-42-turn1-honesty-claimed-sent-but-queued
echo "==> waiting for the MedicProposal to reach AwaitingApproval (Surgeon + canary gate run now)"
for i in $(seq 1 120); do
  PHASE=$(kubectl -n "$NS" get mp "$MP" -o jsonpath='{.status.phase}' 2>/dev/null || true)
  echo "    [$i] phase=${PHASE:-<none>}"
  [ "$PHASE" = "AwaitingApproval" ] && break
  if [ "$PHASE" = "NeedsHuman" ]; then
    echo "!! medic gave up — evidence:"; kubectl -n "$NS" get mp "$MP" -o yaml; exit 1
  fi
  sleep 5
done
[ "$PHASE" = "AwaitingApproval" ] || { echo "!! timed out"; kubectl -n "$NS" get mp "$MP" -o yaml; exit 1; }

echo "==> the one human button:"
kubectl -n "$NS" get mp "$MP" -o jsonpath='{.status.message}{"\n"}'
kubectl -n "$NS" annotate mp "$MP" medic.hhagenbuch.io/approved=true

echo "==> waiting for Promoted"
for i in $(seq 1 60); do
  PHASE=$(kubectl -n "$NS" get mp "$MP" -o jsonpath='{.status.phase}' 2>/dev/null || true)
  echo "    [$i] phase=${PHASE:-<none>}"
  [ "$PHASE" = "Promoted" ] && break
  sleep 5
done
[ "$PHASE" = "Promoted" ] || { echo "!! not promoted"; kubectl -n "$NS" get mp "$MP" -o yaml; exit 1; }

echo "==> the antibody rule: the suite must now contain the incident case"
kubectl -n "$NS" get configmap support-golden-cases -o jsonpath='{.data.dataset\.yaml}' \
  | grep -q "$MP" && echo "ANTIBODY PRESENT ✓"

echo
echo "HEALED. The suite is one case stronger:"
kubectl -n "$NS" get mp "$MP"
kubectl -n "$NS" get promptversions
