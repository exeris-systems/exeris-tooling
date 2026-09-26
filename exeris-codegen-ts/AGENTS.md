---
title: "exeris-codegen-ts: Angular/TypeScript build-time code generator"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-09-12
paths:
  - "exeris-codegen-ts/**"
enforced-by:
  - "npm test"
  - "KernelCodegenCompileTest"
---

# exeris-codegen-ts

Scope-specific constraints for the TypeScript and Angular code generator of the Exeris ecosystem.
Parent contracts in the repository root [`AGENTS.md`](../AGENTS.md) apply in full; this file
restricts behaviour for the frontend emission pipeline.

## Mission and scope

`exeris-codegen-ts` is a standalone npm package that consumes canonical `DomainMetadata` JSON
and generates production-grade Angular v22 TypeScript artefacts (components, services, stores,
guards, forms, lists, details, app structure, and sagas).

It is built and tested independently from the Java Maven reactor.

## Operating contract

1. **Angular v22 canon:** Emitted code strictly targets Angular v22+.
   - Use Signal Forms (`formGroup`, `formControl`, signal-based state).
   - Use `httpResource` / `rxResource` for reactive resource fetching.
   - Zero deprecated APIs (no legacy `@Input()`/`@Output()` decorators where signal inputs/outputs apply).
2. **DomainMetadata sole contract:** Emitters consume `DomainMetadata` JSON only. Emitters must not
   assume or require direct access to Java compiler elements or classpath symbols.
3. **Java/TS emitter parity:** Any field or feature present in `DomainMetadata` that is handled by
   `exeris-codegen-java` must be considered and matched with appropriate Angular artefacts.
4. **Determinism:** Identical `DomainMetadata` input yields byte-identical TypeScript output.
   Sort object keys, imports, and component definitions deterministically.

## Verification

- `cd exeris-codegen-ts && npm install && npm test` runs the unit and generator test suite.
- Parity with Java emission is verified via `KernelCodegenE2ETest` and cross-build verification.
