---
name: exeris-tooling-codegen-determinism-review
description: Codegen determinism review for exeris-tooling. Use for every PR that touches `*Generator` emission surfaces, scaffold helpers, or text-stable iteration. Catches timestamps, randoms, hash-iteration leakage, and locale-dependent formatting before they reach committed `src/main/generated/`.
---

# Exeris Tooling Codegen Determinism Review

## Purpose
Enforce: same DomainMetadata input → byte-identical emitted output, across runs / machines / locales.

This skill is mandatory for PR reviews touching `exeris-codegen-java/*Generator.java`, `exeris-codegen-ts/src/**`, or shared scaffold/iteration helpers in `exeris-codegen-core`.

## When to Use
- Any PR review in `exeris-codegen-{core,java,ts}`.
- Any change that adds a generator or modifies scaffold extraction.
- Any change to `OutputWriter` or filesystem write path.
- Any PR proposing iteration over a `Map`/`Set` whose iteration order leaks into emitted text.

## Required Inputs
- PR diff or changed file list scoped to `exeris-codegen-*`.
- Affected `*Generator` classes.
- Stated intent of the change (new shape / refactor / scaffold / parity).

## Review Procedure
1. **Scan emitted-text producers** — any `String.format`, text block, `JavaPoet.JavaFile`, or `StringBuilder.append` chain that produces text written to disk.
2. **Time-of-day audit** — reject any `Instant.now()`, `LocalDate.now()`, `System.currentTimeMillis()`, or `new Date()` reachable from emission paths. The 0.1.0 `OutputWriter timestamp drop` is canonical.
3. **Randomness audit** — reject `UUID.randomUUID()` and `Random` in emission paths. Test fixtures are OK.
4. **Iteration-order audit** — flag iteration over `HashMap`, `HashSet`, `ConcurrentHashMap` when the output is text-stable-sensitive. Require sort or `LinkedHashMap`/`TreeMap` upstream.
5. **Locale audit** — flag `String.format`/`String.toLowerCase`/`String.toUpperCase` without `Locale.ROOT` when emitting language-like output.
6. **Path-separator audit** — flag `File.separator` in emitted text paths (use forward slash for source paths).
7. **Determinism harness reachability** — if the change is non-trivial, require a "regenerate twice, `diff -r`, expect empty" check on at least one representative entity.
8. **Decision and report** — produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.

## Decision Logic
- **APPROVE**: No timestamp / random / hash-iteration / locale leak; existing `KernelCodegenE2ETest` substring assertions or `KernelCodegenCompileTest` cover the changed surface.
- **CONDITIONAL**: Fixable determinism gap with named remediation; suggest the minimum harness or sort addition.
- **REJECT**: Active reintroduction of a known determinism regression (timestamps, randoms in emission, hash-order leakage).

## Completion Criteria
- Every emitted-text producer in the change was scanned.
- A "regenerate twice" check was proposed or already covered.
- A verdict and remediation list were provided.

## Review Output Template
1. **Scope analysed** (generators / scaffolds touched)
2. **Determinism findings** (timestamps / randoms / iteration / locale / path)
3. **Coverage** (which existing test catches what; what the change exposes)
4. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
5. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Never let a timestamp into committed generated artefacts.
- Never trust default-`HashMap` iteration in text-stable emission.
- Never claim "low-risk" without naming the test that catches the regression.
