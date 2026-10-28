#!/usr/bin/env bash
# Fail if anything that looks like a Model API key or a .env value is in the tracked tree or the evidence folder.
set -euo pipefail
cd "$(dirname "$0")/.."
status=0
if [ -f .env ]; then
  while IFS='=' read -r k v; do
    [ -z "$v" ] && continue
    case "$k" in *KEY*|*TOKEN*|*SECRET*|*PASSWORD*) ;; *) continue ;; esac   # only secret-like keys
    v="${v%\"}"; v="${v#\"}"
    [ ${#v} -lt 12 ] && continue
    if git grep -q -F -- "$v" -- . ':!.env' 2>/dev/null; then echo "FAIL: value of $k from .env appears in tracked files"; status=1; fi
    if grep -rq -F -- "$v" docs/evidence 2>/dev/null; then echo "FAIL: value of $k appears under docs/evidence"; status=1; fi
  done < <(grep -E '^[A-Z_]+=' .env)
fi
if git grep -n -E 'Bearer [A-Za-z0-9_\-]{20,}' -- . ':!scripts/secret_scan.sh' >/dev/null 2>&1; then echo "FAIL: bearer token literal in tracked files"; status=1; fi
[ $status -eq 0 ] && echo "secret scan: PASS"
exit $status
