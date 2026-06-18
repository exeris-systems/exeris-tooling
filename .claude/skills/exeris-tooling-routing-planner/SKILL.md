---
name: exeris-tooling-routing-planner
description: After triage (see exeris-tooling-task-classifier), use to turn a classified exeris-tooling task into an execution plan — primary agent, ordered secondary handoffs, validation gates tied to the risk surface, and the minimal next action. Invoke when a build-time task spans modules or needs an explicit agent sequence and gate list before implementation starts.
---

# Exeris Tooling Routing Planner

## Purpose
Given a classified task (see `exeris-tooling-task-classifier`), produce a minimal, risk-aware execution order across `exeris-tooling-{router,architect,implementer,codegen-verification,docs-adr}`.

## When to Use
- Right after `exeris-tooling-task-classifier`, to convert the classification into an actionable plan.
- When a task spans modules or the Java↔TS build boundary and the agent sequence is non-trivial.
- When you need the must-pass validation gates named up front (compile-gate, e2e, npm test, determinism re-run).
- Skip for trivial single-agent tasks where the plan is one obvious step.

## Output Contract
Return:
1. `primary_agent`
2. `secondary_handoffs` (ordered list with reason)
3. `execution_plan` (3–5 steps)
4. `validation_gates` (must-pass list)
5. `minimal_next_action`

## Routing Patterns
- `PIPELINE_SHAPE` → `exeris-tooling-architect` primary; `docs-adr` secondary when ADR-015 affected.
- `PROCESSOR_IMPLEMENTATION` → `exeris-tooling-implementer` primary; `codegen-verification` secondary.
- `GENERATOR_IMPLEMENTATION` → `exeris-tooling-implementer` primary; `codegen-verification` mandatory; `docs-adr` secondary when MIGRATION entry needed.
- `VERIFICATION` → `exeris-tooling-codegen-verification` primary; `implementer` secondary if new test infra requires code.
- `DOCS_ADR` → `exeris-tooling-docs-adr` primary; `architect` secondary if new ADR proposed.
- `CROSS_BUILD` → `exeris-tooling-architect` primary; `implementer` + `codegen-verification` parallel secondaries.
- `MULTI_DOMAIN` → start with `architect`, list all dominant handoffs.

## Default Validation Gates
- `KernelCodegenCompileTest` green (any generator surface change touching imports/type names).
- `KernelCodegenE2ETest` green (any emitted-text change).
- `exeris-codegen-ts && npm test` green (any DomainMetadata shape change visible to TS side).
- Determinism re-run (regenerate twice, byte-identical diff) when emission style changes.
- ADR-015 still satisfied (when emission idiom touched).

## Completion Criteria
Output is complete only if all five contract fields are present and validation gates are tied to the specific risk surface.
