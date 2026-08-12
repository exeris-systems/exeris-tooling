---
name: exeris-tooling-triage
description: Triage and execution plan for a new piece of exeris-tooling work. Invoke when starting a milestone slice or ticket in this repo ("start T-x", "next slice", "let's do G2", "begin work on 0.x"), when a request lands and the owning module is not yet obvious, or when a change looks like it spans exeris-processor / exeris-codegen-core / exeris-codegen-java / exeris-codegen-ts / exeris-codegen-maven-plugin / exeris-e2e-tests. Returns task class, scope, severity, primary risk against the pipeline contract, the agent sequence, and the must-pass validation gates. Skip for one-line single-file edits with an obvious owner.
---

# Exeris Tooling Triage

## Purpose
Classify incoming work, then convert the classification into a minimal, risk-aware execution
plan across `exeris-tooling-{router,architect,implementer,codegen-verification,docs-adr}`.

This skill does not implement changes.

## When to Use
- Starting a milestone slice, ticket, or numbered task in `exeris-tooling`.
- A request lands and the owning module or primary risk is not yet obvious.
- Scope might cross modules or the Java↔TS build boundary — surface that early.
- You need the must-pass gates named up front (compile-gate, e2e, npm test, determinism re-run).
- **Skip** when the task is a one-line, single-file edit with an obvious owner — triage overhead
  is not worth it.

## Output Contract
Return exactly:
1. `task_class` (`PIPELINE_SHAPE` | `PROCESSOR_IMPLEMENTATION` | `GENERATOR_IMPLEMENTATION` | `VERIFICATION` | `DOCS_ADR` | `CROSS_BUILD` | `MULTI_DOMAIN`)
2. `scope` (single-module | cross-module | cross-build [Java↔TS])
3. `severity` (low | medium | high | critical)
4. `primary_risk`
5. `primary_agent`
6. `secondary_handoffs` (ordered, with reason)
7. `execution_plan` (3–5 steps)
8. `validation_gates` (must-pass list, tied to the specific risk surface)
9. `minimal_next_action`

## Classification Heuristics
- `PIPELINE_SHAPE`: module placement, DomainMetadata contract, kernel-target story, ADR-015 alignment.
- `PROCESSOR_IMPLEMENTATION`: `exeris-processor` logic, diagnostics, `javax.lang.model` extraction, `@AutoService` wiring.
- `GENERATOR_IMPLEMENTATION`: any `*Generator.java` or TS emitter (`*-gen.ts`) change.
- `VERIFICATION`: e2e snapshot, compile-gate, determinism harness, TS parity check.
- `DOCS_ADR`: ADR-015 amendment, MIGRATION entry, README/ROADMAP sync.
- `CROSS_BUILD`: change requires coordination across the Maven reactor and the TS npm package.
- `MULTI_DOMAIN`: at least two classes above are first-order concerns.

## Routing Patterns
- `PIPELINE_SHAPE` → `exeris-tooling-architect` primary; `docs-adr` secondary when ADR-015 affected.
- `PROCESSOR_IMPLEMENTATION` → `exeris-tooling-implementer` primary; `codegen-verification` secondary.
- `GENERATOR_IMPLEMENTATION` → `exeris-tooling-implementer` primary; `codegen-verification` mandatory; `docs-adr` secondary when a MIGRATION entry is needed.
- `VERIFICATION` → `exeris-tooling-codegen-verification` primary; `implementer` secondary if new test infra requires code.
- `DOCS_ADR` → `exeris-tooling-docs-adr` primary; `architect` secondary if a new ADR is proposed.
- `CROSS_BUILD` → `exeris-tooling-architect` primary; `implementer` + `codegen-verification` parallel secondaries.
- `MULTI_DOMAIN` → start with `architect`, list all dominant handoffs.

## Default Validation Gates
- `KernelCodegenCompileTest` green (any generator surface change touching imports/type names).
- `KernelCodegenE2ETest` green (any emitted-text change).
- `GeneratedTestsE2ETest` green (any `Kernel*TestGenerator` change — the gate *runs* emitted tests).
- `cd exeris-codegen-ts && npm test` green (any DomainMetadata shape change visible to the TS side).
- Determinism re-run (regenerate twice, byte-identical diff) when emission style changes.
- ADR-015 still satisfied (when the emission idiom is touched).

## Guardrails
- Preserve the single-target story (no Spring/Quarkus/Micronaut/Vanilla generator reintroduction).
- Preserve DomainMetadata as the only processor↔generator contract.
- Preserve determinism and Java/TS parity by default.
- If uncertain between two classes, emit `MULTI_DOMAIN` and state both dominant concerns.

## Completion Criteria
Output is complete only if all nine contract fields are present, each justified in 1–2 concise
bullets, and the validation gates are tied to the specific risk surface rather than listed wholesale.
