# `.claude/` — Claude Code workspace for `exeris-tooling`

This directory is loaded automatically when a Claude Code session opens inside
`~/exeris-systems/exeris-tooling/`. It exists alongside the repo-root [`CLAUDE.md`](../CLAUDE.md)
and works as the operating context for AI assistants on the build-time pipeline
(annotation processor + Java/TS code generators + e2e conformance).

## Layout

- `agents/` — sub-agents Claude can launch via the `Agent` tool (or the user can invoke directly):
  - `exeris-tooling-router.md` — entrypoint triage; classifies work and routes to the right specialist
  - `exeris-tooling-architect.md` — pipeline shape, module placement, ADR-015 emission strategy alignment
  - `exeris-tooling-implementer.md` — concrete code changes in processor / codegen-core / codegen-java / codegen-ts
  - `exeris-tooling-codegen-verification.md` — determinism, parity, compile-gate, e2e snapshot evidence
  - `exeris-tooling-docs-adr.md` — ADR-015 and downstream-doc drift control
- `commands/` — slash commands invocable as `/<command-name>`:
  - `codegen-determinism-check.md`, `processor-purity.md`, `emitter-parity.md`, `kernel-target-discipline.md`
- `skills/` — invocable skills (`/<skill-name>`):
  - `exeris-tooling-task-classifier`, `exeris-tooling-routing-planner`
  - `exeris-tooling-codegen-determinism-review`, `exeris-tooling-emitter-parity-review`
  - `exeris-tooling-processor-discipline-review`, `exeris-tooling-kernel-target-discipline`

## Doctrine — single source

Project doctrine is **not** duplicated under `.claude/` to avoid drift:

- **`/CLAUDE.md`** (repo root) — auto-loaded operating context (pipeline shape, hard constraints, scoped bans, build commands).
- **`docs/adr/ADR-015-codegen-emission-strategy.md`** — founding decision for emission strategy.
- **`docs/MIGRATION-0.x-to-1.0.md`** — break list across trains.
- **`README.md`** + **`ROADMAP.md`** — pipeline narrative and milestone scope.

When skills/agents need policy context, they reference these — they do not restate them.
