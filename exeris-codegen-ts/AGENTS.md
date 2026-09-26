---
title: "exeris-codegen-ts: Angular/TypeScript build-time code generator"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-09-26
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

1. **Angular v22 is the target, reached in phases.** The phases are RFC-2026-06-18's, "Angular v22
   Migration of the TS Emitter" under `docs/rfc/`, still a draft. What the emitters produce today:
   - The emitted `package.json` pins Angular `^22.0.0`, and the application is zoneless.
   - `detail-gen.ts` fetches through `rxResource`.
   - `form-gen.ts` still emits Reactive Forms (`FormBuilder`, `Validators`).
   - `service-gen.ts` still returns `Observable<T>` from `HttpClient`.

   Moving services to `httpResource()`/`rxResource()` is the RFC's phase B. Moving forms to Signal
   Forms is its phase C, which changes the emitted shape and carries an ADR of its own. New or
   rewritten emission follows the RFC's guidelines. An existing emitter changes shape only in the
   phase that owns the change.
   - No deprecated APIs: no `@Input()`/`@Output()` decorators where signal inputs and outputs apply.
2. **The shared codegen rules hold here unchanged.** `DomainMetadata` is the only input
   ([policy](../.agents/policies/domain-metadata-contract.md)), what one emitter handles the other
   handles or says why not ([policy](../.agents/policies/emitter-parity.md)), and output is
   byte-identical for identical input ([policy](../.agents/policies/codegen-determinism.md)). They are
   authored once, in those policies; this file adds only what is TypeScript's.

## Where this does not apply, and what it costs

Not to the Java emitters or the annotation processor, which the root `AGENTS.md` governs, and not to
the emitted application as a product of its own. These constraints judge the generator, and its
output only as evidence of what the generator does.

The cost is carried by consumers: they commit what this package emits. Every idiom change here
rewrites files under their `src/app/generated/`, the default output path, and pinning the emission
to Angular v22 means an application on an older Angular cannot build it. That is why a change of
shape waits for the phase that owns it rather than riding along with an unrelated fix.

## Verification

- `cd exeris-codegen-ts && npm install && npm test` runs the unit and generator test suite.
- Parity with Java emission is verified via `KernelCodegenE2ETest` and cross-build verification.
