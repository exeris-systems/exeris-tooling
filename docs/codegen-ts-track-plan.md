---
title: "codegen-ts track — the emitted front consumes what the backend serves"
type: design-note
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-02
---

# codegen-ts track — the emitted front consumes what the backend serves

`@exeris/codegen-ts` is versioned in lockstep with the Maven reactor: one tag `vX.Y.Z` releases
both, the Maven artefacts to Maven Central and the package to npmjs under the `@exeris` scope. The
package consumes the `DomainMetadata` JSON the same release's processor writes, so a separate version
line would need a compatibility matrix for no gain. This note is the TS side's plan: what the TypeScript emitter owes before 1.0,
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
covered. The TS line therefore needs its own criterion: stage 0 gates it, and the no-`GAP`
criterion below is proposed.

## 0.9.0 — what the TS side ships with the cut

The 0.9.0 cut waits for this scope. Each row is one pull request; the order respects the
dependency column. The cost is the whole release's: the Maven artefacts reach Maven Central only
with this scope, so a slip in any row — P11 is the largest, and P14 and P15 depend on the SDK moving
the ui-kit to npmjs and on the `@exeris` org there — delays the Java side too.

| id | change | modules | ADR | depends on | size |
|---|---|---|---|---|---|
| P1 | Lockstep version: `package.json` follows the reactor (`0.9.0-SNAPSHOT`), and a CI guard fails when the two differ | codegen-ts, build.yml | — | — | S |
| P2 | Field-schema honesty: `FieldMetadataSchema` / `UIMetadataSchema` keys that never arrive (`inList`, `inDetail`, `order`, `ui`, `listColumns`) are removed or classified, extending the stage-0 gate below the top level | codegen-ts | — | — | S–M |
| P3 | Strict-audit honesty: a field-level `@UI` and the `@Field` attributes the processor drops (`inList`, `inDetail`, `order`, `group`, `cssClass`, `ui`) warn instead of passing silently | processor | — | — | S |
| P4 | Stage 2: backend-less emission | codegen-ts | — | — | M |
| P5 | ADR: stability of the emitted TS output (the counterpart of ADR-015, which covers codegen-core and codegen-java only) | docs | ADR | — | S |
| P6 | ADR-047 amendment: until the 1.x facet, lists, detail and forms render through one internal `FieldRenderModel` fed by `FieldMetadata`; the facet later feeds the same model | docs | ADR amendment | — | S |
| P7 | ADR: emitted forms are Signal Forms (Phase C of the Angular v22 RFC) | docs | ADR | P5 | S |
| P8 | `FieldRenderModel`: one function resolving control, format, alignment and picker from what `FieldMetadata` and relationships carry; output byte-identical | codegen-ts | — | P2, P6 | S–M |
| P9 | U2 — lists: column types, sorting, real filters, page size, row actions | codegen-ts | — | P8 | M |
| P10 | U5 — detail: sections and related-entity panels | codegen-ts | — | P8 | S–M |
| P11 | `form-gen` on Signal Forms at parity — the routed edit (`id` + `rxResource`), the version carried on update and the 409 conflict with reload all survive | codegen-ts | P7 | P7 | L |
| P12 | U3 — forms from metadata on Signal Forms | codegen-ts | — | P8, P11 | M |
| P13 | Relationship picker from `@Relationship.displayField`, on Angular Aria only if the installed `@angular/aria` marks the symbols stable (CI reads its `.d.ts`), else a native `<select>` | codegen-ts | design note | P12 | M |
| P14 | ui-kit from npmjs: the emitted `package.json` / `.npmrc` follow the ui-kit's move off GitHub Packages | codegen-ts | — | the SDK publishing ui-kit to npmjs | S |
| P15 | The release workflow also releases `@exeris/codegen-ts` to npmjs on the same tag (`repository`, `files`, `publishConfig.access`, provenance) | release.yml, codegen-ts | — | P1, P5, the `@exeris` org on npmjs | M |
| P16 | Nested schemas follow what the processor writes: saga steps keep `command` / `compensation` / `service` (saga-gen reads a `compensatingAction` that never arrives), `@Action` keeps `methodName` / `routeAccess` / `producesEvents` / `resultType`, `@DomainEvent` keeps `topic` / `aggregateType`, `@EventSourced` reads `snapshotEvery`; `internalApi.hidden` either gets written by the processor or stops being read | codegen-ts, processor if `hidden` is written | — | P2 | M |
| P17 | The entity-level `@UI` view flags take effect: `listView` / `detailView` / `createForm` / `editForm` (and `searchable` / `filterable`) decide what the TS emitter writes; their strict-audit inert entries go in the same change | codegen-ts, processor | — | P2, P3 | S–M |
| P18 | Emitted headers and footers carry no per-release value: the hard-coded versions in file headers, the landing and app footers go, and the CLI `--version` reads `package.json`; a spec keeps them out (ADR-092) | codegen-ts | — | — | S |
| P19 | The emitted app imports `@exeris/ui-kit/styles` (the `.exeris-*` component classes) after `/theme`, a CARD block uses `exeris-card`, form controls carry their own border, padding and focus ring, rich text gets `@tailwindcss/typography`, and the scaffold is Tailwind v4 only, guarded by a spec | codegen-ts | — | P14 | S–M |
| P20 | The generated list, detail and form components — their controls, tables, actions, and error and conflict panels — style themselves through the kit's component classes (`exeris-btn` with `-primary`, `-secondary`, `-danger`, `-ghost`, `-sm`; `exeris-input`, `exeris-select`, `exeris-textarea`, `exeris-checkbox`, `exeris-label`, `exeris-help-text`, `exeris-error-text`, `exeris-input-error`, `exeris-table`, `exeris-card` with `-header`, `-body`, `-footer`; `exeris-alert` with its four variants; `exeris-badge` with `-primary`, `-success`, `-warning`, `-danger`; `exeris-spinner`) instead of inline utility strings, so a consumer restyles them in `styles.css` without editing generated files | codegen-ts | ADR-092 | P9, P10, P11, P19; `@exeris/ui-kit` 0.2.1 (the classes complete on Tailwind v4) | M |

**The kit's component classes P20 does not use leave its frozen contract.** The chip, color, editor,
file, radio, range, rating and toggle controls, the scroll and truncation helpers, `exeris-btn-lg`
and `exeris-badge-gray` have no emitter in 0.9 — field-level `@UI`, which would choose the controls,
stays unread — so `@exeris/ui-kit` 0.3.0, after P20, moves them to an entry outside the names it
freezes at 1.0. They return to it additively when an emitter uses them.

**Field-level `@UI` stays unread in 0.9.** The processor reads only the entity-level `@UI` view
flags; extracting the field-level hints would write keys the SDK `-io` reader does not read
(ADR-042) and would extend the `@UI` path ADR-047 subsumes into `@View`. Hints only `@UI`,
`@UIGroup` or `@Tab` carry — `componentType`, `gridSpan`, `placeholder`, `helpText`, sections,
tabs — arrive with the 1.x facet, through `FieldRenderModel`.

**Out of 0.9:** the ADR-047 facet and the `@UI` deprecation (1.x, per the SDK roadmap); `@View`
G1–G6 (an SDK RFC); field-level server errors (a Java error body and an ADR-036 amendment first);
WebMCP (after P11, flag-gated); route guards (T53).

## Stages

Each stage is independently shippable; within a stage, one pull request per item, each green on
its own. Stages 0–2 have no external gate and need no new ADR.

### Stage 0 — a gate that keeps parity honest — shipped

`src/models/contract-coverage.ts` classifies every `DomainMetadataSchema` field, and
`test/contract/contract-coverage.spec.ts` measures what the TS generators actually read by running
the orchestrator over proxied metadata. Four states:

- **`READ`** — a TS generator reads it;
- **`JAVA_ONLY`** — only the Java side acts on it, and the front has nothing to do with it;
- **`RESERVED`** — no emitter on either side acts on it yet;
- **`GAP`** — the Java side acts on it and the front owes a counterpart it does not emit yet.

The last three carry a reason. A field added to the schema, or one a generator starts or stops
reading, fails the build until it is classified. Today: 18 `READ`, 4 `JAVA_ONLY`, 14 `RESERVED`,
1 `GAP` (`uiMetadata`: the entity-level `@UI` view flags no emitter honours — P17). One level
down, `FIELD_CONTRACT_COVERAGE` has 1 `GAP` (`inUpdate`) and `UI_CONTRACT_COVERAGE` 6 (the view flags).

**Proposed TS 1.0 criterion: no field in `GAP`** — no field the backend acts on is silently ignored
by the front. Not yet in the ROADMAP's 1.0 list.

**`realTimeApi` is `READ`.** The orchestrator composes a stream client for every SSE route the
emitted application serves, under `src/app/services/` beside the services and exported from the app
barrel, whenever services are generated: the live-view client (`<entity>.stream.ts`, a native
`EventSource` on `GET {base}/stream`) for a `realTimeApi` entity, registering one listener per
`@DomainEvent` name because every frame the handler sends is named; and the action stream clients
(`<entity>.action-streams.ts`, `fetch` over `POST {base}/{id}/actions/{kebab}`) for each
`@Action(streaming)`, which has no respond-once route and so no service method.
`contract/stream-routes.json` pins those routes on both sides (`StreamRouteParityE2ETest`,
`stream-route-parity.spec.ts`). A tenant-partitioned entity (`TENANT` or `UNIVERSE`) gets neither
client until the server guards its stream routes — see the ROADMAP item *Stream endpoints carry no
tenant guard*. `guard-gen` and `query-builder-gen` remain uncomposed.

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
`z.record(z.any())`. The SDK roadmap places the facet in the 1.x line, together with the
coordinated `UIFieldMetadata` → `ViewFieldMetadata` rename and `@UI`'s deprecation.

The entity-side cascade does not wait for it: **U2** lists, **U5** detail and **U3** forms (on
Signal Forms, with the relationship picker) ship in 0.9.0 through `FieldRenderModel` (see the 0.9.0
section above). In 1.x the processor populates the facet, the TS schema types it, and it feeds the
same model — no second rendering path. `@View` block depth stays gated on the page corpus (ROADMAP,
*Presentation views*).

### Stage 4 — remaining parity, tests, release

- The per-action stream producer (EV1-stream): the action stream handler still sends only the
  keep-alive scaffold, waiting on an SDK widening that links a streaming action to its event
  types; the TS client already parses its named frames.
- Test-emitter coverage: `spec-gen` covers 2 of 18 TS emitters.
- `npm run lint` cannot run (no `eslint.config.*`) and is not in CI.
- **A stability decision for the TS output.** ADR-015's output-stability contract covers
  codegen-core and codegen-java only (MIGRATION lists codegen-ts as out of its scope), so no decision
  says what a change to the emitted Angular app owes its consumers. The organisation's PR
  classification needs one: `breaking (ADR-NNN)` has no ADR to name for a TS-only change, and a
  narrowing of the regenerated view (stage 1, PR-C) fits none of its values. An ADR — the TS
  counterpart of ADR-015 — before the first npm publication.
- First npmjs publication of `@exeris/codegen-ts`.
- The frontend-only starter (ADR-091 Amendment 1): an npm starter whose metadata source is a
  backend's published contract artifact (ADR-048). The backend + frontend opt-in in
  `exeris-app-parent` also waits for this publication.

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
