# ADR-076 — A write against a row that is not there answers 404, not 500

- **Status:** ACCEPTED (2026-08-27)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen pipeline
- **Visibility:** public
- **Milestone:** 0.8.0 (backlog item **D7**)
- **Supersedes / superseded by:** —

## Context

An emitted application answers one question — *what happens when a write addresses a row that is not
there* — in four artefacts, and gave three different answers.

| Artefact | Its answer |
|---|---|
| `KernelRepositoryGenerator#buildDeleteById` / `#buildUpdate` | throws `RuntimeException("<Entity> not found: " + id)` — the fact exists, as a message substring |
| `KernelHandlerGenerator#appendServerErrorCatch` | one `catch (RuntimeException)` per route → **500** |
| `OpenApiPathsBuilder#buildResponses` | declares **404** on every operation, **500** on none |
| the emitted `<Entity>HandlerTest` | its service double returns quietly → asserts **204** |

None of the three is a rounding error against the others. `500` says the server broke; the row's
absence is a fact about the client's request. The spec named the one status the handler could not
give and omitted the one it did. And the emitted test asserted a status production does not produce,
which is worse than no coverage — it is a witness that testifies against the code it covers.

The same application already answered **404** for the same fact on `GET /{id}` and on every action
route, both of which read through `findById` before deciding. So a resource's by-id surface
disagreed with itself: reading a missing `Order` said it was missing, deleting it said the server
had failed.

Three further measurements shaped the fix rather than merely motivating it.

**The repository already knows.** `deleteById` and `update` both count affected rows and branch on
zero; the emitted `<Entity>RepositoryTest` asserts that branch is taken. The information was never
missing — it was destroyed at the layer boundary, because the only carrier was a message and the
only reader was a `catch (RuntimeException)` that could not read it.

**The emitted repository test said so out loud.** Its comment read: *"hasMessageContaining, not
isInstanceOf(RuntimeException) alone: an NPE from an unstaged field is also a RuntimeException, and
would make this pass without the guard running."* That is the defect stated precisely, one layer
down, by a test written before anyone noticed it generalised.

**A versioned update cannot separate its two failure modes.** The statement matches on `id` **and**
on the expected version in one round trip, so a zero row count means *gone or stale* and nothing
narrower. Splitting them needs a second query.

## 🏁 The Decision

**The repository raises a type. The handler maps it. The spec declares it.**

1. **`KernelErrorGenerator`** emits, per entity, into the generated repository package:
   - `<Entity>NotFoundException` — always;
   - `<Entity>VersionConflictException` — only when the entity is `versioned`.

   Both extend `RuntimeException`, carry the `UUID` they were raised for behind an `id()` accessor,
   and keep the message the bare exception used to carry.

2. **`deleteById` throws the not-found type; `update` throws the conflict type on a versioned entity
   and the not-found type otherwise.**

3. **The handler catches exactly one type per site, ahead of the `RuntimeException` → 500 tail:**

   | Route | Catches | Answers |
   |---|---|---|
   | `DELETE /{id}` | `<Entity>NotFoundException` | `404` |
   | `PUT /{id}`, unversioned | `<Entity>NotFoundException` | `404` |
   | `PUT /{id}`, versioned | `<Entity>VersionConflictException` | `409` |
   | `POST /{id}/actions/{name}` | same as `PUT` — it persists through `service.update` | `404` / `409` |

   One clause, not two: `deleteById` matches on `id` alone and has no stale-version mode even on a
   versioned entity, so emitting both catches everywhere would put a clause on each method that
   nothing can throw into it.

4. **The emitted OpenAPI declares `500` on every operation** — reachable from all of them, via the
   tenant guard and via the `catch (RuntimeException)` tail — **and `409` only on a versioned
   entity's write routes.**

5. **The emitted service double gains the failure mode production has.** `Stub<Entity>Service`
   carries `boolean rowExists = true` and throws from `delete` / `update` when it is false. The
   default keeps every existing case meaning what it said; a new emitted test sets it false and
   pins the `404`.

### Why the types are per entity

A single shared `EntityNotFoundException` would have to live under the project base package, and a
per-domain emitter cannot resolve that. `basePackage` is a pipeline-level input — explicit
`-Aexeris.basePackage`, else auto-detected from the first domain — while a `KernelArtifactGenerator`
sees only `metadata.packageName()`. The `.replace(".domain", ".repository")` derivation every
emitter already uses is correct under both.

The cost is real and is accepted rather than hidden: a consumer cannot catch one supertype across
entities. A shared base class is available later without breaking these types, if a consumer asks
for it.

### Why the repository package, not the domain package

The domain package is the consumer's own — it holds their `@ExerisDomain` entity — and
`<Entity>NotFoundException` is a name they may well have written there already. A generated file
landing on top of it is a compile error in their build, caused by us. The repository package is
generated-owned, and it is where the throw sites are.

### Why a versioned update answers 409 and not 404

Because `409` is true of both conditions the statement collapses — *your write did not apply,
re-read and retry* — and `404` would be a lie about one of them. An exact split is possible with a
presence probe on the failure path; it is deliberately not taken here, because it trades a second
query and a more complicated emitted repository double for a distinction that changes no client's
correct behaviour: the client re-reads either way.

## Consequences

### ✅ Positive Outcomes

- `DELETE` and `PUT` against an absent id answer `404`, which is what the emitted spec has always
  promised and what the emitted `GET` has always given.
- Optimistic locking is finally reportable. A stale-version `PUT` answered `500` before, which is
  indistinguishable from a database outage; it now answers `409`, which a client can retry on.
- The emitted repository test asserts a type rather than a substring, so the NPE its own comment
  warned about can no longer satisfy it.
- The emitted handler test stops testifying against production. Its `204` case now says the row
  was there, and a second case covers the row that was not.
- A consumer catching `<Entity>NotFoundException` in their own code gets the id without parsing a
  message.

### ⚠️ Trade-offs

- **Two more emitted types per versioned entity, one per unversioned.** Small classes, but they are
  public API of the generated app and are covered by the 0.x regeneration contract.
- **No common supertype.** See above; a consumer wanting "any not-found across entities" must catch
  each type, or catch `RuntimeException` and test with `instanceof`.
- **The action route's javadoc note still stands.** A domain exception thrown by the *entity method*
  (`entity.approve()` rejecting "already approved") still surfaces as `500`. That is a different
  question — it is about the consumer's own exceptions, which tooling cannot classify — and it is
  untouched here.
- **The catch order is load-bearing and unbuildable if wrong.** A `catch (RuntimeException)` ahead
  of the typed clause is a javac error in the consumer's build ("has already been caught"), so the
  wrong arrangement cannot ship silently; the generator tests pin it as contiguous emitted blocks
  anyway.

### 📋 What is NOT in scope

- **The exact versioned split** (probe on the failure path to separate gone from stale). Recorded as
  a follow-up, not as debt: `409` is correct today, just not maximally precise.
- **`401` and the `bearerAuth` security requirement.** The emitted spec declares a JWT bearer scheme
  on every operation and `401` on every response set, and no emitter answers `401` or enforces any
  scheme. Same family of defect — a spec promising what the code cannot do — but it is a claim about
  authentication, not about write rejection, and it gets its own finding (**D8**).
- **`404` on routes with no id** (`GET` collection, `POST` create). Also part of D8.
- **Whether an idempotent `DELETE` should answer `204` instead.** Defensible in the abstract, and
  rejected here: the repository distinguishes deliberately (its soft-delete branch excludes
  already-tombstoned rows *so that* a double delete is reportable, and an emitted test asserts it),
  and `204` would discard information the pipeline goes out of its way to keep.
- **Any ADR-070 seam entry.** An exception type is not a component — nothing constructs it through a
  `create*` factory — so `RuntimeComponents` is untouched.

## Cross-references

- **ADR-058** — the emitted-test channel. The new case is emitted and *run* by the gate, which is
  how the perturbation below was observable at all.
- **ADR-070** — the composition root; explicitly not extended here, and why.
- **ADR-075** — the publisher caller. The DELETE publish sits inside the same `try`, and its
  reachability argument now names the typed throw instead of a bare one.
- **D7** in `ROADMAP.md` — the finding, opened while checking a review challenge on T48.

### Verification

Beyond the generator tests, the fix was checked by perturbation:

- deleting the delete route's rejection catch → `shouldAnswerNotFoundForAnAbsentRow` and
  `shouldAnswerConflictForAVersionedUpdate` both fail;
- emitting a stub guard that never fires → the ADR-058 gate fails with
  `expected: HttpStatus[code=404] but was: HttpStatus[code=204]`, which is D7's sentence verbatim.

The first perturbation also found a weakness in the new assertions themselves: a
`containsSubsequence` over the whole emitted file matched clauses belonging to a *different* handler
method, so a deleted catch still passed. The assertions were rewritten as contiguous
indentation-normalised blocks before the perturbation was repeated.
