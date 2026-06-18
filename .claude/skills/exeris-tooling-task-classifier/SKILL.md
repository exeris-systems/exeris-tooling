---
name: exeris-tooling-task-classifier
description: Use FIRST, before starting any exeris-tooling build-time change, to triage it — classifies task type (processor / codegen-java / codegen-ts / e2e / docs), scope, severity, and primary risk against the pipeline contract, then recommends the primary agent. Invoke whenever a request lands in exeris-tooling and the owning module or risk surface is not yet obvious. Chain into exeris-tooling-routing-planner for the execution plan.
---

# Exeris Tooling Task Classifier

## Purpose
Classify incoming work before execution starts.

This is a triage skill: it does not implement changes. It identifies task class, scope, and likely ownership across the build-time pipeline (processor / codegen-core / codegen-java / codegen-ts / e2e).

## When to Use
- A request lands in `exeris-tooling` and the owning module or primary risk is not yet obvious.
- Before delegating to any specialist agent, to confirm the right primary.
- When scope might cross modules or the Java↔TS build boundary and you need that surfaced early.
- Skip when the task is a one-line, single-file edit with an obvious owner — triage overhead is not worth it.

## Output Contract
Return exactly:
1. `task_class` (`PIPELINE_SHAPE` | `PROCESSOR_IMPLEMENTATION` | `GENERATOR_IMPLEMENTATION` | `VERIFICATION` | `DOCS_ADR` | `CROSS_BUILD` | `MULTI_DOMAIN`)
2. `scope` (single-module | cross-module | cross-build [Java↔TS])
3. `severity` (low | medium | high | critical)
4. `primary_risk`
5. `recommended_primary_agent`

## Classification Heuristics
- `PIPELINE_SHAPE`: module placement, DomainMetadata contract, kernel-target story, ADR-015 alignment.
- `PROCESSOR_IMPLEMENTATION`: `exeris-processor` logic, diagnostics, `javax.lang.model` extraction, `@AutoService` wiring.
- `GENERATOR_IMPLEMENTATION`: any `*Generator.java` or TS emitter code change.
- `VERIFICATION`: e2e snapshot, compile-gate, determinism harness, TS parity check.
- `DOCS_ADR`: ADR-015 amendment, MIGRATION entry, README/ROADMAP sync.
- `CROSS_BUILD`: change requires coordination across Maven reactor and TS npm package.
- `MULTI_DOMAIN`: at least two classes above are first-order concerns.

## Guardrails
- Preserve single-target story (no Spring/Quarkus/Micronaut/Vanilla generator reintroduction).
- Preserve DomainMetadata as the only processor↔generator contract.
- Preserve determinism and Java/TS parity by default.
- If uncertain between two classes, emit `MULTI_DOMAIN` and state both dominant concerns.

## Completion Criteria
Classification is complete only if all five output fields are present and each justified in 1-2 concise bullets.
