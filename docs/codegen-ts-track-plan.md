---
title: "codegen-ts track — the emitted front consumes what the backend serves"
type: design-note
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-01
---

# codegen-ts track — the emitted front consumes what the backend serves

`@exeris/codegen-ts` has its own version line (`package.json`) and publishes to npm separately from
the Maven artefacts. This note is that line's plan: what the TypeScript emitter owes before 1.0,
in what order, and on which Angular v22 idioms. The [ROADMAP](../ROADMAP.md) keeps one entry that
points here; per-item evidence lives in the pull requests that close them.

Companions: [`generation-expansion-kernel-free-menu.md`](generation-expansion-kernel-free-menu.md)
(the ranked UI-depth menu this plan's stage 3 consumes), [ADR-047](adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.md)
(the `@UI`→`@View` leaf facet), [ADR-076](adr/ADR-076-write-rejection-status.md) (write-rejection
statuses), and the Angular v22 migration RFC
([`RFC-2026-06-18`](rfc/RFC-2026-06-18-angular-v22-migration-of-the-ts-emitter.md)).

## The gap, measured

Both sides have 19 emitters; counting emitters hides the gap. Counting what each side **reads from
the shared contract** shows it. Measured on `main` at `d2db7b3`:

| `DomainMetadata` field | Java call sites | TS call sites | what the emitted front cannot do |
|---|---:|---:|---|
| `versioned` | 9 | 0 | carry the optimistic-lock version on update (see stage 1, PR-A) |
| `relationships` | 5 | 0 | show that Order → Customer: no link, no picker, no related panel |
| `audited` | 7 | 0 | the audit panel keys on field names that happen to be declared |
| `graphMetadata` | 1 | 0 | — (server-side graph sync; JAVA_ONLY by nature, see below) |

Stage 1 closed the first three rows: the TS side now reads `versioned`, `audited` and
`relationships`, and `softDelete` with them (the system-field classification the views share).
Stage 0's gate holds the per-field state from here on.

Beyond fields, the wire contract: the emitted handler answers 400 / 404 / 409 / 500 with **no
body** (`KernelHandlerGenerator`), and the emitted front maps none of them — two delete paths call
`alert()` with a fixed string, every other failure renders the raw error.

**The versioned update is broken, not only unsupported.** `type-gen`'s `systemFieldNames` puts the
version field on the server-owned list, so the `…Update` DTO never carries it. The generated
repository reads a missing version as expected `0`, increments it, and matches `WHERE version = ?`:
the first update of a row succeeds, every later one gets 409.

**The 1.0 annotation metric cannot see any of this.** "51 of 51 SDK annotations reach emitted
output" counts against the union of the Java and TS emitters, so a field only Java reads counts as
covered. The TS line therefore needs its own criterion (stage 0, proposed).

## Stages

Each stage is independently shippable; within a stage, one pull request per item, each green on
its own. Stages 0–2 have no external gate and need no new ADR.

### Stage 0 — a gate that keeps parity honest

A test in `exeris-codegen-ts` that walks the fields of `DomainMetadataSchema` and requires each to
be in exactly one state: **read** by a TS generator, **`JAVA_ONLY`** with a one-line reason, or
**`RESERVED`** (no consumer on either side yet). A new field fails the build until it is
classified. Proposed as the TS line's GA criterion — not yet in the ROADMAP's 1.0 list: 1.0 means no field the backend acts on is silently
ignored by the front. Scheduled after stage 1 so it lands against a table with the stage-1 fields
already moved to *read*.

### Stage 1 — contract parity with the emitted backend — shipped

Shipped as #235 (A0), #236 (A), #239 (B), #240 (C), #238 (D).

Scope follows the emitted Java (`KernelHandlerGenerator`, `KernelRepositoryGenerator`,
`OpenApiPathsBuilder`). A–D are **SHARED** surfaces: the
Java side already acts on the field; the TS side catches up to it.

| PR | item | shape |
|---|---|---|
| **A0** | the edit route edits | `<plural>/:id/edit` loads the form, but the router binds `:id` to an input the form does not declare: the form stays in create mode, empty, and submitting it creates a row. The form takes the `id` input, loads the entity through `rxResource`, derives edit mode from it, and navigates after save or cancel when routed. Prerequisite of A: without it no emitted page reaches the update path. |
| **A** | `versioned` write | When `versioned`, the `…Update` schema and DTO keep the version field; the edit form carries the loaded entity's version and sends it; a 409 surfaces as a conflict with a reload of the current row. Create is unchanged (the server owns the initial version). The version key comes from `systemFields.versionField` on the `versioned` flag, not from the hard-coded default list. Visible change to the emitted `…Update` type → MIGRATION note. |
| **B** | status mapping | One emitted `http-error` helper maps the statuses the emitted handler answers (400 / 404 / 409 / 500 — ADR-036, ADR-076 — plus status 0 for an unreachable server) to user-facing messages; detail's resource `error()`, list's `error.set(err.message)`, the store and saga `err.message` paths and the two `alert()` delete paths go through it. **Status only** — the backend sends no error body. |
| **C** | `audited` panel | The detail view's audit panel renders on `audited` (the Java side adds exactly `createdAt` / `updatedAt`, named by `systemFields`), and shows the version on `versioned`. The hard-coded system-name lists in `detail-gen` / `form-gen` give way to `systemFields`. |
| **D** | relationship link, minimum | A `MANY_TO_ONE` relationship whose field is a UUID foreign key renders as a `routerLink` to the target's detail route in list and detail, resolving the target through the loaded domains. Entity-typed relationship fields wait for stage 3's picker, where the serialised shape is settled. |

**Out of stage 1, and why:**

- **Field-level validation errors in forms.** The backend answers 400 with no body, so there is
  nothing to map onto a field. The client half is ready-made in v22 (below); the contract half is
  a Java error body plus an OpenAPI schema — an ADR-036 amendment, and a Java-side PR first.
- **Route guards.** `guard-gen` checks invented permission names and is attached to no route; the
  backend binds no route policy, so every route is permit-all. A front guard mirroring a backend
  that enforces nothing repeats ADR-079's defect in the other emitter. This is **T53**'s subject,
  and consumes the `@RouteAccess` extraction once the processor writes it.
  Interim, the only safe move is to stop emitting the invented constants.

### Stage 2 — backend-less emission rules

An app with no entities and only `STATIC` views emits and boots today, but carries backend
furniture: `provideHttpClient`, a `proxy.conf.json` for an API that does not exist, `apiUrl`,
`zod` / `rxjs` in `package.json`, empty `types/` / `schemas/` barrels. Stage 2 makes the scaffold
emit only what the metadata needs. It precedes stage 3 because an emitter deepened on a scaffold
carrying dead backend wiring bakes that wiring into every page a studio generates. This is the
absence of a backend, not a second backend target (hard constraint #1 is untouched).

### Stage 3 — the `@View` leaf facet, then the UI-depth cascade

[ADR-047](adr/ADR-047-view-leaf-field-facet-and-ui-subsumption.md) is accepted and unimplemented:
the processor leaves `ComponentNodeMetadata.field` null and the TS schema models it as
`z.record(z.any())`. Order, per the kernel-free menu: **U4** (processor populates the facet; TS
schema types the leaf facet (`ViewFieldMetadata` per ADR-047, today the SDK's `UIFieldMetadata`); strict audit covers its attributes) → **U2** lists → **U5** detail
→ **U3** forms (includes the relationship picker, which completes `relationships`) → `@View`
block depth. Two external gates: ADR-047's coordinated SDK rename (`UIFieldMetadata` →
`ViewFieldMetadata`), and for `@View` block depth the page corpus (ROADMAP, *Presentation views*).
The form reshape rides Angular v22 **Phase C** (Signal Forms), which its RFC marks
ADR-worthy.

### Stage 4 — remaining parity, tests, release

- The per-action stream driver (EV1-stream), unblocked on the pinned kernel.
- Test-emitter coverage: `spec-gen` covers 2 of 18 TS emitters.
- `npm run lint` cannot run (no `eslint.config.*`) and is not in CI.
- **A stability decision for the TS output.** ADR-015's output-stability contract covers
  codegen-core and codegen-java only (MIGRATION lists codegen-ts as out of its scope), so no decision
  says what a change to the emitted Angular app owes its consumers. The organisation's PR
  classification needs one: `breaking (ADR-NNN)` has no ADR to name for a TS-only change, and a
  narrowing of the regenerated view (stage 1, PR-C) fits none of its values. An ADR — the TS
  counterpart of ADR-015 — before the first npm publication.
- The generated header comments carry Javadoc-only tags (`@author`, `@since`) in `.ts` doc comments
  across the emitters; one sweep, separate from feature work.
- First npmjs publication of `@exeris/codegen-ts`.

`graphMetadata` stays **JAVA_ONLY**: its one consumer is server-side graph sync. A graph
*view* is a presentation feature and enters through `@View`, not through this field.

## Angular v22 — which idiom lands where

v22 stabilised Signal Forms, `resource` / `rxResource` / `httpResource`, and made OnPush the
default for new components. The emitter already runs on v22 pins, zoneless, `rxResource` in
detail, native `animate.enter`. What each stage takes from the release:

| stage | v22 idiom | status in v22 | use |
|---|---|---|---|
| 1 (B) | `Resource.error()` / `.reload()` | stable | the status helper reads the resource's error signal; a 409 reloads the row through the same resource instead of a hand-written refetch |
| 1 (A) | — | — | carried on the current form shape; a Signal Forms rewrite is Phase C, not smuggled in here |
| 3 (U3) | Signal Forms — `form()`, `FormField`, schema validators | stable | the form reshape (Phase C, own ADR) |
| 3 (U3) | Signal Forms `submit()` returning `{ fieldTree, kind: 'server', message }[]` | stable | the landing point for field-level server errors once the backend sends them (stage 1, out-of-scope item) |
| 3 (U3/U5) | Angular Aria — Autocomplete, Select, Listbox, Tabs, Accordion | **verify against the installed `@angular/aria` before emitting**; release notes and the guide disagree on its level | relationship picker, tabbed detail, grouped form sections |
| 3 (D→U5) | `rxResource` with `params` derived from the parent resource | stable | related-entity panels that load after the parent; URLs stay in the service (Phase B3 design note), so no `httpResource` in components |
| after 3 | WebMCP from Signal Forms | experimental | flag-gated, off by default, last |

Kept deliberately: the explicit `ChangeDetectionStrategy.OnPush` (v22's default is a scaffold
default, and the emitted code should not depend on it); the stable `Subject` + `debounceTime`
search over the experimental `debounced()`.

## Dependencies on other work

- **T6** makes `DslMapper.routePlural` the kebab-cased SDK plural (`pluralName`), so the
  front's routes match `effectivePath()`. PR-A0's navigation and PR-D's links call that function
  rather than pluralising on their own.
- **`@RouteAccess`** extraction on the Java side will add `routeAccess` to the metadata JSON; the
  Zod schema strips unknown keys silently, so the same change owes the `domain-model.ts` field.
- **T53** owns authorization for both emitters.
