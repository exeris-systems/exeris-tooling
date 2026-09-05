#!/usr/bin/env bash
#
# Agent-adapter fidelity gate (ADR-085 §I.29, agents-md-schema.md rules 2 and 7).
#
# Asserts that every adapter file in .claude/:
#   1. carries the generated marker within the first 600 characters;
#   2. has valid YAML frontmatter matching all canonical fields from its source under .agents/;
#   3. is byte-identical below the frontmatter to its canonical source.
#
# Usage:
#   tools/agent-adapter-check/agent-adapter-check.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

python3 - "$@" <<'PY'
import pathlib, re, sys, yaml

MARKER_WINDOW = 600
GENERATED = re.compile(r"do[- ]not[- ]edit|generated from|@generated", re.I)
SOURCE = re.compile(r"generated from (\S+)")
ROOTS = [".claude/skills", ".claude/agents", ".claude/commands"]

def split_fm(text: str):
    if not text.startswith("---\n"):
        return None, text
    end = text.index("\n---", 3) + len("\n---\n")
    return text[:end], text[end:].lstrip("\n")

def parse_fm(fm_text: str | None):
    if not fm_text or not fm_text.startswith("---\n"):
        return {}
    end = fm_text.index("\n---", 3)
    raw = fm_text[4:end]
    lines = [l for l in raw.splitlines() if not l.strip().startswith("#")]
    return yaml.safe_load("\n".join(lines)) or {}

def body(text: str) -> str:
    """Everything below the YAML frontmatter and any leading marker comment."""
    if text.startswith("---\n"):
        text = text[text.index("\n---", 3) + len("\n---\n"):]
    text = re.sub(r"\A<!--.*?-->\n", "", text.lstrip("\n"), flags=re.S)
    return text.lstrip("\n")

errors, checked = [], 0
for root in ROOTS:
    d = pathlib.Path(root)
    if not d.is_dir():
        continue
    for p in sorted(d.rglob("*.md")):
        rel = p.as_posix()
        text = p.read_text(encoding="utf-8")
        checked += 1
        if not GENERATED.search(text[:MARKER_WINDOW]):
            errors.append(f"{rel}: no generated-from marker in the first {MARKER_WINDOW} characters")
            continue
        m = SOURCE.search(text[:MARKER_WINDOW])
        if not m:
            errors.append(f"{rel}: marker names no source path")
            continue
        src = pathlib.Path(m.group(1))
        if not src.exists():
            errors.append(f"{rel}: source {src} does not exist")
            continue

        # Validate frontmatter fidelity for shared/canonical fields
        sfm, _ = split_fm(src.read_text(encoding="utf-8"))
        dfm, _ = split_fm(text)
        try:
            s_data = parse_fm(sfm)
        except Exception as e:
            errors.append(f"{rel}: source {src} frontmatter is invalid YAML: {e}")
            continue
        try:
            d_data = parse_fm(dfm)
        except Exception as e:
            errors.append(f"{rel}: adapter frontmatter is invalid YAML: {e}")
            continue

        for k, v in s_data.items():
            if d_data.get(k) != v:
                errors.append(f"{rel}: frontmatter field '{k}' differs from source {src} — re-render the adapter")

        # Validate markdown body identity
        if body(text) != body(src.read_text(encoding="utf-8")):
            errors.append(f"{rel}: body differs from {src} — regenerate, do not edit the adapter")

print(f"agent-adapter-check: {checked} provider files checked against .agents/")
for e in errors:
    print(f"  FAIL  {e}", file=sys.stderr)
sys.exit(1 if errors else 0)
PY
