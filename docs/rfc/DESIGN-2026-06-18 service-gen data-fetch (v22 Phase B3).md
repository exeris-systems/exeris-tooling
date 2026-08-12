# DESIGN-2026-06-18: service-gen data-fetch under v22 (Phase B3)

| Field | Value |
|:--|:--|
| **Status** | DRAFT (design note, local — feeds the v22 RFC / Phase B3) |
| **Parent** | `RFC-2026-06-18 Angular v22 Migration of the TS Emitter` |
| **Scope** | `exeris-codegen-ts` emitters: `service-gen`, `store-gen`, `list-gen`, `detail-gen` |
| **Decision needed** | How should the emitted data-fetch layer consume the service under v22 — and does the service contract change? |

## Why this is a design note, not a straight PR

Phase B3 was filed as "adopt `httpResource()`/`rxResource()` in `service-gen`". On inspection the service contract is consumed **three different ways**, two of which are **already broken** — so this is a correctness decision with downstream ripple, not a cosmetic idiom swap.

## Findings (current emitted code, grounded)

Service (`service-gen`) returns RxJS Observables throughout:
`findAll(): Observable<Page<T>>`, `findById(): Observable<T>`, `create/update/delete(): Observable<…>`, custom actions `Observable<T>`.

Consumers diverge:

| Consumer | How it calls the service | Verdict |
|:--|:--|:--|
| `list-gen` | `this.service.findAll(...).subscribe({ … })` | ✅ correct Observable use |
| `store-gen` | `async loadAll(): Promise<void> { const response = await this.service.findAll(...) }` — **no `firstValueFrom`** | ❌ **bug**: `await` on an Observable returns the Observable, not the value; `response` is never the `Page<T>` |
| `detail-gen` | `resource({ loader: ({request}) => this.service.findById(request) })` — imports `resource` (Promise loader) | ❌ **mismatch**: `resource()`'s loader must return a `Promise<T>`; it's handed an `Observable<T>` |

Two compounding facts:
- **No emitted-Angular compile gate exists** (only the Java side has `KernelCodegenCompileTest`). codegen-ts tests assert emitted *substrings*, not that the generated app type-checks — so these slipped.
- v22 stabilised `resource()`, **`rxResource()`**, and `httpResource()`. `rxResource()` is the purpose-built bridge for an **Observable-returning** loader.

So B3 must (a) fix store/detail, and (b) decide whether the service stays Observable-returning.

## Options

### Option 1 — Keep service Observable; fix consumers with the right primitive *(recommended)*
- `service-gen`: **unchanged** (Observable is the composable source of truth; the service layer owns URLs/transport).
- `detail-gen`: `resource(` → **`rxResource(`** with `stream`/loader returning the Observable (its native shape). One-line idiom swap, fixes the mismatch.
- `store-gen`: wrap calls in **`firstValueFrom(...)`** (or `rxResource` for the list signal) so `await` actually resolves the value.
- `list-gen`: leave as-is (correct), or optionally move to `rxResource` later.

**Pros:** smallest blast radius; fixes both bugs; adopts a *stable* v22 primitive (`rxResource`) exactly where it fits; service contract unchanged → no ripple into types or other consumers. **Cons:** service still Observable (not "resource everywhere"), but that's correct layering.

### Option 2 — Reshape service to `httpResource()`
- `service-gen` methods become/expose `httpResource()`-based resources; consumers read `.value()`/`.isLoading()`.

**Pros:** most "v22-native". **Cons:** `httpResource()` issues HTTP *itself*, duplicating URL/transport that the service layer exists to own; biggest ripple (every consumer + types); imperative actions (create/update/delete/custom `@Action`) don't fit the resource model and would still need Observable/Promise methods → a split, inconsistent service. Highest risk for least architectural fit.

### Option 3 — Make the service Promise-based (`firstValueFrom` inside the service)
- `service-gen` returns `Promise<T>`; store's `await` becomes correct; detail keeps `resource()`; list moves from `subscribe` to `await`/`resource`.

**Pros:** store + detail "just work". **Cons:** throws away RxJS composition (cancellation, retry, debounce interop) at the service boundary; changes the service contract for *every* consumer incl. the currently-correct list-gen; a step *away* from v22's signal/resource-of-Observable direction.

### Option 4 — Pure bugfix, no resource adoption
- `firstValueFrom` in store; detail's `resource` loader wrapped to a Promise. No `rxResource`.

**Pros:** minimal. **Cons:** leaves `resource()` fed by a Promise-wrapped Observable (awkward), skips the stable v22 primitive that exactly fits.

## Recommendation

**Option 1.** Keep `service-gen` Observable-returning; switch `detail-gen` to **`rxResource()`** and fix `store-gen` with **`firstValueFrom()`** (or `rxResource` for its list signal). It fixes the two real bugs, adopts a *stable* (non-experimental) v22 primitive where it genuinely fits, and leaves the service contract — and every type — untouched, so the downstream ripple is contained to the two broken consumers.

Imperative mutations (`create`/`update`/`delete`/custom `@Action`) stay Observable methods consumed via `firstValueFrom` in the store — resources are for reads, not commands.

## Open items before the B3 PR
- **Emitted-Angular type-check gate.** These bugs slipped because nothing compiles the generated Angular app. Consider a minimal `tsc --noEmit` smoke gate over a generated sample (or at least assert `rxResource`/`firstValueFrom` presence in specs). Worth its own follow-up; without it, Phase C (Signal Forms) is equally exposed.
- **Determinism + parity:** changes are TS-only (no Java counterpart) — state in the PR. Iteration order unaffected.
- **Spec updates:** detail-gen (`rxResource` + import), store-gen (`firstValueFrom` import + call sites), in the same PR.

## Decision log
- _pending founder sign-off on Option 1._
