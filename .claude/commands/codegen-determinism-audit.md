---
# DO NOT EDIT — generated from .agents/workflows/codegen-determinism-audit.md (agents-md-schema.md rule 7). Edit the source.
description: Audit emitted code for determinism, verifying byte-identical output across runs and locales.
argument-hint: Modified generator classes or PR diff
---
<!-- DO NOT EDIT. Generated from .agents/workflows/codegen-determinism-audit.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Review generator changes against determinism invariants.

Priorities:
1. Time-of-day audit: reject `Instant.now()`, `System.currentTimeMillis()`, `LocalDate.now()`, `new Date()`.
2. Randomness audit: reject `UUID.randomUUID()` and `Random` in emission paths.
3. Collection order: verify maps and sets use sorted or deterministic iteration (`LinkedHashMap`, `TreeMap`, sorted lists).
4. Locale safety: verify `Locale.ROOT` is used in `String.format` and case conversions.
5. Path separators: verify forward slash (`/`) is used for emitted source paths.
6. Regenerate test: propose or verify a regenerate-twice byte comparison (`diff -r`).

Changed scope:
$ARGUMENTS

Please produce:
- Affected generator / scaffold classes
- Determinism findings by category
- Verification test coverage
- Final verdict: APPROVE / CONDITIONAL / REJECT
