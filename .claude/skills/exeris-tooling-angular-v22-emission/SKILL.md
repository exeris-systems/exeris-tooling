---
name: exeris-tooling-angular-v22-emission
description: Angular v22 emission discipline for exeris-codegen-ts. Use whenever authoring or modifying a TS generator (*-gen.ts) or the emitted app scaffold — keeps emitted Angular idioms on the v22 canon (Signal Forms, httpResource, dropped deprecations) and gates the phased A→B→C migration.
---

# Exeris Tooling — Angular v22 Emission Discipline

## Purpose
Keep what `exeris-codegen-ts` **emits** aligned with the Angular v22 canon while the phased
migration is in flight. This skill is self-contained — the operative canon (phase gate +
emit/stop quick-reference below) lives here. The fuller rationale and verified v22 facts live in
the working RFC at `docs/rfc/RFC-2026-06-18 Angular v22 Migration of the TS Emitter.md` (an
intentionally untracked local draft until accepted — same treatment as the reattach RFC; once
accepted it becomes the authoritative ADR, which a fresh checkout will have). Treat this skill as the procedure that
applies the canon and the boundary that catches drift; defer to the ADR if/when it lands.

The emitter package itself is Angular-agnostic (emits strings); the migration surface is the
**emitted output** — `app-structure-gen.ts` (scaffold `package.json` + `app.config.ts`) and the
per-shape generators.

## When to Use
- Editing any `exeris-codegen-ts/src/generators/**/*-gen.ts` or `app-structure-gen.ts`.
- Touching the emitted scaffold's dependency pins, `app.config.ts` providers, or form/list/detail shape.
- Adding or rewriting a generator shape during Phase A / B / C.
- Reviewing a PR that changes emitted Angular idioms.

## Phase gate (which changes belong to which PR)
- **Phase A — compat bump (urgent, ~1 PR, no component/form shape change):** scaffold pins
  `@angular/* ^21→^22`, `typescript ~5.9→~6` (v22 *requires* TS 6 and drops 5.9), Node engine 20→22;
  **drop `withFetch()`** (fetch is default in v22). This still diffs `app.config.ts` providers +
  `package.json` pins — "no shape change" means no component/form shape change, not a zero-diff PR.
- **Phase B — idiom modernisation (additive).** Done: **B1 scaffold cleanup** (PR #96 — dropped dead
  `@angular/platform-browser-dynamic`, `engines.node`/`@types/node` → Node-22 floor, builder
  `@angular-devkit/build-angular` → `@angular/build`). Remaining: `httpResource()` / `rxResource()` in
  services (today return `Observable` — **stable**, but reshapes the consumed service contract → own PR +
  design note); optional `@Service` (genuinely new in v22 — first-class sugar over
  `@Injectable({providedIn:'root'})`, not an Exeris construct; cosmetic); audit emitted template `?.`
  (now `undefined`, not `null`).
  - **NOT adopted — `debounced()`:** it is an **experimental** v22 API (`@angular/core`) and returns a
    `Resource<T>` (`.value()`), not a debounced signal. Per the no-experimental-by-default rule it stays
    out of default emission; the existing `Subject + debounceTime + takeUntilDestroyed` in `list-gen` is
    stable, idiomatic v22 — keep it.
  - **NOT adopted — dropping explicit `OnPush`:** v22's OnPush-default is a CLI-scaffold-level default on
    new apps, not a framework behaviour change for components without an explicit strategy. Keep the
    explicit `ChangeDetectionStrategy.OnPush` to avoid depending on that default.
- **Phase C — reshape (ADR-worthy):** `form-gen.ts` Reactive Forms → **Signal Forms** (real emitted-shape
  change → needs its own ADR + emitter-parity note); then **WebMCP** (`provideExperimentalWebMcpForms()`
  / `experimentalWebMcpTool` / `declareExperimentalWebMcpTool`) **flag-gated, OFF by default** —
  Angular *experimental* API. WebMCP-from-forms is near-free *given* Signal Forms, so Signal Forms precede it.

Do not let a later-phase idiom ride in on an earlier-phase PR (esp. don't smuggle the Signal-Forms
reshape into the version bump).

## Emit / stop-emitting quick reference
- **Stop:** `withFetch()` arg; `typescript ~5.9` / `@angular/* ^21` pins; `@angular/platform-browser-dynamic`;
  the `@angular-devkit/build-angular` builder; (Phase C) `FormBuilder`/`Validators` Reactive Forms.
- **Keep (already v22-valid):** standalone, signals, `@if/@for/@defer`, `inject()`, functional guards,
  `provideZonelessChangeDetection()`, esbuild `@angular/build:application` builder, Tailwind v4,
  explicit `OnPush`, `Subject + debounceTime + takeUntilDestroyed` search debounce.
- **Start (per phase):** `httpResource()`/`rxResource()` (stable), Signal Forms (Phase C), opt-in WebMCP (Phase C).

## Two MCP planes — do not conflate
- **Dev-time (helps us author; we do NOT emit):** Angular CLI MCP (`ng mcp`, read-only doc/example tools)
  + Angular agent-skills. Same plane as `exeris-ai-bridge`.
- **Runtime (we emit, Phase C, flag-gated):** WebMCP inside the deployed app for in-browser agents.
  Orthogonal to ai-bridge. Never emit experimental WebMCP by default.

## Non-Negotiable Rules
- **Determinism (hard-constraint #3):** idiom swaps must keep list-ordered iteration; update the
  affected Vitest substring specs in the **same PR**. Defer to `exeris-tooling-codegen-determinism-review`.
- **Parity (strong-default #4):** `form`/`list`/`detail` reshapes and the WebMCP shape are **TS-only** —
  state this explicitly in the PR. Defer to `exeris-tooling-emitter-parity-review`.
- **No experimental-by-default:** WebMCP and any Angular *experimental* API ship behind a config flag, off.
- **Single source:** if the v22 idiom canon shifts, update the RFC
  (`docs/rfc/RFC-2026-06-18 …`, and the resulting ADR), then this skill — not the reverse.

## Output Template
1. **Files touched** (which `*-gen.ts` / scaffold)
2. **Phase** (A / B / C) and confirmation no later-phase idiom leaked in
3. **Emit delta** (stopped / started idioms)
4. **Determinism** (iteration order preserved; specs updated — yes/no)
5. **Parity** (TS-only shapes named in PR — yes/no)
6. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`) + minimal required actions
