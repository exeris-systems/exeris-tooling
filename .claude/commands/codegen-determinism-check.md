---
description: Check that an exeris-tooling change preserves deterministic codegen output — same DomainMetadata → byte-identical emission.
argument-hint: PR diff or files in exeris-codegen-* touched by the change
---

Audit this change for codegen determinism.

Determinism contract:
- Same DomainMetadata input → byte-identical generated output across runs, machines, and locales.
- No `System.currentTimeMillis()`, `Instant.now()`, `LocalDate.now()`, or any timestamp embedded in emitted artefacts.
- No `UUID.randomUUID()` in emitted output (acceptable in tests, never in generators).
- No `HashMap`/`HashSet` iteration leaking into emitted text — sort or use `LinkedHashMap`/`TreeMap` before iteration when the output is text-stable-sensitive.
- No `String.format` with default locale where the format produces numeric output (use `Locale.ROOT`).
- No `File.separator` or path-string assembly that varies between OS.

Change:
$ARGUMENTS

Please review:
1. Does any path in this change introduce a time-of-day, random, or locale-dependent value into emitted output?
2. Does any iteration order rely on hash bucketing for a text-stable surface?
3. Is the change verifiable with a "regenerate twice, diff bytes" harness? If not, propose the minimum harness addition.
4. Does `KernelCodegenE2ETest` (substring snapshot) or `KernelCodegenCompileTest` (compile-gate) currently cover this surface?
5. Minimal correction if determinism is at risk.

Treat regression of the 0.1.0 `OutputWriter timestamp drop` fix as a hard failure.
