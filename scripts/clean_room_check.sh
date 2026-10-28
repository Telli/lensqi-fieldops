#!/usr/bin/env bash
# Clean-room reproducibility check (Track C). Clones the repository into an empty temporary directory and, using only
# what is committed, runs: Python venv + fixture validators, dotnet test, the API on its own port and data directory,
# the hero flow over HTTP with the simulated probe (fake fixes caught, real fix verified), verify-receipt on the bundle
# and on the shipped evidence bundles, and two genuine-call evaluation scenarios when a Meta key is available.
# Prints PASS or FAIL. Integrators run it after every merge; a FAIL stops merges.
#
# Usage: scripts/clean_room_check.sh [--source <git url or path>] [--port 5299] [--keep] [--no-meta]
#   --source   what to clone (default: this checkout's origin remote, i.e. what is pushed)
#   --no-meta  skip the genuine Muse/SAM evaluation step (reported as SKIPPED, never as passed)
# The Meta key is read from MODEL_API_KEY or from the invoking checkout's .env; it is never printed or copied into logs.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$(git -C "$HERE" remote get-url origin 2>/dev/null || echo "$HERE")"
PORT=5299
KEEP=0
META=1
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SOURCE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --no-meta) META=0; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/fieldops-cleanroom.XXXXXX")"
REPO="$WORK/repo"
DATA="$WORK/data"
LOG="$WORK/log"
mkdir -p "$DATA" "$LOG"
BASE="http://127.0.0.1:$PORT"
API_PID=""
FAILS=()
SKIPS=()

step() { printf '\n== %s\n' "$*"; }
ok() { printf '   ok   %s\n' "$*"; }
bad() { printf '   FAIL %s\n' "$*"; FAILS+=("$*"); }
cleanup() {
  [ -n "$API_PID" ] && kill "$API_PID" 2>/dev/null && wait "$API_PID" 2>/dev/null
  if [ "$KEEP" = 1 ]; then echo "kept $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

# json <expr> : read JSON on stdin and print a Python expression over it as `d`
json() { python3 -I -c "import sys,json; d=json.load(sys.stdin); v=$1; print('' if v is None else v)"; }
post() { curl -s -X POST -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' ${2:+-d "$2"} "$BASE$1"; }
code() { curl -s -o /dev/null -w '%{http_code}' -X POST -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' ${2:+-d "$2"} "$BASE$1"; }
upload() { curl -s -H "Idempotency-Key: $(uuidgen)" -F "image=@$2" -F source=RecordedFixture -F "observedAt=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" "$BASE/v1/inspections/$1/observations"; }

step "clone $SOURCE"
if git clone -q --depth 1 "$SOURCE" "$REPO" 2>"$LOG/clone.txt"; then ok "$(git -C "$REPO" log --oneline -1)"; else bad "clone"; cat "$LOG/clone.txt"; echo "RESULT: FAIL"; exit 1; fi
cd "$REPO"

step "python venv and fixture validators"
if python3 -m venv .venv >"$LOG/venv.txt" 2>&1 && .venv/bin/pip install -q -r scripts/requirements.txt -r tools/sam-parser/requirements.txt >>"$LOG/venv.txt" 2>&1; then ok "venv with meta-sam-parser and Pillow"; else bad "pip install (see $LOG/venv.txt)"; fi
for v in validate_expected_twin.py validate_vendor_packs.py test_expected_twin_constraints.py test_sample_pack_constraints.py; do
  if .venv/bin/python scripts/$v >"$LOG/$v.txt" 2>&1; then ok "$v"; else bad "$v"; tail -5 "$LOG/$v.txt"; fi
done

step "dotnet test"
if dotnet test --nologo >"$LOG/test.txt" 2>&1; then ok "$(grep -E 'Passed!' "$LOG/test.txt" | sed -E 's/.*Passed: +([0-9]+).*- ([^ ]+)\.dll.*/\2 \1/' | tr '\n' ' ')"; else bad "dotnet test"; grep -E 'Failed|error' "$LOG/test.txt" | head -10; fi

# The key is passed to the API through its environment only.
KEY="${MODEL_API_KEY:-}"
if [ -z "$KEY" ] && [ -f "$HERE/.env" ]; then KEY="$(grep -E '^MODEL_API_KEY=' "$HERE/.env" | head -1 | cut -d= -f2- | tr -d '"')"; fi

step "start the API on $BASE (Simulated diagnostics, data in a temp directory)"
dotnet build src/LensQi.FieldOps.Api --nologo -v q >"$LOG/build.txt" 2>&1 || bad "API build"
MODEL_API_KEY="$KEY" Storage__DataDirectory="$DATA" Diagnostics__Mode=Simulated \
  dotnet run --project src/LensQi.FieldOps.Api --no-build --no-launch-profile --urls "$BASE" >"$LOG/api.txt" 2>&1 &
API_PID=$!
for _ in $(seq 1 60); do curl -s "$BASE/health" >/dev/null 2>&1 && break; sleep 1; done
HEALTH="$(curl -s "$BASE/health")"
if [ "$(echo "$HEALTH" | json "d['diagnosticsMode']")" = "Simulated" ]; then ok "$HEALTH"; else bad "API did not start (see $LOG/api.txt)"; echo "RESULT: FAIL"; exit 1; fi

step "hero flow over HTTP (simulated probe, no model calls)"
IMG=fixtures/telecom-lab/eval/images
REQS="switch_chassis_visible yellow_cable_near_end_visible post_repair_view_captured"
post /v1/simulation/diagnostics/NODE-A-HEALTH '{"passed":false}' >/dev/null
ID="$(post /v1/inspections '{"fixtureId":"LAB-RACK-01","assetId":"LAB-SW-01"}' | json "d['inspectionId']")"
upload "$ID" "$IMG/f02-wrong-port-down.jpg" >/dev/null
STAGE="$(post "/v1/inspections/$ID/diagnostics/NODE-A-HEALTH:run" | json "d['state']['stage']")"
[ "$STAGE" = "FaultConfirmed" ] && ok "failing simulated probe -> FaultConfirmed" || bad "fault not confirmed ($STAGE)"
post "/v1/inspections/$ID/work-order" '{"summary":"Move the yellow cable to port 2"}' >/dev/null
post "/v1/inspections/$ID/repair-claims" '{"description":"Moved the cable to port 2"}' >/dev/null

DUP="$(upload "$ID" "$IMG/f02-wrong-port-down.jpg")"
VERDICT="$(echo "$DUP" | json "d['changeAnalysis']['verdict']")"; STAGE="$(echo "$DUP" | json "d['state']['stage']")"
[ "$VERDICT" = "DuplicateImage" ] && [ "$STAGE" = "NeedsEvidence" ] && ok "fake fix 1: re-submitted photo -> DuplicateImage, NeedsEvidence" || bad "duplicate not caught ($VERDICT, $STAGE)"
PROBES_BEFORE="$(curl -s "$BASE/v1/inspections/$ID" | json "len(d['diagnostics'])")"
C="$(code "/v1/inspections/$ID/verify")"; PROBES_AFTER="$(curl -s "$BASE/v1/inspections/$ID" | json "len(d['diagnostics'])")"
[ "$C" = "409" ] && [ "$PROBES_BEFORE" = "$PROBES_AFTER" ] && ok "verify refused (409) and no probe ran" || bad "verify on a duplicate: HTTP $C, probes $PROBES_BEFORE -> $PROBES_AFTER"

FAKE="$(upload "$ID" "$IMG/f02-wrong-port-down-post.jpg" | json "d['evidenceId']")"
for r in $REQS; do post "/v1/inspections/$ID/checklist" "{\"requirementId\":\"$r\",\"status\":\"Satisfied\",\"photoEvidenceId\":\"$FAKE\"}" >/dev/null; done
post "/v1/inspections/$ID/acknowledge" >/dev/null
V="$(post "/v1/inspections/$ID/verify")"
[ "$(echo "$V" | json "d['decision']['result']")" = "Denied" ] && ok "fake fix 2: new photo, endpoint still down -> Denied ($(echo "$V" | json "d['state']['stage']"))" || bad "fake fix not denied: $(echo "$V" | head -c 300)"

post /v1/simulation/diagnostics/NODE-A-HEALTH '{"passed":true}' >/dev/null
post "/v1/inspections/$ID/repair-claims" '{"description":"Reseated the cable in port 2"}' >/dev/null
REAL="$(upload "$ID" "$IMG/c03-clear-rotated.jpg" | json "d['evidenceId']")"
for r in $REQS; do post "/v1/inspections/$ID/checklist" "{\"requirementId\":\"$r\",\"status\":\"Satisfied\",\"photoEvidenceId\":\"$REAL\"}" >/dev/null; done
post "/v1/inspections/$ID/acknowledge" >/dev/null
V="$(post "/v1/inspections/$ID/verify")"
KIND="$(echo "$V" | json "d['decision']['outcomeKind']")"
[ "$(echo "$V" | json "d['decision']['result']")" = "Verified" ] && [ "$KIND" = "VerifiedCompleteSimulated" ] && ok "real fix -> Verified, $KIND (never Live)" || bad "real fix not verified as simulated: $(echo "$V" | head -c 300)"

step "receipts"
RV="$(curl -s "$BASE/v1/inspections/$ID/receipt/verify")"
[ "$(echo "$RV" | json "d['isValid']")" = "True" ] && ok "API re-verifies the bundle ($(echo "$RV" | json "len(d['checks'])") checks)" || bad "API receipt verify"
dotnet build tools/LensQi.FieldOps.VerifyReceipt --nologo -v q >>"$LOG/build.txt" 2>&1
for b in "$DATA/receipts/$ID" docs/evidence/receipt-sample docs/evidence/receipt-loopback; do
  if dotnet run --project tools/LensQi.FieldOps.VerifyReceipt --no-build -- "$b" >"$LOG/verify.txt" 2>&1 && grep -q "RECEIPT INTEGRITY: PASS" "$LOG/verify.txt"; then ok "verify-receipt $(basename "$b")"; else bad "verify-receipt $b"; tail -5 "$LOG/verify.txt"; fi
done
TAMPER="$WORK/tampered"; cp -R "$DATA/receipts/$ID" "$TAMPER"
F="$(ls "$TAMPER"/artifacts/* | head -1)"; printf '\x00' | dd of="$F" bs=1 seek=100 count=1 conv=notrunc 2>/dev/null
if dotnet run --project tools/LensQi.FieldOps.VerifyReceipt --no-build -- "$TAMPER" >"$LOG/tamper.txt" 2>&1; then bad "a tampered bundle verified"; else ok "a one-byte flip in an artifact fails verification"; fi

step "evaluation: c01-clear-p2, f01-seated-down (genuine Muse Spark and SAM 3.1)"
if [ "$META" = 0 ] || [ -z "$KEY" ]; then
  SKIPS+=("genuine-call evaluation (no MODEL_API_KEY or --no-meta)"); echo "   SKIPPED: no Meta key available or --no-meta given"
else
  if dotnet run --project tools/LensQi.FieldOps.Eval -- --base "$BASE" --only c01-clear-p2,f01-seated-down >"$LOG/eval.txt" 2>&1 \
     && [ "$(grep -c '^PASS' "$LOG/eval.txt")" = 2 ]; then ok "$(grep '^PASS' "$LOG/eval.txt" | awk '{print $2}' | tr '\n' ' ')"; else bad "evaluation"; grep -E '^(PASS|FAIL)|rror' "$LOG/eval.txt" | head -5; fi
fi

echo
if [ ${#FAILS[@]} -eq 0 ]; then
  [ ${#SKIPS[@]} -gt 0 ] && echo "SKIPPED: ${SKIPS[*]}"
  echo "RESULT: PASS"
  exit 0
fi
echo "RESULT: FAIL (${#FAILS[@]}): ${FAILS[*]}"
exit 1
