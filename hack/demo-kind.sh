#!/usr/bin/env bash
# KEYLESS demo environment: the full medic loop on kind with two labeled
# stand-ins — the patient is a deterministic stub agent (hack/demo-agent/) and
# the Surgeon's response is replayed from hack/demo/surgeon-replay.json. Every
# other part runs for real: Watcher, Diagnoser, MCP round-trips, MedicProposal
# controller, agent-operator canary, agent-evals gate (judge tier skipped —
# empty key), approval hold, antibody merge. The CI live variant
# (.github/workflows/live-demo.yml) runs the same loop with a real model.
#
# Requires: kind, kubectl, docker, mvn, java 25, and ../agent-operator checked
# out (its CRDs + operator jar).
#
#   hack/demo-kind.sh up      # cluster + operator + medic, ready for the tape
#   hack/demo-kind.sh down    # tear everything down
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=agent-medic-demo
NS=agents
OPERATOR_DIR=${OPERATOR_DIR:-../agent-operator}
EVALS_IMAGE=ghcr.io/hhagenbuch/agent-evals:0.2.1
DEMO=/tmp/medic-demo

down() {
  [ -f "$DEMO/medic.pid" ] && kill "$(cat "$DEMO/medic.pid")" 2>/dev/null || true
  [ -f "$DEMO/operator.pid" ] && kill "$(cat "$DEMO/operator.pid")" 2>/dev/null || true
  kind delete cluster --name "$CLUSTER" 2>/dev/null || true
  rm -rf "$DEMO"
  echo "demo environment removed"
}

if [ "${1:-up}" = "down" ]; then down; exit 0; fi

rm -rf "$DEMO"; mkdir -p "$DEMO/traces" "$DEMO/incidents" "$DEMO/rooms"

echo "==> kind cluster"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER"
kubectl config use-context "kind-$CLUSTER" >/dev/null

echo "==> build medic (exploded classpath) + the operator"
mvn -q -DskipTests package
( cd "$OPERATOR_DIR" && mvn -q -DskipTests package )

echo "==> demo patient image + evals image into kind"
docker build -q -t medic-demo-agent:0.1 hack/demo-agent >/dev/null
kind load docker-image medic-demo-agent:0.1 --name "$CLUSTER"
docker pull -q "$EVALS_IMAGE" >/dev/null
kind load docker-image "$EVALS_IMAGE" --name "$CLUSTER"

echo "==> CRDs, namespace, keyless secret, Agent, suite"
kubectl apply -f "$OPERATOR_DIR/deploy/crds/" >/dev/null
kubectl apply -f deploy/crds/medicproposal-crd.yaml >/dev/null
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# Empty key on purpose: the judge tier skips, deterministic assertions gate.
kubectl -n "$NS" create secret generic anthropic-key --from-literal=api-key="" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f hack/demo/agent.yaml >/dev/null
kubectl -n "$NS" create configmap support-golden-cases \
  --from-file=dataset.yaml=hack/demo/demo-suite.yaml \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "==> operator (background; log: $DEMO/operator.log)"
java -jar "$OPERATOR_DIR"/target/agent-operator-*.jar > "$DEMO/operator.log" 2>&1 &
echo $! > "$DEMO/operator.pid"

echo "==> medic (background, controller mode + replayed Surgeon; log: $DEMO/medic.log)"
MEDIC_KUBERNETES_ENABLED=true \
MEDIC_KUBERNETES_NAMESPACE="$NS" \
MEDIC_SURGEON_SCRIPT_FILE=hack/demo/surgeon-replay.json \
MEDIC_WATCH_DIR="$DEMO/traces" MEDIC_BUNDLE_DIR="$DEMO/incidents" \
MEDIC_WORK_DIR="$DEMO/rooms" MEDIC_RULES_FILE=config/rules.yaml \
java -cp target/classes:"target/lib/*" io.github.hhagenbuch.medic.MedicApplication \
  > "$DEMO/medic.log" 2>&1 &
echo $! > "$DEMO/medic.pid"

echo "==> waiting for the patient Deployment (the operator creates it)"
for i in $(seq 1 30); do
  kubectl -n "$NS" get deploy support-agent >/dev/null 2>&1 && break
  sleep 2
done
kubectl -n "$NS" rollout status deploy/support-agent --timeout=120s

echo
echo "READY. The tape's beats:"
echo "  cp examples/honesty-incident.trace.jsonl $DEMO/traces/s-support-42.trace.jsonl"
echo "  kubectl -n $NS get mp -w"
echo "  kubectl -n $NS annotate mp <incident> medic.hhagenbuch.io/approved=true"
echo "Tear down with: hack/demo-kind.sh down"
