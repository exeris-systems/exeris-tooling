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
- `skills/` — invocable skills (`/<skill-name>`, also auto-triggered by `description`):
  - `exeris-tooling-triage` — classify a new slice + produce the agent sequence and gate list
  - Pipeline-contract gates: `exeris-tooling-codegen-determinism-review`,
    `exeris-tooling-emitter-parity-review`, `exeris-tooling-processor-discipline-review`,
    `exeris-tooling-kernel-target-discipline`
  - `exeris-tooling-consumer-build-contracts` — ADR-055 / ADR-058 / ADR-060; what emitted code may
    require of the downstream build
  - `exeris-tooling-strict-audit-review` — `-Aexeris.strict` INERT_* registry hygiene
  - `exeris-tooling-adr-shape-gate` — Research/RFC/ADR shape + registry reservation
  - `exeris-tooling-detach-output-discipline` — committed-L1 / detach lifecycle (hard-constraint #6)
  - `exeris-tooling-angular-v22-emission` — keeps emitted Angular idioms on the v22 canon; gates the phased A→B→C migration

There is no `commands/` directory: skills are already invocable as `/<skill-name>`, so shim
commands that only delegated to a same-named skill were removed (they duplicated listing entries
and diluted skill routing).

## Writing a skill `description`

A skill is selected from its `description` **before** the diff is read, so a description phrased as
a condition on the diff ("use for every PR that touches emission surfaces") is unevaluable at
selection time and never fires. Key each description on two things observable at that moment:
the **action being asked for** ("before committing or opening a PR", "when reviewing or addressing
a review", "when the ask is to write an ADR") and **concrete paths or symbols** the session has
just touched (`exeris-codegen-java/`, `*-gen.ts`, `OutputWriter`, `ExerisDomainProcessor`).

## Doctrine — single source

Project doctrine is **not** duplicated under `.claude/` to avoid drift:

- **`/CLAUDE.md`** (repo root) — auto-loaded operating context (pipeline shape, hard constraints, scoped bans, build commands).
- **`docs/adr/ADR-015-codegen-emission-strategy.md`** — founding decision for emission strategy.
- **`docs/MIGRATION-0.x-to-1.0.md`** — break list across trains.
- **`README.md`** + **`ROADMAP.md`** — pipeline narrative and milestone scope.

When skills/agents need policy context, they reference these — they do not restate them.
