#!/usr/bin/env bash
#
# Agent-adapter renderer (ADR-085 §I.29, agents-md-schema.md rules 2 and 7).
#
# Rewrites every provider adapter from its canonical source under `.agents/`.
# Canonical shared fields (name, description) and all source frontmatter fields
# flow from .agents/ into the adapter, while any provider-only frontmatter fields
# already present in the destination are preserved.
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
import pathlib, sys, yaml

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

def parse_fm(text):
    if not text or not text.startswith("---\n"):
        return {}
    end = text.index("\n---", 3)
    raw = text[4:end]
    lines = [l for l in raw.splitlines() if not l.strip().startswith("#")]
    try:
        return yaml.safe_load("\n".join(lines)) or {}
    except Exception:
        return {}

def render(src: pathlib.Path, dst: pathlib.Path):
    sfm, body = split_fm(src.read_text(encoding="utf-8"))
    src_data = parse_fm(sfm)

    dst_data = {}
    if dst.exists():
        dfm, _ = split_fm(dst.read_text(encoding="utf-8"))
        dst_data = parse_fm(dfm)

    # Start with canonical metadata from .agents/ source
    merged = dict(src_data)
    # Preserve any provider-only fields already present in dst (e.g. tools, model if not in src)
    for k, v in dst_data.items():
        if k not in merged:
            merged[k] = v

    if merged:
        rendered_fm = yaml.dump(merged, sort_keys=False, width=10000, allow_unicode=True)
        out = ("---\n" + YAML_MARK.format(src=src.as_posix())
               + rendered_fm
               + "---\n"
               + HTML_MARK.format(src=src.as_posix())
               + body)
    else:
        out = ("---\n" + YAML_MARK.format(src=src.as_posix())
               + "---\n"
               + HTML_MARK.format(src=src.as_posix())
               + body)

    before = dst.read_text(encoding="utf-8") if dst.exists() else ""
    if not dst.exists():
        dst.parent.mkdir(parents=True, exist_ok=True)
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
