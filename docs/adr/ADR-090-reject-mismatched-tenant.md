---
title: "ADR-090 — A write that names another tenant is refused with 400, not left to row-level security"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-090
---

# ADR-090 — A write that names another tenant is refused with 400, not left to row-level security

- **Status:** ACCEPTED (2026-09-26) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058);
  the decision is implemented on `main` (`exeris-tooling` #223) · amended 2026-10-08 (Amendment 1 —
  an update keeps every server-owned column, not only the owner) · amended 2026-10-08 (Amendment 2 —
  a request body does not write read-only fields, and the update schema is the body the update
  writes from) · amended 2026-10-08 (Amendment 3 — the create validates the body it carries, and
  `inCreate` / `inUpdate` shape the bodies like `readOnly`)
- **Deciders:** the founder (finding **T36**)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen pipeline — emitted repository, handler, error types, OpenAPI, TypeScript
  types
- **Visibility:** public
- **Milestone:** 0.9.0 (finding **T36**; applies to the shared-scope field of **T29 slice B**)
- **Supersedes:** the 0.8.0 T36 rationale recorded in `ROADMAP.md` ("a tenant the caller **did** set
  is left alone … re-deciding it in emitted Java would be a second implementation of a rule the
  database already enforces"). That rationale was never an ADR.

## Context

The generated repository stamps a tenant-partitioned row's owner from the bound `StorageContext` when
the caller leaves it unset (T36, 0.8.0). An owner the caller *did* set is a separate question: which
tenant a request may write as. Leaving that question to the migration's row-level-security
`WITH CHECK` predicate alone does not hold, for four reasons.

1. **RLS is not always there.** A role with `SUPERUSER` or `BYPASSRLS` skips every policy, even on a
   `FORCE`d table. An engine without row-level security has no policy at all. The emitted migration
   cannot tell which of these the runtime environment uses. Without a policy in force, a `POST` or
   `PUT` body naming any tenant is written as that tenant.
2. **Where RLS is there, it answers the wrong status.** A `WITH CHECK` violation surfaces from the
   driver as a persistence exception and reaches the handler's `catch (RuntimeException)`, which
   answers **500**. The request is the caller's mistake: repeating it unchanged fails the same way and
   no operator action makes it succeed — kernel ADR-083's `FaultOrigin.CALLER`, the class ADR-036 §2
   maps to 4xx. A 500 reports a malformed request as a server fault and tells the caller nothing.
3. **An update that writes the owner can move a row.** An `UPDATE … SET` list that carries the owner
   column lets a `PUT` body with a different tenant re-own the row. Under RLS the new row fails
   `WITH CHECK` (the 500 above); without RLS it succeeds.
4. **On a UNIVERSE table the owner in the SET list is a takeover.** T29 slice B widens reads across a
   shared scope. Under the kernel's reference single `FOR ALL` policy shape, measured on PostgreSQL 16
   as a non-owner `NOSUPERUSER NOBYPASSRLS` role, `UPDATE t SET owner = <self> WHERE id = <partition-
   mate's row>` affects **1 row** (`ROADMAP.md`, T29 slice B): a tenant re-owns a row it can only read.
   The additive `FOR SELECT` policy tooling emits closes this in the database; this ADR closes it in
   the repository as well, for the engines and roles where the database does not.

The tooling already accepts double enforcement where a database rule has a caller-facing twin: the
T10 `CHECK` constraints mirror the handler's `@Validation` 400s.

## 🏁 The Decision

**With a tenant bound, the generated repository refuses a row naming another tenant, with a typed
caller fault the generated handler answers 400. With no tenant bound, nothing changes. The owner is
never updated.**

1. **Refuse, only while a tenant is bound.** `save` and `update` of a tenant-partitioned entity (TENANT
   and UNIVERSE) run, right after the T36 stamp, `refuseForeignTenant(entity.get<Owner>())`
   (`KernelRepositoryGenerator#appendTenantStamp`, `#buildRefuseForeignTenant`):
   - "bound" means `KernelProviders.STORAGE_CONTEXT.isBound()` **and** an isolation key on it; an
     unbound slot and the system scope both mean "nothing to compare against", and the row is left to
     the database — a seeder that writes owners explicitly keeps working;
   - the slot is tested before the throwing `storageContext()` accessor, so "none bound" never becomes
     a failure;
   - a different owner throws `<Entity>TenantMismatchException(UUID tenantId)`;
   - a non-UUID isolation key is the same `IllegalStateException` deployment fault the stamp reports
     (never an `IllegalArgumentException`, which ADR-036 §2 reads as the caller's).
2. **The owner is not in the `UPDATE … SET` list** (`KernelRepositoryGenerator#updateColumns`). Of the
   two ways to keep an update from moving a row — drop the column, or refuse a change — dropping it is
   the one that holds everywhere:
   - it makes a move *impossible* rather than detected, on every engine and role, bound or not;
   - detecting a change needs the stored owner, which the repository only learns through a
     predicate (`AND owner = ?`) that turns a move attempt into zero rows — a 404/409 that reports a
     caller fault as a missing row;
   - with a tenant bound, the refusal in (1) already rejects a foreign owner before the statement;
   - on a UNIVERSE table it removes the takeover in Context §4 independently of the policy shape.

   The `INSERT` still writes the owner. `update` still stamps an absent owner, so the returned entity
   names it. An entity with nothing to write — no domain field, no audit or version column — emits
   `UPDATE … SET id = id WHERE id = ?`, which binds nothing in its SET list and keeps the zero-row
   answer (404); a field-less `GLOBAL` entity gets the same statement.
3. **The same rule for the caller-writable shared-scope field** (T29 slice B). A UNIVERSE entity's
   `@SharedScope` field is decoded from the request body like any field, so it gets the same
   treatment (`KernelRepositoryGenerator#appendSharedScopeStamp`, `#buildRefuseForeignSharedScope`):
   with a scope bound (a non-blank `sharedScopeKey` on the context `storageContextOrSystem()`
   returns), a row tagged with another scope is refused with
   `<Entity>SharedScopeMismatchException(String sharedScope)` — a `UUID` scope column passes its
   `toString()`; with none bound the caller's tag is kept. Unlike the owner it **stays in the SET
   list**: an owner moving its own row between scopes, or out of one, is a legitimate write, and the
   owner-pinned policy still decides whose row it is.
4. **The types** are emitted by `KernelErrorGenerator` next to the ADR-076 ones, in the repository
   package, per entity (ADR-076's reasoning for both choices applies unchanged): plain
   `RuntimeException` subclasses carrying the rejected value (`tenantId()`, `sharedScope()`), not the
   row id. `<Entity>TenantMismatchException` exists for every tenant-partitioned entity;
   `<Entity>SharedScopeMismatchException` only for a UNIVERSE entity with a `@SharedScope` field.
5. **The handler maps them to 400** (`KernelHandlerGenerator#appendCallerFaultCatch`), ahead of the
   `RuntimeException` → 500 tail, on the routes that hand the repository an entity to write —
   `POST`, `PUT /{id}`, and every respond-once action route, which persists through
   `service.update`. A `streaming = true` action has no such route: its stream route does not run the
   action and writes nothing. A UNIVERSE entity with a `@SharedScope` field catches both types in one
   multi-catch. No log, like the 404 beside it.
6. **The published contract says so.** The emitted OpenAPI marks the owner — and a UNIVERSE entity's
   shared-scope field — `readOnly: true` on the entity schema and leaves both out of the
   `…CreateDto` / `…UpdateDto` schemas (`OpenApiComponentsBuilder#serverOwnedFields`). The TypeScript
   emitter omits from its `…Create` type and create schema every owner and shared-scope field a
   `systemFields` block names (`systemFieldNames`), and the generated views render no control for
   them or for `tenantId` (`viewSystemFieldNames`). One exception, for one release: a
   tenant-partitioned entity with **no** `systemFields` block keeps `tenantId` in its `…Create` and
   `…Update` types and its create schema, marked `@deprecated` (`deprecatedDtoOwner`,
   `DEPRECATED_OWNER_DOC`); exeris-tooling 0.10.0 moves it into `systemFieldNames`
   (`ROADMAP.md`, "Removals due in 0.10.0"). Sending the bound tenant is harmless; sending another is
   a 400.

### Exception → status chain

| Where | What happens | Answer |
|---|---|---|
| `<Entity>Repository.save/update` → `refuseForeignTenant` | bound isolation key ≠ `entity.<owner>` | throws `<Entity>TenantMismatchException` |
| `<Entity>Repository.save/update` → `refuseForeignSharedScope` (UNIVERSE with `@SharedScope`) | bound shared scope ≠ `entity.<scope>` | throws `<Entity>SharedScopeMismatchException` |
| `<Entity>Service.save/update` | delegates; does not catch | propagates |
| `<Entity>Handler.handleCreate / handleUpdate / handle<Action>` | `catch (<Entity>TenantMismatchException [\| <Entity>SharedScopeMismatchException] e)` | **400 Bad Request**, no body, no log |
| same, no tenant bound | no refusal; the row reaches the database; RLS decides | 201/200, or 500 on an RLS violation (unchanged) |
| same, non-UUID isolation or shared-scope key | `IllegalStateException` | 500 (deployment fault, unchanged) |

### Why 400 — and not 403, 409 or 500

- **Not 500:** the caller is at fault (kernel ADR-083 `CALLER`; ADR-036 §2's 4xx arm).
- **Not 403:** the emitted application binds no authorization model — ADR-079 withdrew the spec's
  authentication claim for the same reason. A 403 would assert a permission system that does not
  exist.
- **Not 409:** ADR-076 gave 409 one meaning, a version conflict; a client retries on it.
- **400:** the request body is wrong in a way the caller can fix, and is wrong the same way every time.

### Why the types do not extend `ExerisKernelException`

`FaultOrigin.classify` answers an `ExerisKernelException`'s own `faultOrigin()` and `SYSTEM` for every
other throwable. Extending the kernel base would let these types classify themselves for a generic
adapter, but that base class is the kernel's own: it requires an `EX-<DOMAIN>-<ID>` code from
kernel-owned domains and allocates a trace UUID and a timestamp per construction. The generated
handler catches them by type, as it does the ADR-076 types, and the exception never leaves it. If a
consumer adapter ever needs classification, a tooling-owned marker is the change to make, not
borrowing the kernel's base.

This is consistent with the handler's body-decode path, which consults `FaultOrigin.classify` and
answers 400 only for `CALLER` (ADR-036 §2, kernel ADR-083): the mismatch types are raised after the
body is decoded, inside the write's `try`, and never pass through that classification. Were they
routed through it, they would classify `SYSTEM` and answer 500 — so the by-type catch is required,
not a shortcut.

### The required database role model

The refusal covers a **bound** request. Everything else still rests on row-level security, so
generated code must connect as a role that:

- is `NOSUPERUSER` **and** `NOBYPASSRLS` — either attribute makes every policy, forced or not, inert;
- does **not own** the tables, or the tables are `FORCE ROW LEVEL SECURITY` (the emitted migrations
  force them; a hand-written table must too);
- is granted `SELECT, INSERT, UPDATE, DELETE` on the tables and nothing that implies the above.

A role holding either bypass attribute turns every isolation test into a vacuous pass. Checking the
role at boot belongs to the kernel or the persistence driver, which own the connection — not to
generated code — and is recorded as a kernel ask in `ROADMAP.md`, not built here.

## Consequences

### ✅ Positive Outcomes

- A body naming a foreign tenant is refused on every engine and role while a tenant is bound, and is
  reported as the caller fault it is.
- No update can move a row between tenants, bound or not, RLS or not.
- On a UNIVERSE table the repository adds a second barrier against re-owning a partition-mate's row.
- The published OpenAPI contract stops inviting clients to send a field the server owns.
- The emitted handler test of a tenant-partitioned entity dispatches with a tenant bound, so its
  cases reach the write instead of stopping at the T41 tenant guard, and `GeneratedTestsE2ETest`
  executes it for a TENANT and a UNIVERSE entity.

### ⚠️ Trade-offs

- **This is a second implementation of part of RLS — on purpose, and narrowly.** It covers a bound
  request's write, and nothing else: reads and deletes are still partitioned only by the policy. The
  two cannot drift in the dangerous direction — the refusal is strictly narrower than the policy's
  `WITH CHECK`, so it can only turn a would-be 500 into a 400, never admit what the policy refuses.
- **A partition-mate acting on a shared row it can read** (an action route loads it, then persists)
  gets 400, not the 404/409 the database's zero-row update would produce. The caller is at fault
  either way; 400 says so consistently.
- **Scope pinning on write is stricter than the kernel's reference policy** while a scope is bound: an
  owner in a session bound to scope V cannot save its own row tagged W. The kernel has not ruled on
  the write-side scope pin of kernel ADR-012 §4b (the shared-scope tenancy model; tooling ask k1);
  this ADR chooses the conservative reading for bound requests and leaves unbound ones unchanged.
- **One more emitted type per tenant-partitioned entity**, two per UNIVERSE entity with a
  `@SharedScope` field — public API of the generated app, covered by the 0.x regeneration contract.
- **The TypeScript create/update types of a tenant-partitioned entity with no `systemFields` block
  still carry `tenantId` until 0.10.0**, deprecated, so for one release the generated client and the
  published OpenAPI disagree on that one field.
- **`@TenantId(validateOnMutation = false)` does not turn the refusal off.** The attribute is inert,
  and the processor's strict mode reports it so; the refusal is unconditional for a bound request.

### 📋 What is NOT in scope

- Reads and deletes on a non-RLS engine or a bypassing role — still unpartitioned there. Filtering them
  in Java would be the full second implementation of RLS.
- A boot-time role check (above).
- 401 and authentication — ADR-079.
- `readOnly` for the audit/version columns — the entity schema does not mark them; a separate change
  if the contract should say so.

### 🚫 Non-Goals

- **Replacing row-level security.** The refusal is a pre-check at the edge of a bound request; RLS
  stays the enforcement for every request the check does not see.
- **Authorising the caller.** Whether the caller may act for the tenant it is bound to is
  authorisation (T53), not tenancy; this ADR compares the row against the binding only.

### ⚠️ Risks and Assumptions

- **Assumes:** RLS is the backstop for an unbound request and for the system scope. Both carry
  nothing to compare against, so the repository leaves the row to the policy.
- **Assumes:** the kernel binds `STORAGE_CONTEXT` with the request's isolation key whenever a tenant is
  known. A request that reaches the repository with the slot unbound is not refused here.
- **Assumes:** the database role is `NOSUPERUSER NOBYPASSRLS` (above). With a bypassing role, the
  refusal is the only partition check on writes, and reads are unpartitioned.
- **Reversed by:** a kernel ruling on the write-side scope pin (kernel ADR-012 §4b, ask k1) that differs
  from the conservative reading this ADR takes for bound requests, or a kernel-side refusal of a
  mismatched write that makes the generated pre-check redundant.

## Cross-references

- **ADR-076** — the typed-rejection pattern and the per-entity / repository-package placement reused
  here.
- **ADR-036 §2** (link stub) and kernel **ADR-083** (`FaultOrigin`, link stub) — the caller-vs-system
  split the 400 follows, and the classification the handler's body-decode path applies.
- **ADR-079** — why not 403.
- **ADR-058** — the generated-test channel whose emitted tests prove the chain.
- **ADR-059** (link stub) — the `DataScope { GLOBAL, TENANT, UNIVERSE }` tiers; "tenant-partitioned"
  here means any tier other than `GLOBAL`.
- Kernel **ADR-012** §4b — the kernel's tenancy model: the isolation key and shared-scope key on
  `StorageContext`, and the reference RLS policy shape.
- **T36**, **T29 slice B**, **T41**, **T10** in `ROADMAP.md`; the migration note is
  `docs/MIGRATION-0.x-to-1.0.md`, "A write naming another tenant now answers `400`, and an update no
  longer writes the owner (T36)".

### Verification

- Generated repository test (`KernelRepositoryTestGenerator`), executed by `GeneratedTestsE2ETest` for
  a TENANT and a UNIVERSE entity: `saveAcceptsATenantThatIsTheBoundOne`,
  `saveRefusesATenantThatIsNotTheBoundOne`, `saveLeavesACallerTenantToTheDatabaseWhenNoneIsBound`,
  `updateNeverWritesTheTenantSoARowCannotMove`; for UNIVERSE additionally
  `saveRefusesASharedScopeThatIsNotTheBoundOne` and `saveKeepsACallerSharedScopeWhenNoneIsBound`.
- Generated handler test (`KernelHandlerTestGenerator#addCallerFaultTests`), executed by the same gate:
  `POST` and `PUT` answer 400 for a foreign tenant, and `POST` for a foreign shared scope, *and* the
  decoded entity reached the write — so the 400 is not one of the guard 400s ahead of it.
- OpenAPI: entity-schema `readOnly: true` and DTO omission asserted on the model
  (`OpenApiComponentsBuilderTest`), on the emitted YAML parsed back (`OpenApiGeneratorTest`), and end
  to end from an annotated source (`SharedScopeSqlE2ETest`).
- `KernelCodegenCompileTest` compiles the new types and the multi-catch against kernel 0.12.0.

## Amendment 1 — an update keeps every server-owned column, not only the owner (2026-10-08)

**Status:** Accepted *(widens Decision §2 from the owner to every server-owned column, and §6's
`…UpdateDto` omission with it; §1, §3, §4 and §5 are unchanged)*

**Deciders:** the founder.

§2 takes the owner out of the `UPDATE … SET` list so that no update can move a row. A server-owned
column left in that list is written with whatever the `PUT` body carries: an audited entity's
creation stamp and author, a soft-deleted entity's flag. A body with `"deleted": false` restores a
soft-deleted row, and a body that leaves the creation stamp out stores it as null. The rule §2
states for the owner — a column the server owns is not the body's to write — holds for each of
them.

**The update replaces every domain field and keeps every server-owned column.** The server-owned
fields are the ones `ListQuerySupport#systemFieldNames` names, the set ADR-096 already excludes
from sorting and filtering: the key, the owner, the audit fields (created and updated at and by),
the soft-delete fields (the flag, `@SoftDeleteTimestamp`, `@SoftDeletedBy`), the version, the
shared scope, and every field a `systemFields` role names. Of these, an update:

| Field | `SET` list | Value written |
|---|---|---|
| key | no — it closes the `WHERE` clause | — |
| owner, created at, created by, updated by, soft-delete flag, soft-delete timestamp, soft-deleted by | no | the stored value stays |
| updated at (an `audited` entity) | yes | `Instant.now()` |
| version (a `versioned` entity) | yes | the body's expected version plus one |
| shared scope (a UNIVERSE entity) | yes, as §3 decides | the body's value, refused when it contradicts a bound scope |

`ServerOwnedFields#keptOnUpdate` is the second row's set and `KernelRepositoryGenerator#updateColumns`
filters the column layout through it. The update stamp is written because the server sets it; no
generated code knows the acting principal, so the updated-by field has no server value and keeps the
stored one.

**The returned entity is the row as stored.** After a row matched, `update` reads the kept columns
back on the same connection, inside the same managed transaction, and sets them on the entity it
returns (`KernelRepositoryGenerator#buildReadStoredColumns`, emitted as `readStoredColumns`). An
entity whose only kept column is the key reads nothing back. The read is a single-row `SELECT` by
key and costs one round trip per update. The alternatives do not hold:

- `UPDATE … RETURNING` is not portable to every engine the kernel persistence driver runs on; the
  in-memory H2 the boot tests use has no `RETURNING`.
- carrying the body's values through leaves the response saying something the row does not;
- reading before the write sees a row another transaction may change before the `UPDATE` runs.

If the read finds no row, it throws the rejection a zero-row update throws (ADR-076) and the
transaction rolls back, so the write never commits behind a rejection.

**A soft-deleted row is not updated.** The `UPDATE` of a `softDelete` entity also matches
`AND <flag> = false`, the predicate `findById`, `findAll`, `count` and `deleteById` already apply.
Without it, a `PUT` to a soft-deleted row would write the row every read treats as absent. With it,
the row is absent to the update as well, and the update answers what ADR-076 assigns to a row that
is not there: `404`, or `409` on a versioned entity, whose zero-row result cannot tell gone from
stale.

**The published contract follows.** `…UpdateDto` leaves out every field
`ServerOwnedFields#notInUpdateBody` names: the server-owned set above without the version, which the
body must carry. The `…CreateDto` and the entity schema are unchanged; an insert's treatment of the
audit and soft-delete fields is not part of this amendment.

### What the amendment leaves unchanged

- §1's refusal, §3's shared-scope rule, §4's types and §5's handler mapping.
- The insert: `save` still writes every layout column and stamps the audit columns.
- The TypeScript `…Update` type (ADR-092): it still carries the audit and soft-delete fields, which
  the server ignores. Aligning it is a separate `exeris-codegen-ts` change.

### Consequences of the amendment

- **Breaking for a client that wrote server-owned columns through `PUT`.** Correcting a creation
  stamp or an author, or restoring a soft-deleted row with `"deleted": false`, needs a route of the
  consumer's own. The regeneration note is `docs/migration/0.10.0/java-11-update-keeps-server-owned-columns.md`.
- **One more statement per update** for an entity that keeps any column besides the key — every
  audited, soft-deleted or tenant-partitioned entity.
- The generated `RecordingPersistence` gains `writeBinds`, the binds of the last write, because the
  read-back's `prepare` clears `binds` (ADR-058's double; still JUnit 5 and AssertJ only).

### Verification of the amendment

- `KernelRepositoryServerOwnedUpdateTest` — the `SET` list and the read-back per column kind:
  audited, versioned, soft-deleted, tenant-partitioned, every flag at once, the audit and
  soft-delete role fields, declared role names, a renamed key, a UNIVERSE shared scope, and an
  entity with nothing to read back; `ServerOwnedFieldsTest` for the two sets.
- The generated `<Entity>RepositoryTest` gains `updateWritesNoServerOwnedColumnAndReturnsTheStoredOnes`
  for an entity with a kept system column, and `updateNeverWritesTheTenantSoARowCannotMove` asserts
  the returned owner is the stored one; `GeneratedTestsE2ETest` runs both for a TENANT and a
  UNIVERSE entity.
- `UpdateKeepsServerOwnedColumnsBootE2ETest` boots the emitted application on H2: a `PUT` with a
  forged `createdAt`, `createdBy` and `deleted` leaves all three as stored, in the response and in a
  later `GET`, and a `PUT` to the row after `DELETE` answers `404`. With `createdAt` put back in the
  `SET` list, the test fails on the forged stamp.
- `OpenApiComponentsBuilderTest#updateDtoLeavesOutTheFieldsTheUpdateKeeps`.

## Amendment 2 — a request body does not write read-only fields, and the update schema is the body the update writes from (2026-10-08)

**Status:** Accepted *(adds a second update path beside Amendment 1's; replaces §6's and Amendment
1's account of the `…UpdateDto`; §1 to §5 are unchanged)*

**Deciders:** the founder.

A field marked `@Field(readOnly = true)` is one the client does not set: the form shows it and never
edits it, and the `…CreateDto` and `…UpdateDto` have always left it out. The update still wrote it
from the request: the handler decodes the whole entity and the `UPDATE … SET` list carried every
domain column, so a `PUT` body naming a read-only field rewrote it, and a body that left it out
stored it as null. A read-only field is not a server-owned column, though. An action's entity method
changes one as domain logic — `approve()` sets a status, `lock()` sets a lock expiry — and the
action persists through the same update (§5).

The `…UpdateDto` disagreed with the statement in two more places. It left out a UNIVERSE entity's
shared scope, which §3 keeps in the `SET` list and writes from the body. And it listed the version
only when the entity declares a field of that name, while the optimistic-lock update matches on the
version the body carries whether or not it is declared.

**Two update paths.** An entity with a read-only field gets two updates in its repository and
service:

- `update(id, entity)` is the update an action drives: the respond-once action route and the
  streaming action (ADR-044) call it after the entity method ran. It writes the read-only fields.
- `updateFromRequest(id, entity)` is the update the `PUT {base}/{id}` handler calls. It leaves the
  read-only fields out of its `SET` list and reads their stored value back onto the entity it
  returns, as Amendment 1 does for the server-owned columns. It reads them back through its own
  `readStoredRequestColumns`.

An entity without a read-only field has `update` alone, and the `PUT` handler calls it: the two
statements would be the same. On both paths a server-owned column keeps its stored value, and the
update stamp and the version are set by the server, as Amendment 1 decides. The insert writes the
read-only fields.

A field that also plays a system role keeps that role's rule: a read-only version is still matched
and incremented, and a read-only shared scope is still written.

`ServerOwnedFields#keptOnUpdate` stays the set every update keeps (Amendment 1's).
`ServerOwnedFields#keptOnRequestUpdate` is that set and `#readOnlyFields`, and
`KernelRepositoryGenerator#requestUpdateColumns` filters the column layout through it.
`ServerOwnedFields#notInUpdateBody` is the set the request body does not carry: Amendment 1's
server-owned set without the version and without a UNIVERSE entity's shared scope, plus the
read-only fields.

| Field | `update` (actions) | `updateFromRequest` (`PUT`) | `…UpdateDto` |
|---|---|---|---|
| a domain field | written | written | yes |
| a domain field marked `readOnly` | written | not written, read back | no |
| key, owner, created at and by, updated by, soft-delete fields | not written, read back (Amendment 1) | not written, read back | no |
| updated at (an `audited` entity) | `Instant.now()` | `Instant.now()` | no |
| version (a `versioned` entity) | expected version plus one | expected version plus one | **yes**, declared as a field or not, `integer`/`int64` under its role name |
| shared scope (a UNIVERSE entity) | written (§3) | written (§3) | **yes** |

**The update schema is the body the update writes from.** `OpenApiComponentsBuilder` builds the
`…UpdateDto` from `notInUpdateBody` and adds the version of a versioned entity that declares no
field of that name. No property is `required`. The entity schema and the `…CreateDto` are
unchanged: the shared scope stays `readOnly` on the entity schema and out of the `…CreateDto`, and a
read-only field stays out of the `…CreateDto`.

**The update route validates what it writes.** `handleUpdate` runs the `@Validation` guard (T10) on
the fields the request body carries only (`KernelValidationRules#onUpdate`). A field the request
update keeps is not written from the body, so checking the body's value of it would refuse a request
for a value the update ignores, and a `required` one would refuse every `PUT` that follows the
published schema. `handleCreate` still checks every field; an action route checks none, as before.

### What the amendment leaves unchanged

- §1's refusal, §3's shared-scope rule, §4's types and §5's handler mapping. The refusal runs in
  both updates.
- The insert, and `handleCreate`'s validation: a `required` read-only field is still checked on
  `POST`, although the `…CreateDto` leaves it out.
- The TypeScript `…Update` type (ADR-092); aligning it with this schema is an `exeris-codegen-ts`
  change.

### Consequences of the amendment

- **Breaking for a client that wrote a read-only field through `PUT`.** An action that changes one
  keeps working. The regeneration note is
  `docs/migration/0.10.0/java-12-update-keeps-read-only-fields.md`.
- **Public API of the generated app:** an entity with a read-only field gains
  `<Entity>Repository#updateFromRequest` and `<Entity>Service#updateFromRequest`. Code of the
  consumer's own that applies a client's values should call `updateFromRequest`; code that applies a
  domain change calls `update`.
- **One more read per `PUT`** for an entity whose only kept columns are its read-only fields.
- **A client generated from the OpenAPI document** sends the shared scope and the expected version in
  the update body.

### Verification of the amendment

- `ServerOwnedFieldsTest` — a read-only field is in `notInUpdateBody` and `keptOnRequestUpdate`, not
  in `keptOnUpdate`; a read-only version or shared scope keeps its role; the shared scope is in no
  set.
- `KernelRepositoryServerOwnedUpdateTest` — with a read-only field, `updateFromRequest` leaves it out
  of the `SET` list and reads it back and `update` writes it, alone and beside the server-owned
  columns; without one, no `updateFromRequest` is emitted.
- `KernelServiceGeneratorTest`, `KernelHandlerGeneratorTest`, `KernelActionStreamHandlerGeneratorTest`
  — the service's two updates reach the repository's two; `handleUpdate` calls `updateFromRequest`,
  the respond-once and the streaming action call `update`.
- `OpenApiComponentsBuilderTest` — the shared scope is in the `…UpdateDto`; an undeclared, a renamed
  and a read-only version are in it; an unversioned entity has none; a read-only field is in neither
  DTO. `SharedScopeSqlE2ETest` asserts the shared scope end to end from an annotated source;
  `KernelOpenApiGoldenDocumentTest` pins the undeclared version.
- `KernelValidationRulesTest#updateChecksOnlyTheBody`, `KernelHandlerTestGeneratorTest` —
  `handleUpdate` checks no kept field, and its generated test case is driven by a field the body
  carries.
- `GeneratedTestsE2ETest` executes the generated tests of an entity with a required read-only field
  and a primitive one, including `updateFromRequestKeepsTheReadOnlyColumnsAndUpdateWritesThem` and the
  service's `updateFromRequestReachesTheRepositorysRequestUpdate`.
- `UpdateKeepsServerOwnedColumnsBootE2ETest` boots the emitted application: a `PUT` with a forged
  read-only `status` leaves it as stored; an action whose entity method sets it stores the new value,
  in the response and in a later `GET`; a `PUT` forging it back leaves the action's value. With the
  `PUT` route calling `update`, the test fails on the forged value; with `update` keeping the
  read-only fields, it fails on the action's.

## Amendment 3 — the create validates the body it carries, and `inCreate` / `inUpdate` shape the bodies like `readOnly` (2026-10-08)

**Status:** Accepted *(narrows Amendment 2's "What the amendment leaves unchanged" for
`handleCreate`'s validation; extends Amendment 2's read-only rule to `@Field(inUpdate = false)`; §1
to §5 and Amendments 1 and 2 are otherwise unchanged)*

**Deciders:** the founder.

Amendment 2 left `handleCreate` checking every field. A field marked `required` and `readOnly` is
out of the `…CreateDto`, so a `POST` that follows the published schema carries no value for it and
the handler answered `400`. The same holds for the key, the owner and a UNIVERSE entity's shared
scope, which the repository fills and the schema leaves out. `@Field(inCreate = false)` and
`@Field(inUpdate = false)` are declared on the field and carried into the metadata, and neither
shaped any Java output: the create and update schemas listed such a field, the update wrote it, and
both handlers validated it.

**Each body-carrying route validates the fields its published body lists.**
`ServerOwnedFields#notInCreateBody` is the set the create body leaves out: the key, the owner, a
UNIVERSE entity's shared scope, the read-only fields and the fields marked `inCreate = false`.
`OpenApiComponentsBuilder` builds the `…CreateDto` from it and `KernelValidationRules#onCreate`
selects the create route's `@Validation` guards from it, so the schema and the guard cannot list
different fields. A field with a `required` rule that the body does not list is no longer refused on
`POST`.

**`inUpdate = false` is read-only on the `PUT` path.** `ServerOwnedFields#readOnlyFields` becomes
`fixedOnRequestUpdate`: the domain fields marked `readOnly = true` or `inUpdate = false` that play no
system role. They are out of `updateFromRequest`'s `SET` list and read back, out of the
`…UpdateDto` and unvalidated on `PUT`; `update`, which an action drives, writes them, as Amendment 2
decides for a read-only field. A field in a system role keeps that role's rule.

*Rename:* the method Amendment 2 names `ServerOwnedFields#readOnlyFields` is
`ServerOwnedFields#fixedOnRequestUpdate` from this amendment on; Amendment 2's text keeps the old
name as it was accepted.

**The insert is unchanged.** It writes the entity it is handed, so a `POST` body that names a
read-only or `inCreate = false` field still stores that value; the schema does not list it and the
generated client never sends it.

**A required read-only field is set by the server before the insert.** A read-only field is out of
the create body whatever its `inCreate`, and `@Field.defaultValue` reaches no generated code. A plain
domain field that is both `required` and `readOnly` therefore means "the server sets it before the
row is written": the code that creates the row, a service of the consumer's own, fills it. The
generated repository's `save` checks, before it prepares the `INSERT`, that every such field of a
reference type is non-null and throws an `IllegalStateException` naming the entity and the field
(`Cannot create <Entity>: field '<field>' is required and read-only, so the server must set it
before the row is written`). `IllegalStateException` is the type the generated code raises for a
server-side defect (the tenant resolver's, ADR-090 §4), which the handler's `RuntimeException` arm
logs with the exception and answers `500`, so the defect is not reported as the caller's and the
database's `NOT NULL` violation is not what surfaces. A field in a system role (the key, the owner,
the shared scope, the audit, version and soft-delete fields the entity enables, and every name a
`systemFields` block declares) is set by the server and is not checked; neither is a primitive.
`ServerOwnedFields#setByServerOnCreate` is the set.

**The entity schema lists the version.** A versioned entity that declares no field of the
version's name gets a `readOnly` `integer`/`int64` property under the version's role name on the
entity schema, as the `…UpdateDto` has it since Amendment 2: the response carries the stored version
the next `PUT` sends back.

### Consequences of the amendment

- **Breaking for a client that set an `inUpdate = false` field through `PUT`.** The value is
  ignored and the stored one is returned; an action changes it. The regeneration note is
  `docs/migration/0.10.0/java-13-create-and-update-follow-the-published-schemas.md`.
- An entity with an `inUpdate = false` field gains `updateFromRequest` on its repository and
  service, as an entity with a read-only field does.
- The TypeScript `…Update` type still lists an `inUpdate = false` field, which the form sends as
  loaded; aligning it is an `exeris-codegen-ts` change.

### Verification of the amendment

- `ServerOwnedFieldsTest`, `KernelValidationRulesTest` — the create and update sets per field kind.
- `OpenApiComponentsBuilderTest` — the create and update bodies per flag; the entity schema's
  undeclared version.
- `KernelHandlerGeneratorTest`, `KernelHandlerTestGeneratorTest` — the guard of each route lists the
  fields its body carries.
- `KernelRepositoryServerOwnedUpdateTest` — an `inUpdate = false` field is out of
  `updateFromRequest`'s `SET` list and read back.
- `ServerOwnedFieldsTest`, `KernelRepositoryServerOwnedUpdateTest` — the set, and the check ahead of
  the `INSERT`; the generated `<Entity>RepositoryTest` has `save<Field>LeftNull` per field, which
  asserts the exception names the field and nothing is bound.
- `MetadataLoaderTest` — a field without `inCreate` / `inUpdate` keys reads as `true`, the
  `@Field` default and the default the TypeScript loader's schema applies.
- `LifecycleFlagsBootE2ETest` boots the emitted application: a `POST` and a `PUT` that follow the
  schemas are accepted when the consumer's service fills the required read-only field, a `POST`
  whose service leaves it null answers `500` and the log carries the entity and the field, a forged
  `inUpdate = false` value is ignored and an action's change is stored.

## Amendment 4 — the create body leaves out every server-owned field, and the entity schema lists the audit stamps only when the entity has them (2026-10-08)

**Status:** Accepted *(narrows Amendment 3's "The insert is unchanged" for the fields below; §1 to §5
and Amendments 1 to 3 are otherwise unchanged)*

**Deciders:** the founder.

A create is server-owned the way a `PUT` is (Amendments 1 and 2): the body does not choose a value
the server stamps, starts or holds.

**`ServerOwnedFields#notInCreateBody` holds every system-role field.** The key, the owner and a
UNIVERSE entity's shared scope were already in it; it now also holds the audit fields (created and
updated at and by), the version and the soft-delete fields (flag, time and actor), each under the
name the `systemFields` block gives it, else its default, for the roles the entity's flags switch on
(`ListQuerySupport#systemFieldNames`), together with the read-only and `inCreate = false` fields. They
leave `<Entity>CreateDto` and the create route's validation.

**The create handler drops what the body carried.** The handler decodes the body into the entity, so
before the validation and the service it sets the audit times and, when the entity declares them as
fields, the actors to `null`, the version to its initial value (`0`), the soft-delete flag to `false`
and, when declared, the deletion time and actor to `null` (`ServerOwnedFields#resetOnCreate`). The
repository's `save` then stamps the creation and update times with `Instant.now()` and the insert
binds a version that is still `null` as `0`. The generated code has no principal, so an author is
never taken from the body; a value the consumer's own service sets on the entity before it calls
`save` is kept, since the reset runs ahead of the service. The check that a `required` and `readOnly`
field is set before the insert (Amendment 3) is unchanged.

**The entity schema lists the audit stamps only for an entity that has them.**
`OpenApiComponentsBuilder` listed `createdAt` and `updatedAt` on every entity schema. It lists them
for an audited entity, under the role's name, `readOnly` as ADR-090 §6 has it for a server-owned
field; an entity that is not audited and declares no such field has no such property, and a field it
declares keeps its own schema.

### Consequences of the amendment

- **Breaking for a client that set an audit, version or soft-delete field through `POST`.** The value
  is ignored. The regeneration note is
  `docs/migration/0.10.0/java-14-create-body-leaves-out-server-owned-fields.md`.
- The TypeScript `…Update` type still lists an `inUpdate = false` field, which the form sends as
  loaded; `exeris-tooling#381` aligns the TypeScript side with the Java bodies.

### Verification of the amendment

- `ServerOwnedFieldsTest` — the create set per role and per renamed role, and the resets.
- `OpenApiComponentsBuilderTest` — the entity schema with and without the audit stamps.
- `KernelValidationRulesTest`, `KernelHandlerGeneratorTest` — the create guard and the resets.
- `CreateIgnoresServerOwnedFieldsBootE2ETest` boots the emitted application: a `POST` that forges
  `createdAt`, `updatedAt`, `version`, `deleted` and `createdBy` stores the server's values, and the
  author a service sets is kept.
