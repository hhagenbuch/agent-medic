#!/usr/bin/env bash
# Regenerate the MedicProposal CRD from the Java model into deploy/crds/.
# (The agents.hhagenbuch.io CRDs are also generated from the client-side
# mirror classes, but agent-operator owns those — we deliberately do not ship them.)
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q -DskipTests compile
cp target/classes/META-INF/fabric8/medicproposals.medic.hhagenbuch.io-v1.yml deploy/crds/medicproposal-crd.yaml
echo "synced deploy/crds/medicproposal-crd.yaml"
