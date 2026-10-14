# ADR-076 — A write against a row that is not there answers 404, not 500

- **Status:** ACCEPTED (2026-08-27) · amended 2026-10-14 (Amendment 1 — a write naming a missing row
  through a relationship answers 422, and a delete of a still-referenced row answers 409)
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

## Amendment 1 — a foreign-key violation answers 422 or 409 (2026-10-14)

**Status:** Accepted *(adds two refusals to the Decision's pattern — a type the repository raises,
a status the handler answers, a status the spec declares; §1 to §5 are otherwise unchanged)*

**Deciders:** the founder (ruling 2026-10-14, `exeris-tooling#398`).

A foreign-key violation fell into the handler's `RuntimeException` tail and answered `500`: a create
or update whose `MANY_TO_ONE` field names a row that does not exist, a dangling self reference, and a
delete of a row other rows still reference. The client caused each of them.

### The decision of the amendment

1. **The repository recognises a foreign-key violation.** The kernel's persistence SPI reports a
   refused statement as `PersistenceProviderException` with code `EX-PERS-5003` and raw arguments
   `[sqlState, detail]` (`PersistenceErrorTranslator`, kernel core). A foreign-key violation is
   SQLState `23503` on PostgreSQL in both directions; H2 reports `23503` for a referenced row being
   deleted and `23506` for a written row naming a missing one (measured on the booted H2 test: with
   `23506` removed from the set, the three `422` cases answer `500`). The emitted
   `foreignKeyViolation` walks the cause chain, bounded, for that exception.
2. **Two types**, emitted by `KernelErrorGenerator` into the repository package beside the others,
   for the reasons §1 gives:
   - `<Entity>ReferenceNotFoundException(String relation, Throwable cause)` with `relation()`,
     raised by `save`, `update` and `updateFromRequest`;
   - `<Entity>StillReferencedException(UUID id, Throwable cause)` with `id()`, raised by
     `deleteById`.
3. **Which relationship refused the write is read from the constraint name.** The foreign-key
   migration names each constraint `fk_<table>_<column>`, and both engines put that name in their
   message (PostgreSQL in lower case, H2 in upper case). The repository tests the names it knows,
   longest first, so a name that is a prefix of another cannot claim its violation. A violation of a
   constraint the build did not declare is not attributed and propagates unchanged — still `500`.
   A delete violates a foreign key only as the referenced side, so any foreign-key violation of a
   delete is the still-referenced refusal.
4. **The handler maps them**, ahead of the `500` tail, without a log:

   | Route | Catches | Answers |
   |---|---|---|
   | `POST`, `PUT /{id}`, `POST /{id}/actions/{name}` | `<Entity>ReferenceNotFoundException` | `422`, problem body |
   | `DELETE /{id}` | `<Entity>StillReferencedException` | `409` |
   | streaming action | `<Entity>ReferenceNotFoundException` | `stream-error` frame, status `422` |

   The `422` body is an RFC 9457 problem object — `type` `about:blank`, `title`
   `Unprocessable Content`, `status` `422`, `detail` — whose `field` member names the relationship.
   It is encoded by the exchange's typed-response encoder, so its media type is the encoder's
   (`application/json`); the encoder appends its own `Content-Type`, and a second one from the handler
   would be a duplicate header. The kernel names no `422` constant, so the handler holds
   `new HttpStatus(422, "Unprocessable Content")`. The stream frame carries status and title only, as
   every other `stream-error` frame does.
5. **Why 422 and 409.** `422`: the body is well formed and decodes, and the row it points at is the
   caller's to correct; `400` is the answer for a body the handler cannot use at all, and `404` would
   say the addressed resource is missing. `409`: the row exists and the delete can succeed once the
   rows that reference it are removed or repointed — a conflict with the current state, not a missing
   row. A `409` on `DELETE` is never a version conflict: §3's versioned clause is on the update path
   only, and `deleteById` matches on `id` alone.
6. **Emitted only where a foreign key of the build can produce it** (`ForeignKeys`, the same source
   the foreign-key migration now reads):
   - the reference refusal for an entity with a `MANY_TO_ONE` relationship whose target is an entity
     of the build — a target outside the build gets no constraint;
   - the still-referenced refusal for an entity some entity of the build references — itself
     included — and whose delete removes the row; a soft delete keeps the row, so no constraint sees
     it.
7. **The spec declares them** on the same condition: `422` on the create, the update and every
   action of a referencing entity, and `409` on the delete of a referenced one.
8. **The generated tests** (ADR-058) cover both: the repository test stages the SPI exception in
   PostgreSQL's shape and, wrapped once, in H2's, and a unique violation that stays as it is; the
   handler test pins `422` with the problem body on both writes, past every guard, and `409` on the
   delete. `RecordingPersistence` gains `writeFailure`, which its next write throws.

### How the generators see the build

A per-entity generator saw one `DomainMetadata`, and whether an entity is referenced is a fact about
the others. `KernelArtifactGenerator` gains
`generateMultiple(DomainMetadata metadata, List<DomainMetadata> build)`, defaulting to
`generateMultiple(metadata)`; `CodegenPipeline` calls it with every entity of the run, and the
repository, error, handler, action-stream and OpenAPI generators override it. Called without a
build, they take the entity as a build of its own, where only a reference to itself is a foreign key.
`OpenApiGenerator#generateAggregated` passes the entities it describes; the generated-test
generators take the build the same way. No metadata field is added: the contract stays
`DomainMetadata`, read whole.

The new `KernelArtifactGenerator` method in `exeris-codegen-core` widens the pipeline contract
between `CodegenPipeline` and its generators: a generator may read every entity of the run, not only
its own. The widening is recorded here; ADR-015, which owns the emission strategy, is not amended.
`GeneratorRegistry#generateAll(DomainMetadata)` and `KernelGeneratorStrategy#generate(DomainMetadata)`
route through the build-wide forms with a build of one, so they emit nothing that only another
entity's reference produces. `docs/generators.md` names the generators that read the whole build;
the catalogue JSON gains no field, because ADR-097 fixes its row keys and their order.

### Consequences of the amendment

- **Breaking for a client that branched on the `500`.** The regeneration note is
  `docs/migration/0.10.0/java-15-foreign-key-violation-answers-422-or-409.md`.
- **A `409` now has two meanings, one per route.** On `PUT` and an action it is §3's version
  conflict; on `DELETE` it is a still-referenced row. The TypeScript `httpErrorMessage` tells them
  apart by the action it is given (`docs/migration/0.10.0/ts-14-referenced-delete-and-missing-reference-messages.md`).
- **A foreign key the consumer declared outside the generated migration** is not attributed on a
  write and still answers `500` there; on a delete it answers `409`, which is true of it.
- **The engines' messages are read.** The constraint name is the only place either engine says which
  constraint failed, so the attribution depends on the driver keeping it in its message. Both
  measured drivers do; a driver that dropped it would turn the `422` back into a `500`, never into a
  wrong field.

### Verification of the amendment

- `ForeignKeysTest`, `ForeignKeyRefusalGeneratorsTest`, `OpenApiForeignKeyStatusesTest` — which
  entities get which type, catch and status; the constraint order; the build of one.
- `ForeignKeyGeneratedTestsE2ETest` — the generated cases compile against the ADR-058 classpath
  and pass, run by name.
- `ForeignKeyRefusalBootE2ETest` boots the emitted application on H2: a create, an update and an
  action naming a missing row answer `422` with `"field"` naming the relationship, a dangling self
  reference names the self relationship, and a delete of a row another entity's rows or its own rows
  reference answers `409`, then `204` once they are gone.
- `KernelCodegenCompileTest` compiles the renamed-key corpus, whose `Payment` references `Invoice`,
  with every lint category.
