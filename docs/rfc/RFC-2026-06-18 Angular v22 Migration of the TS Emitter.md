# RFC-2026-06-18: How far should the TypeScript emitter adopt Angular v22, and on what migration path?

| Field             | Value                                                                                      |
|:------------------|:------------------------------------------------------------------------------------------|
| **Status**        | **DRAFT**                                                                                  |
| **Author(s)**     | arkstack-dev                                                                               |
| **Date Opened**   | 2026-06-18                                                                                 |
| **Date Closed**   | —                                                                                         |
| **Target ADR(s)** | TBD — one ADR for the Signal-Forms emission shift; emitter-parity note for WebMCP (reserve in `exeris-docs/adr-index.md` once ACCEPTED) |
| **Affected Repos**| `exeris-tooling` (`exeris-codegen-ts` only; Java emitters unaffected)                      |
| **Reviewers**     | —                                                                                         |

## Question

Angular v22 shipped on 2026-06-03. `exeris-codegen-ts` emits an Angular application whose `package.json` is pinned to `^21.0.0` / TypeScript `~5.9.3`. v22 hard-requires TypeScript 6 and Node 22, stabilises Signal Forms / `httpResource()`, and adds an experimental in-browser agent surface (WebMCP). **How far should our emitter follow v22 — a minimal compatibility bump, an idiom modernisation, or a full move to Signal Forms plus opt-in WebMCP — and in what order do we ship it without breaking determinism or the emitter-parity contract?**

## Context

The build-time pipeline's TS half (`exeris-codegen-ts`, a standalone npm package — **not** in the Maven reactor) turns `DomainMetadata` into an Angular app: services, stores, guards, sagas, event handlers, and list/form/detail components. The package itself does **not** depend on `@angular/*` — it emits Angular source as strings — so the migration surface is **what it emits**, concentrated in `app-structure-gen.ts` (the scaffold `package.json` + `app.config.ts`) and the per-shape generators.

The trigger is external and time-boxed: v22 is now the supported train. The emitted scaffold pins `@angular/* ^21.0.0`, `typescript ~5.9.3`, Node-implied 18+ — a generated app run `npm install`-then-`ng build` on a v22 toolchain will pull v22 transitively or fail TS-version resolution, and **will not run on Node 20** which v22 drops. Leaving it unanswered means our flagship "scaffold a working app" demo emits a stack that is one major behind on day one of v22 — exactly the Studio-readiness optics we are trying to fix in the 0.6.0 cycle.

The question has a small enumerable answer set because v22 changes split cleanly into three tiers: *unblock* (version pins + dropped deprecations), *modernise* (additive idiom swaps, no shape change), and *reshape* (Reactive Forms → Signal Forms, plus the net-new WebMCP shape). Each tier has a different blast radius against CLAUDE.md hard-constraint #3 (determinism) and strong-default #4 (Java/TS emitter parity).

## Investigation

### Prior art / facts gathered (web, v22.0.0, verified across three sources)

**Breaking changes that touch our emitted output:**
- **TypeScript 6 required**; 5.9 and earlier unsupported. **Node 20 dropped → 22+.** Our scaffold pins `typescript ~5.9.3` (`app-structure-gen.ts:372`) and `@angular/* ^21.0.0` (`:351-359`, devkit `:365-367`).
- **`OnPush` is the default change-detection strategy** now. We already emit explicit `ChangeDetectionStrategy.OnPush` everywhere (`list-gen.ts:131`, `form-gen.ts:141`, `detail-gen.ts:102`) → now redundant but harmless.
- **`withFetch()` deprecated** (fetch is the default transport). We emit `provideHttpClient(withFetch())` (`app-structure-gen.ts:143`).
- Template optional chaining `?.` now yields `undefined`, not `null`. Router `canMatch` gains a mandatory 3rd param; `paramsInheritanceStrategy` default flips to `'always'`. `strictTemplates` default `true`. `withIncrementalHydration()` deprecated (now default — N/A, we emit no SSR hydration). Hammer.js removed (N/A). Webpack/`@angular-devkit/build-angular` *webpack* builders deprecated — **N/A for us: we already emit the esbuild `:application` builder** (`app-structure-gen.ts:398`).

**Stabilised / new (adoption opportunities):**
- **Signal Forms stable** (was developer-preview in v21). `touched` is now an input + `touch()` output; validator `when` required in object form; `markAsTouched()` cascades to descendants. Our `form-gen.ts` emits **Reactive Forms** (`FormBuilder`/`Validators`).
- **`resource()`, `rxResource()`, `httpResource()` all stable.** We use only `resource()` (`detail-gen.ts:153`); services return `Observable<T>` (`service-gen.ts`).
- **`@Service`** decorator (sugar over `@Injectable({providedIn:'root'})`), **`debounced()`** signal (replaces manual RxJS `debounceTime`, used in `list-gen.ts`), **`injectAsync`** (prefetch lazy DI), `@angular/aria` GA.

**AI / agentic (`angular.dev/ai`) — two distinct MCP planes.** These must not be conflated:

- **Dev-time plane (helps us *author* the emitter; we do NOT emit it).** The **Angular CLI MCP server** (`ng mcp`, experimental) exposes read-only reference tools — `get_best_practices`, `search_documentation`, `find_examples`, `onpush_zoneless_migration` — plus workspace-bound experimental tools (`build`/`devserver`/`test`/`modernize`, N/A: we have no app, we emit strings). **Angular agent-skills** (`angular-developer`, `angular-new-app`; `npx skills add github.com/angular/skills`, Gemini-CLI-targeted) are versioned idiom packs. Both are candidates for our Claude-Code dev loop (a `--read-only --local-only` MCP reference oracle / mining the skills into our own `.claude/skills/`) to keep emitted idioms canonical — they sit in the same layer as **`exeris-ai-bridge`** (the ecosystem's dev-time MCP), and are NOT part of the emitted artefact.
- **Runtime plane (we *emit* it — Phase C, flag-gated).** **Experimental WebMCP** ships *inside the deployed app* so an in-browser LLM agent can call app functions directly instead of driving the DOM: `provideExperimentalWebMcpTools([...])` (app-wide), `declareExperimentalWebMcpTool({...})` (in an injection context), and — critically — **`provideExperimentalWebMcpForms()` + the `experimentalWebMcpTool` form option, which auto-derive a tool from a Signal Form** (JSON schema inferred from initial values + validators). The spec is early and Angular's API may move *outside* major versions → **opt-in only, never emitted by default.** This is orthogonal to `exeris-ai-bridge`: WebMCP exposes the *generated app at runtime to a browser agent*; ai-bridge exposes the *ecosystem at dev-time to a coding agent*.

**Design-patterns (`/ai/design-patterns`)** are hand-authoring guidance for apps that integrate an LLM (signal-triggered `resource`, `linkedSignal` accumulation, streaming, loading/error, scoped resources). They only become emit-relevant if `DomainMetadata` ever carries AI-feature semantics (none today); for now they merely reinforce the Phase-B resource/signal idioms.

### Constraints (CLAUDE.md)

- **#3 Determinism** — same `DomainMetadata` → byte-identical output. Every idiom swap must keep deterministic ordering; ~300+ substring specs (Vitest, no snapshots) ripple on any idiom change but will catch regressions.
- **#4 Metadata is the only contract** — no change here; this is pure consumption.
- **Strong-default #4 Emitter parity** — Java/TS shapes must stay aligned. The reshaped shapes (`form`/`list`/`detail`) and the new WebMCP shape are **TS-only** (no Java counterpart); the PR must state this explicitly per the "name it in the PR" rule.
- **No Maven wrapper for TS** — unchanged; this stays an npm-only change set.

### Code archaeology (current emitted profile)

Already modern (v21-grade, valid on v22): standalone components, signals everywhere, `@if/@for/@defer`, `inject()`, functional guards, `provideZonelessChangeDetection()` (`app-structure-gen.ts:141`), Tailwind v4, esbuild `:application` builder. **Stale vs v22:** version pins, Reactive Forms (`form-gen.ts`), `withFetch()`, manual `debounceTime` (`list-gen.ts`), `Observable`-returning services (`service-gen.ts`). The emitter package itself is v22-agnostic (Node ≥18, Vitest 4, TS 5.9 dev-only) — no `package.json` change needed in the package, only in emitted strings.

## Options Considered

### Option A: Compat bump (unblock v22)
Bump emitted scaffold pins `@angular/* ^21→^22`, `typescript ~5.9→~6`, Node engine 20→22; drop the deprecated `withFetch()` argument. No shape changes.

**Pros:** Generated apps build & run on the v22 toolchain immediately; ~1 PR; near-zero determinism risk (string-literal edits + a handful of spec updates); no parity story needed.
**Cons:** Leaves us emitting v21-era idioms (Reactive Forms, manual debounce, Observable services) on a v22 stack — functionally fine, optically behind.
**Cost:** ~1 PR.

### Option B: Idiom modernisation (A + additive swaps)
A, plus: `debounced()` over RxJS in `list-gen`; optional `@Service`; `httpResource()`/`rxResource()` in `service-gen`; drop now-redundant explicit `OnPush`; audit emitted `?.` for the `null→undefined` semantics flip.

**Pros:** Emitted code reads as native v22; no emitted-artefact *shape* change (forms stay Reactive).
**Cons:** Several PRs; meaningful spec ripple; `httpResource` changes the service consumption ergonomics for downstream hand-written code.
**Cost:** 2–4 PRs.

### Option C: Signal Forms + WebMCP (A + B + reshape) — *selected scope*
B, plus: rewrite `form-gen.ts` from Reactive Forms to **stable Signal Forms** (a genuine change to the emitted artefact's shape), and add **opt-in WebMCP emission** (`provideExperimentalWebMcpForms()` / `declareExperimentalWebMcpTool()`) behind a config flag, off by default.

**Pros:** Fully on the v22 signal-first direction; positions generated apps for the agentic-frontend story (`angular.dev/ai`) that aligns with the broader Exeris AI-bridge thesis.
**Cons:** Largest blast radius — `form-gen` reshape rewrites the most idiom-pinned generator and its ~40 specs; WebMCP rides an **experimental** Angular API (churn risk) so must be flag-gated; needs an explicit emitter-parity note (TS-only shapes) and an ADR for the forms shift.
**Cost:** A as 1 PR, B as 2–3 PRs, C-reshape as a dedicated PR + ADR, WebMCP as a flagged PR. Phased.

### Option D (do nothing)
Keep emitting `^21`. **Rejected:** v22 drops Node 20 and requires TS 6 — generated apps are actively broken against a current toolchain, not merely dated.

## Recommendation

**Adopt Option C, delivered strictly phased A → B → C, so v22 is unblocked in the first PR and the high-risk reshape lands last behind its own ADR.**

The phasing is the point. **Phase A** (compat bump) is the only urgent item — it is the difference between "generated app builds on v22" and "doesn't" — and it carries almost no risk, so it ships first and standalone. **Phase B** (idiom swaps) is additive and reversible; each swap is its own small PR gated by the determinism check and the existing substring specs. **Phase C** splits in two: the Signal-Forms rewrite is a real emitted-shape change and therefore gets an ADR (reserve a number) plus an explicit "TS-only shape, no Java parity counterpart" note; WebMCP lands last, flag-gated and off by default, because it depends on an Angular *experimental* API whose surface can still move.

This sequencing means we never block the urgent (v22 compatibility) on the speculative (WebMCP), and the one change that alters committed `src/main/generated/` shape for existing users (Signal Forms) is isolated, documented, and migration-noted rather than smuggled in with a version bump. The order also pays off technically: **WebMCP-from-forms is near-free *given* Signal Forms** — `provideExperimentalWebMcpForms()` auto-derives the agent tool from the Signal Form's inferred schema, so once Phase C emits Signal Forms, the flag-gated WebMCP step is one provider + one `experimentalWebMcpTool` option, not a separate tool-authoring shape. Reactive Forms have no such bridge, which is a further reason the reshape precedes WebMCP rather than running independently.

### New emitter guidelines (apply from Phase A onward)
- **Target v22 idioms**; stop emitting deprecated options (`withFetch()` first).
- **Prefer Signal Forms** for new/rewritten form emission; **`httpResource()`/`rxResource()`** for data services; **`debounced()`** over manual `debounceTime`.
- **WebMCP and any Angular *experimental* API are opt-in only**, behind a config flag, never default.
- **Determinism is non-negotiable** — keep list-ordered iteration; update substring specs in the same PR as the idiom change.
- **Parity discipline** — `form`/`list`/`detail` reshapes and the WebMCP shape are TS-only; state this in every PR per strong-default #4.

### Why not the alternatives?
- **Option A / B alone** — fine as *stops on the path*, but stopping there leaves us off the v22 signal-first + agentic direction the founder selected (T-C); they are folded in as Phases 1–2.
- **Option D** — v22's Node-20 drop and TS-6 requirement make "do nothing" an actively-broken scaffold, not a neutral hold.

### Risks of the recommendation
- **Signal Forms churn** — the API hardened from v21 preview but `touched`/validator semantics shifted even at GA; the rewrite may need a follow-up as patterns settle.
- **WebMCP is experimental** — flag-gating contains the blast radius, but expect to track API drift; do not let any default-on path depend on it.
- **Spec ripple** — ~300+ substring specs; the `form-gen` reshape touches the densest cluster (~40). Mitigated by phasing and the determinism gate.
- **`?.` semantics flip** (`null→undefined`) could subtly change emitted template behaviour where we chain on optionals — audit during Phase B.

## Decision Record

<Filled in when status reaches ACCEPTED / REJECTED / WITHDRAWN.>

| Field                | Value                                                              |
|:---------------------|:------------------------------------------------------------------|
| **Outcome**          | —                                                                 |
| **Date**             | —                                                                 |
| **Resulting ADR(s)** | TBD (Signal-Forms emission shape; reserve on ACCEPT)              |
| **Notes**            | Founder selected T-C scope on 2026-06-18; phasing A→B→C per §Recommendation |

## Open questions / follow-ups

### Implementation decisions (during delivery)
- **Phase A — DONE (PR #95):** pins `^22` + TS `~6.0.0` (verified `@angular/compiler-cli@22` peerDep `typescript >=6.0 <6.1`); dropped `withFetch()`; refreshed version strings. `engines.node` was already `>=24` (not 20) so left for B1.
- **Phase B1 scaffold cleanup — DONE (PR #96):** dropped `@angular/platform-browser-dynamic`; `engines.node`/`@types/node` → Node-22 floor; builder → `@angular/build`.
- **`debounced()` — REJECTED for default emission.** Verified against `angular.dev/api/core/debounced`: it is an **experimental** v22 API (`@angular/core`) returning a `Resource<T>` (`.value()`), not a debounced signal. Per the no-experimental-by-default rule it must not be emitted by default, and it is not a drop-in for the current debounce. The existing `Subject + debounceTime + takeUntilDestroyed` in `list-gen` is stable, idiomatic v22 — kept. (Reverses the Option B "debounced over RxJS" line.)
- **Dropping explicit `OnPush` — REJECTED.** v22's OnPush-default is CLI-scaffold-level on new apps, not a framework change for components lacking an explicit strategy; keep explicit `OnPush` to avoid a behaviour dependency.

### Remaining Phase B candidates
- **`httpResource()` / `rxResource()` in `service-gen`** — **stable** in v22, but reshapes the consumed service contract (services return `Observable<T>` today; stores/components consume that). Needs a design note before implementation: replace vs add-alongside, and the downstream ripple into store/list/detail. (owner: codegen-ts)
- **`@Service` vs `@Injectable`** — cosmetic; adopt only if it reduces emitted boilerplate measurably. (owner: codegen-ts)
- **`?.` semantics audit** — emitted templates: v22 returns `undefined` not `null` from optional chaining; confirm no emitted expression depends on the old `null`. (owner: codegen-ts)

### Other
- **`@Service` vs `@Injectable`** — cosmetic; adopt in Phase B only if it reduces emitted boilerplate measurably. (owner: codegen-ts)
- **`injectAsync` prefetch** for lazy-loaded routes/services — evaluate in Phase B; not required for parity. (owner: codegen-ts)
- **WebMCP config-flag shape** — where does the opt-in live (codegen config schema, Zod-validated) and what entity surface does it expose (CRUD actions? Signal Forms?)? Becomes its own design note before the WebMCP PR. (owner: codegen-ts)
- **Emitted-app test stack** — v22 ships `migrate-karma-to-vitest` and Vitest Zone.js support; should the scaffold emit a Vitest test setup? Out of scope here. (owner: codegen-ts)
- **Dev-loop authoring aids** — wire `ng mcp --read-only --local-only` as a reference oracle and/or mine `github.com/angular/skills` (`angular-developer`) into our `.claude/skills/` so the agent writing the emitters has canonical v22 idioms on hand. Tooling-internal (not an emitted artefact); same plane as `exeris-ai-bridge`. (owner: tooling DX)
- **WebMCP vs ai-bridge boundary** — confirm with the ai-bridge track that runtime WebMCP (emitted, in-browser) and dev-time ai-bridge (ecosystem introspection) stay separate planes; no shared registry. (owner: codegen-ts + ai-bridge)

## Sources

- [Angular v22 Release — angular.dev/events/v22](https://angular.dev/events/v22)
- [What's new in Angular 22.0 — Ninja Squad](https://blog.ninja-squad.com/2026/06/03/what-is-new-angular-22.0)
- [Release 22.0.0 — github.com/angular/angular](https://github.com/angular/angular/releases/tag/v22.0.0)
- [Build with AI — angular.dev/ai](https://angular.dev/ai)
