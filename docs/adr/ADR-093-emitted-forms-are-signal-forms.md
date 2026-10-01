---
title: "ADR-093 — Emitted forms are Angular Signal Forms"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-093
---

# ADR-093 — Emitted forms are Angular Signal Forms

- **Status:** ACCEPTED (2026-10-01)
- **Deciders:** the founder
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen (`exeris-codegen-ts`, `form-gen`)
- **Visibility:** public
- **Milestone:** 0.9.0 (`docs/codegen-ts-track-plan.md`, P7; implemented by P11, extended by P12)
- **Driven By:** [RFC-2026-06-18](../rfc/RFC-2026-06-18-angular-v22-migration-of-the-ts-emitter.md),
  Phase C (the Signal Forms rewrite, held for its own decision)
- **Relates to:** ADR-092 (classification of the shape change), ADR-047 Amendment 1
  (`FieldRenderModel`), ADR-076 (the 409 the form handles), ADR-036 (handler error statuses)
- **Supersedes / superseded by:** —

## Context and Problem Statement

`form-gen` writes each `<Entity>FormComponent` on Reactive Forms: `FormBuilder.group` with
`Validators.required` / `minLength` / `maxLength` / `pattern` / `min` / `max` derived from
`FieldMetadata`, bound with `formControlName`. Around that core it already carries behaviour the
emitted backend depends on:

- **the routed edit** — an `id` input bound from the `:id` route parameter, the entity loaded through
  `rxResource`, `current` (the host's `entity` input, else the loaded row) and `editMode` derived from
  them;
- **the optimistic lock** — for a `versioned` entity, the version the row was loaded at
  (`loadedVersion`) is sent on update; a 409 sets a form-level `conflict` state with a reload of the
  current row (ADR-076);
- **navigation when routed**, and the `saved` / `cancelled` outputs when embedded.

Angular v22 made Signal Forms stable. Every symbol this decision emits is marked
**stable since v22.0** on angular.dev, imported from `@angular/forms/signals`: `form()`, the schema
validators (`required()`, `minLength()`, `maxLength()`, `min()`, `max()`, `pattern()`, `email()`),
the `FormField` directive (selector `[formField]`), and `submit()`, whose action resolves to
validation errors of the shape `{ fieldTree, kind: 'server', message }`.

RFC-2026-06-18 selected Signal Forms for rewritten form emission and held the rewrite for its own
ADR, because it changes the shape of every emitted form component.

**The question this ADR answers:** what an emitted form is built on, and what it must keep.

## 🏁 The Decision

**Emitted forms are Signal Forms. The rewrite keeps every behaviour of the current form component
listed below, renders its controls through the codegen-ts `FieldRenderModel`, and uses only APIs
marked stable in the Angular major the emitted `package.json` pins.**

1. **The form model.** The component holds the entity's editable value in a `WritableSignal` and
   builds its form with `form(model, schema)`. The schema states the validators that `FieldMetadata`
   declares, with the same mapping as today: `required` → `required()`, `minLength` / `maxLength` →
   `minLength()` / `maxLength()`, `pattern` → `pattern()`, `min` / `max` → `min()` / `max()`. Each
   control is bound with `[formField]`.
2. **Submission.** The submit button calls `submit()` with an action that sends the create or update
   request. Its result shape is the landing point for field-level server errors: when the emitted
   backend sends an error body that names fields, the action maps it to
   `{ fieldTree, kind: 'server', message }` entries. Until then the action returns no field errors.
3. **Carried over from the current form, unchanged in behaviour:**
   - the `id` input, the by-id load through `rxResource`, and `current` / `editMode` derived as today
     (a host's `entity` wins over the load; an `id` always means edit); the `mode` and `entity`
     inputs stay;
   - in edit mode the model is filled from `current` once it is loaded, and submit stays disabled
     until it is;
   - for a `versioned` entity, the version the row was loaded at is sent on update; create sends
     none;
   - **a 409 is a form-level conflict, never a field error** — it sets the conflict state and offers
     a reload of the current row through the same resource, which also picks up the new version;
   - other failures surface as one form-level message through the emitted `http-error` helper;
   - when routed, save and cancel navigate as today; when embedded, the `saved` and `cancelled`
     outputs fire and the host decides;
   - computed fields stay read-only and outside the submitted DTO; the selector and every
     `data-testid` stay.
4. **Controls render through `FieldRenderModel`.** Which control a field gets (input type, select,
   textarea, toggle, date, relationship picker), its format and its layout come from the one internal
   field-render model ADR-047 Amendment 1 establishes, shared with lists and detail. `form-gen` does
   not keep a control mapping of its own.
5. **Stable APIs only.** An emitted form imports from `@angular/forms/signals` only symbols marked
   stable in the pinned Angular major. An experimental or developer-preview API — WebMCP's
   `provideExperimentalWebMcpForms()` and the `experimentalWebMcpTool` option among them — is not
   emitted by default; WebMCP from forms is a later, flag-gated step (RFC-2026-06-18, Phase C).
6. **Parity: TS_ONLY.** The form component has no Java counterpart. The wire contract it speaks — the
   `…Create` / `…Update` DTOs, the version on update, the statuses of ADR-036 and ADR-076 — is
   unchanged by this decision.
7. **Compatibility.** The emitted form component changes shape: `form` becomes a `FieldTree` instead
   of a `FormGroup`, and `FormBuilder` / `ReactiveFormsModule` leave its imports. Under ADR-092 this is
   `breaking (ADR-092)`, permitted in 0.x, and the pull request that lands it adds a MIGRATION entry
   naming what hand-written code must change (code reading `form.value`, `form.controls`, calling
   `patchValue`, or extending the component).

## Consequences

### ✅ Positive Outcomes

- **[+] The form reads as native v22.** Model, validation state and submission are signals, like
  the rest of the emitted app.
- **[+] A place for server field errors.** `submit()`'s result shape is ready for the day the backend
  names fields; only the backend half and its mapping remain.
- **[+] One control vocabulary.** Forms, lists and detail resolve controls through one model, so a
  field renders the same way on every page.
- **[+] WebMCP becomes a small later step.** Angular derives an agent tool from a Signal Form; Reactive
  Forms have no such bridge.

### ⚠️ Trade-offs

- **[-] A breaking change for every consumer with a form.** Hand-written code touching the
  Reactive Forms API must be rewritten after regeneration.
- **[-] The densest spec cluster ripples.** `form-gen` has the largest share of the substring specs;
  the rewrite updates them in the same pull request.
- **[-] Signal Forms is new at stable.** Its validation and touched semantics may still settle across
  v22 minors; the emitter follows them.

### 📋 What is NOT in scope

- **Field-level server errors.** They need an error body from the emitted handler and an ADR-036
  amendment first.
- **WebMCP from forms** — flag-gated and off by default, after P11.
- **The relationship picker's control choice** (Angular Aria or native `<select>`) — P13's design note.
- **Field-level `@UI` hints** (`componentType`, `placeholder`, `helpText`, sections) — they reach the
  form through the 1.x leaf facet (ADR-047).

### 🚫 Non-Goals

- A form DSL or form configuration of our own on top of Signal Forms; the emitted code uses the
  Angular API directly.
- Keeping a Reactive Forms emission path in parallel; there is one form shape.
- Supporting a hand-edited emitted form across regeneration; detached files (L2) are the consumer's.

### ⚠️ Risks and Assumptions

- **Assumes:** `form()`, the schema validators, `[formField]` and `submit()` in
  `@angular/forms/signals` stay stable across the Angular majors the emitted app pins.
- **Assumes:** every control the field-render model resolves has a Signal Forms binding — native
  inputs, `select`, checkbox, date — without a custom control wrapper.
- **Reversed by:** Angular deprecating or reshaping the Signal Forms API in a way the emitter cannot
  follow within one major, or a control the render model needs that Signal Forms cannot bind
  without a custom control layer — either would reopen the form shape.
- **Risk:** the rewrite touches the densest spec cluster and the routed-edit, version and conflict
  logic; a regression there passes the substring specs if a spec pinned the old shape rather than
  the behaviour. The existing behavioural specs are kept and re-pointed, not deleted.

## Alternatives considered

- **Keep Reactive Forms.** Rejected: it leaves the one non-signal surface in the emitted app, and
  forgoes the server-error result shape and the WebMCP bridge the roadmap builds on.
- **Emit both, selected by a flag.** Rejected: two form generators to keep at parity for the routed
  edit, the lock and the conflict, for a 0.x line that may break shape.

## Compliance — testable obligations

1. **Shape** (obligations 1, 2): a `form-gen` spec asserts the emitted component imports `form`,
   the used validators, `FormField` and `submit` from `@angular/forms/signals`, binds controls with
   `[formField]`, and imports neither `FormBuilder` nor `ReactiveFormsModule`.
2. **Carry-over** (obligation 3): specs assert the `id` input and `rxResource` load, `editMode` /
   `current`, the version sent on update and omitted on create, the 409 path setting the conflict
   state and not a field error, routed navigation, and the `saved` / `cancelled` outputs.
3. **One render path** (obligation 4): `form-gen` resolves controls only through `FieldRenderModel`.
4. **Stable only** (obligation 5): a spec fails when an emitted form imports a symbol outside the
   allow-list of stable `@angular/forms/signals` symbols.
5. **Compile** — an emitted sample application with a versioned entity compiles with the pinned
   Angular compiler.
6. **MIGRATION** (obligation 7): the pull request implementing P11 adds the entry.
7. Migration owner: `exeris-tooling`, target 0.9.0.
