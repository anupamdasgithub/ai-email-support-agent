#!/bin/bash
# ci-check.sh — minimal CI gate for the AI Email Support Agent repo.
# Run locally before committing, or wire into GitHub Actions (see ci.yml).
#
#   1. Builds the Java workers (compile only — fast fail on broken code)
#   2. Validates every BPMN file is well-formed XML and has BPMN process defs
#
# Exit non-zero if anything fails, so it can gate a commit/push or a pipeline.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAIL=0

echo "=================================================="
echo " CI check — AI Email Support Agent"
echo "=================================================="

# ---- 1. Build workers ----
echo ""
echo ">>> [1/2] Building Java workers (mvn compile)"
if [ -f workers/pom.xml ]; then
  if (cd workers && mvn -q -DskipTests compile); then
    echo "    PASS: workers compile"
  else
    echo "    FAIL: workers did not compile"
    FAIL=1
  fi
else
  echo "    SKIP: workers/pom.xml not found"
fi

# ---- 2. Validate BPMN ----
echo ""
echo ">>> [2/2] Validating BPMN files"
BPMN_FILES=$(find process -name "*.bpmn" 2>/dev/null)
if [ -z "$BPMN_FILES" ]; then
  echo "    SKIP: no .bpmn files under process/"
else
  for f in $BPMN_FILES; do
    # (a) well-formed XML
    if ! python3 -c "import xml.etree.ElementTree as ET; ET.parse('$f')" 2>/dev/null; then
      echo "    FAIL: $f is not well-formed XML"
      FAIL=1
      continue
    fi
    # (b) contains at least one BPMN process definition
    if grep -q "bpmn:process\|<process " "$f"; then
      echo "    PASS: $f (well-formed, has process)"
    else
      echo "    FAIL: $f has no <process> definition"
      FAIL=1
    fi
  done
fi

echo ""
echo "=================================================="
if [ "$FAIL" -eq 0 ]; then
  echo " CI RESULT: PASS"
  echo "=================================================="
  exit 0
else
  echo " CI RESULT: FAIL"
  echo "=================================================="
  exit 1
fi
