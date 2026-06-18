---
name: exeris-tooling-angular-v22-emission
description: Angular v22 emission discipline for exeris-codegen-ts. Use whenever authoring or modifying a TS generator (*-gen.ts) or the emitted app scaffold — keeps emitted Angular idioms on the v22 canon (Signal Forms, httpResource, debounced, dropped deprecations) and gates the phased A→B→C migration.
---

# Exeris Tooling — Angular v22 Emission Discipline

## Purpose
Keep what `exeris-codegen-ts` **emits** aligned with the Angular v22 canon while the phased
migration is in flight. This skill is self-contained — the operative canon (phase gate +
emit/stop quick-reference below) lives here. The fuller rationale and verified v22 facts live in
the working RFC `RFC-2026-06-18 Angular v22 Migration of the TS Emitter` (a local draft until
accepted; once accepted it becomes the authoritative ADR). Treat this skill as the procedure that
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
- **Phase A — compat bump (urgent, ~1 PR, no shape change):** scaffold pins `@angular/* ^21→^22`,
  `typescript ~5.9→~6`, Node engine 20→22; **drop `withFetch()`** (fetch is default in v22).
- **Phase B — idiom modernisation (additive):** `debounced()` over manual RxJS `debounceTime`;
  `httpResource()` / `rxResource()` in services (today return `Observable`); optional `@Service`;
  drop now-redundant explicit `OnPush` (v22 default); audit emitted template `?.` (now `undefined`, not `null`).
- **Phase C — reshape (ADR-worthy):** `form-gen.ts` Reactive Forms → **Signal Forms** (real emitted-shape
  change → needs its own ADR + emitter-parity note); then **WebMCP** (`provideExperimentalWebMcpForms()`
  / `experimentalWebMcpTool` / `declareExperimentalWebMcpTool`) **flag-gated, OFF by default** —
  Angular *experimental* API. WebMCP-from-forms is near-free *given* Signal Forms, so Signal Forms precede it.

Do not let a later-phase idiom ride in on an earlier-phase PR (esp. don't smuggle the Signal-Forms
reshape into the version bump).

## Emit / stop-emitting quick reference
- **Stop:** `withFetch()` arg; `typescript ~5.9` / `@angular/* ^21` pins; manual `debounceTime` for search;
  (Phase C) `FormBuilder`/`Validators` Reactive Forms.
- **Keep (already v22-valid):** standalone, signals, `@if/@for/@defer`, `inject()`, functional guards,
  `provideZonelessChangeDetection()`, esbuild `:application` builder, Tailwind v4.
- **Start (per phase):** `httpResource()`/`rxResource()`, `debounced()`, Signal Forms, opt-in WebMCP.

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
- **Single source:** if the v22 idiom canon shifts, update the RFC (and the resulting ADR), then this skill —
  not the reverse.

## Output Template
1. **Files touched** (which `*-gen.ts` / scaffold)
2. **Phase** (A / B / C) and confirmation no later-phase idiom leaked in
3. **Emit delta** (stopped / started idioms)
4. **Determinism** (iteration order preserved; specs updated — yes/no)
5. **Parity** (TS-only shapes named in PR — yes/no)
6. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`) + minimal required actions
