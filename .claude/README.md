# `.claude/` — generated adapters and provider configuration

This directory is **not** where project rules are authored. Per
[`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md)
rules 2 and 7, the canonical semantic source is [`.agents/`](../.agents) and this directory adapts it
for Claude Code.

- `skills/`, `agents/`, `commands/` — **generated** from `.agents/skills`, `.agents/agents` and
  `.agents/workflows`. Edit the source.
- `settings.local.json` — provider-owned local configuration. Never semantic content.

A change made in this directory is lost the next time the renderer runs. That is the one thing to
remember.

## Rendering and checking them

Two committed scripts, no hand-editing:

```bash
tools/agent-adapter-check/agent-adapter-render.sh   # rewrite every adapter from .agents/
tools/agent-adapter-check/agent-adapter-check.sh    # assert each body still matches its source
```

The renderer preserves provider frontmatter and replaces the body from the canonical `.agents/`
file. The checker asserts body identity and confirms that the generated-from marker appears in the
first 600 characters.
