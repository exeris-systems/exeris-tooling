#!/usr/bin/env bash
#
# Agent-adapter renderer (ADR-085 §I.29, agents-md-schema.md rules 2 and 7).
#
# Rewrites every provider adapter from its canonical source under `.agents/`, preserving the
# destination's own frontmatter.
#
# The marker is written twice, and both placements are load-bearing:
#   * a YAML comment as the first frontmatter line, because the organisation gate
#     (`agents_file_check.py`) reads only the first 600 characters when it looks for one;
#   * an HTML comment below the frontmatter, which is what a human sees on opening the file.
#
# Run it after editing anything under `.agents/`, then verify with the sibling script:
#   tools/agent-adapter-check/agent-adapter-render.sh && tools/agent-adapter-check/agent-adapter-check.sh
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

python3 - <<'PY'
import pathlib, sys

YAML_MARK = "# DO NOT EDIT — generated from {src} (agents-md-schema.md rule 7). Edit the source.\n"
HTML_MARK = ("<!-- DO NOT EDIT. Generated from {src} by the AGENTS.md adapter step\n"
             "     (agents-md-schema.md rule 7). Edit the source, not this file. -->\n")

MAP = {  # canonical kind -> claude path template
    "skills":    ".claude/skills/{n}/SKILL.md",
    "agents":    ".claude/agents/{n}.md",
    "workflows": ".claude/commands/{n}.md",
}

def split_fm(text):
    if not text.startswith("---\n"):
        return None, text
    end = text.index("\n---", 3) + len("\n---\n")
    return text[:end], text[end:].lstrip("\n")

def strip_marker(fm):
    return "".join(l for l in fm.splitlines(keepends=True) if "DO NOT EDIT" not in l)

def render(src: pathlib.Path, dst: pathlib.Path):
    sfm, body = split_fm(src.read_text(encoding="utf-8"))
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
        dfm = sfm if sfm else "---\n---\n"
    else:
        dfm, _ = split_fm(dst.read_text(encoding="utf-8"))
        dfm = strip_marker(dfm) if dfm else (sfm if sfm else "---\n---\n")
    dfm = strip_marker(dfm)
    out = ("---\n" + YAML_MARK.format(src=src.as_posix()) + dfm[len("---\n"):]
           + HTML_MARK.format(src=src.as_posix()) + body)
    before = dst.read_text(encoding="utf-8") if dst.exists() else ""
    dst.write_text(out, encoding="utf-8")
    return int(out != before)

changed = 0
for kind, claude_t in MAP.items():
    base = pathlib.Path(".agents") / kind
    if not base.is_dir():
        continue
    names = ([d.name for d in sorted(base.iterdir()) if d.is_dir()] if kind == "skills"
             else [f.stem for f in sorted(base.glob("*.md"))])
    for n in names:
        src = base / (f"{n}/SKILL.md" if kind == "skills" else f"{n}.md")
        changed += render(src, pathlib.Path(claude_t.format(n=n)))

print(f"agent-adapter-render: {changed} adapter(s) rewritten")
PY
