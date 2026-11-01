---
title: "0.11.0 migration step: @Schedule is refused at build time (kernel ADR-057 Amendment A1)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### `@Schedule` is refused at build time

The processor refuses `@Schedule`, on whatever element it is declared, with the compile error `EXT-PROC-1030`.
At 1.0 the kernel issues no service principal (kernel ADR-057 Amendment A1), so a declared background
job is refused whatever its action requires: with no principal bound, role and permission requirements
cannot be satisfied. A source with `@Schedule` no longer compiles.

Remove `@Schedule`. Schedule the work outside the generated application (for example
via an external scheduler or orchestrator) until the kernel issues a service principal.
