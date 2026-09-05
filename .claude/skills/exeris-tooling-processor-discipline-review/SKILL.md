---
# DO NOT EDIT — generated from .agents/skills/exeris-tooling-processor-discipline-review/SKILL.md (agents-md-schema.md rule 7). Edit the source.
name: exeris-tooling-processor-discipline-review
description: Build-time purity gate for the annotation processor. Invoke before committing or opening a PR that edits anything under exeris-processor/ — ExerisDomainProcessor, javax.lang.model extraction, javac diagnostics, DomainMetadata write-out, @AutoService wiring — or that adds a dependency to exeris-processor/pom.xml, and when reviewing or addressing a review on such a change. Rejects runtime libraries, Spring, IoC containers, and anything that would make the processor load classes from the user's project classpath.
---
<!-- DO NOT EDIT. Generated from .agents/skills/exeris-tooling-processor-discipline-review/SKILL.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
# Exeris Tooling Processor Discipline Review

## Purpose
Enforce build-time-only purity for `exeris-processor`. The processor runs inside the user's `javac` invocation; pulling runtime libs onto its classpath couples user builds to dependency surfaces they did not opt into.

## When to Use
- Any PR adding a dependency to `exeris-processor/pom.xml`.
- Any PR changing diagnostic output (`Messager`, `note`, `printMessage`, error message phrasing).
- Any PR touching the `javax.lang.model` extraction or annotation-value reading paths.
- Any PR touching `@AutoService` discovery, `SupportedAnnotationTypes`, or `SupportedSourceVersion`.

## Required Inputs
- PR diff scoped to `exeris-processor/`.
- Current `exeris-processor/pom.xml` (compile-scope deps).
- Stated intent: extension / bugfix / diagnostic clarity / metadata shape.

## Review Procedure
1. **Classpath audit** — list new compile-scope deps. Permitted: JDK, `eu.exeris:exeris-sdk-source-model`, `@AutoService` (test-scope `compile-testing` if needed). Reject Spring, runtime kernel jars, Jackson runtime types pulled in for anything other than bounded `DomainMetadata` JSON write-out.
2. **User-project classpath audit** — does the change attempt to load classes from the user's compiled outputs (beyond annotation surface + `javax.lang.model`)? Reject.
3. **Discovery audit** — `@AutoService(Processor.class)` is the canonical discovery. Reject alternative `META-INF/services` paths that bypass `@AutoService`, unless explicitly justified.
4. **Diagnostic actionability** — error messages use `e.toString()` (not `e.getMessage()`), point at the offending element, and tell the user the smallest corrective action.
5. **Verbosity gate** — per-entity `note()` chatter stays gated by `-Aexeris.verbose` (added 0.2.0). Default-quiet in CI.
6. **Typed extraction** — annotation values are read through `getString`/`getBoolean`/`getInt`/`getLong` typed helpers (added 0.2.0), not raw cross-cast over the value map.
7. **SDK-AST drift check** — if the SDK annotation surface differs from the AST shape (e.g. `@InternalApi` reconciliation, `@Validation` deprecation), the processor warns/falls back and the migration target is named in a comment.
8. **Decision and report** — produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.

## Decision Logic
- **APPROVE**: Build-time-only, diagnostics actionable, discovery via `@AutoService`, no user-classpath reach.
- **CONDITIONAL**: Fixable diagnostic phrasing or typed-extraction gap.
- **REJECT**: Runtime-lib leakage, user-classpath reach, alternative discovery without rationale.

## Completion Criteria
- New deps explicitly enumerated.
- Diagnostic surface audited.
- Discovery path confirmed.
- Verdict and remediation provided.

## Review Output Template
1. **Scope analysed** (files and pom changes)
2. **Classpath findings** (added deps, justification)
3. **Diagnostic findings** (phrasing, gating, actionability)
4. **Discovery findings** (`@AutoService` integrity)
5. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
6. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Never pull Spring or kernel runtime jars onto the processor classpath.
- Never silently drop `-Aexeris.verbose` gating.
- Never replace `e.toString()` with `e.getMessage()` in failure diagnostics — JDK exceptions often return `null` for `getMessage`.
