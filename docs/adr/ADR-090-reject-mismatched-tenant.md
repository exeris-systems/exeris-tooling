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
  the decision is implemented on `main` (`exeris-tooling` #223)
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
