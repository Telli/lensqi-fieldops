#!/usr/bin/env python3
"""Append the HEAD commit to docs/HACKATHON_CONTRIBUTIONS.md and commit the ledger.

    python scripts/ledger_add.py "<what was built>" "<test evidence>"

Run once after each feature commit. The row carries the commit time, short SHA, the two texts and "None" for prior code.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LEDGER = ROOT / "docs" / "HACKATHON_CONTRIBUTIONS.md"
MARKER = "| _append as work lands_ | | | | |"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    what, evidence = sys.argv[1], sys.argv[2]
    sha, when = git("rev-parse", "--short", "HEAD"), git("log", "-1", "--format=%cI")
    text = LEDGER.read_text(encoding="utf-8")
    if text.count(MARKER) != 1:
        print("ledger marker missing or duplicated", file=sys.stderr)
        return 1
    if f"`{sha}`" in text:
        print(f"ledger already has {sha}")
        return 0
    row = f"| {when} | `{sha}` | {what} | {evidence} | None |"
    LEDGER.write_text(text.replace(MARKER, row + "\n" + MARKER), encoding="utf-8")
    git("add", str(LEDGER.relative_to(ROOT)))
    git("commit", "-q", "-m", f"Ledger: record {sha}\n\nCo-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>")
    print(f"ledger row added for {sha} and committed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
