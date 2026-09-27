# ADR-090 — A write naming another tenant is refused with 400, not left to row-level security

- **Status:** ACCEPTED (2026-09-26, founder decision on T36) · **number pending registration** — 090 is
  the next free number in `exeris-docs/adr-index.md` as of 2026-09-26 (its highest row is 089). The
  reservation row has not landed there yet and must before this file merges (adr-conventions rule 2);
  the founder files it, because this change cannot write to `exeris-docs`.
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen pipeline — emitted repository, handler, OpenAPI, TypeScript types
- **Visibility:** public
- **Milestone:** 0.9.0 (finding **T36**; applies to the shared-scope field of **T29 slice B**)
- **Supersedes:** the rationale recorded for T36 in 0.8.0 ("a contradicted tenant is left to the RLS
  `WITH CHECK` predicate … re-deciding it here would be a second implementation of a rule the
  database already enforces"). That rationale was never an ADR; it lived in the Javadoc of
  `KernelRepositoryGenerator#appendTenantStamp` and in the emitted `*RepositoryTest`.

## Context

T36 (0.8.0) made the generated repository stamp a tenant-partitioned row's owner from the bound
`StorageContext` when the caller left it unset. It deliberately did **not** look at an owner the
caller *did* set: a contradicted tenant was left to the migration's row-level-security policy,
because re-deciding it would be "a second implementation of RLS". Four facts, each measured or read
from source, undo that reasoning.

1. **RLS is not always there.** A role with `SUPERUSER` or `BYPASSRLS` skips every policy, even on a
   `FORCE`d table. An engine without row-level security has no policy at all. The emitted migration
   cannot tell which of these a deployment runs; Stellar Tactics' `LOGIN CREATEDB`-and-not-superuser
   role note exists precisely because a superuser made every isolation gate pass vacuously. On such a
   deployment, a `POST` or `PUT` body naming any tenant was written as that tenant.
2. **Where RLS is there, it answers the wrong status.** A `WITH CHECK` violation surfaces from the
   driver as a persistence exception and reaches the handler's `catch (RuntimeException)`, which
   answers **500**. The request is the caller's mistake: repeating it unchanged fails the same way and
   no operator action makes it succeed — kernel ADR-083's `FaultOrigin.CALLER`, the class ADR-036 §2
   maps to 4xx. A 500 pages an operator for a malformed request and tells the caller nothing.
3. **An update could move a row.** The emitted `UPDATE … SET` list carried the owner column, so a
   `PUT` body with a different tenant tried to re-own the row. Under RLS the new row fails
   `WITH CHECK` (the 500 above); without RLS it succeeds.
4. **On a UNIVERSE table the owner in the SET list is a takeover.** T29 slice B widens reads across a
   shared scope. Measured on PostgreSQL 16 as a non-owner `NOSUPERUSER NOBYPASSRLS` role, with the
   kernel's reference single-policy shape: `UPDATE t SET owner = <self> WHERE id = <partition-mate's
   row>` affects **1 row** — a tenant re-owns a row it can only read. (Slice B's additive `FOR SELECT`
   policy closes this in the database; this ADR closes it in the repository as well, for the engines
   and roles where the database does not.)

The tooling already accepts double enforcement where a database rule has a caller-facing twin: the
T10 `CHECK` constraints mirror the handler's `@Validation` 400s.

## 🏁 The Decision

**With a tenant bound, the generated repository refuses a row naming another tenant, with a typed
caller fault the generated handler answers 400. With no tenant bound, nothing changes. The owner is
never updated.**

1. **Refuse, only while a tenant is bound.** `save` and `update` of a tenant-partitioned entity (TENANT
   and UNIVERSE) run, right after the T36 stamp, `refuseForeignTenant(entity.get<Owner>())`:
   - "bound" means `KernelProviders.STORAGE_CONTEXT.isBound()` **and** an isolation key on it; an
     unbound slot and the system scope both mean "nothing to compare against" and the row is left to
     the database exactly as before — a seeder that writes owners explicitly keeps working;
   - the slot is tested before the throwing accessor, so "none bound" can never become a failure;
   - a different owner throws `<Entity>TenantMismatchException(UUID tenantId)`;
   - a non-UUID isolation key is the same `IllegalStateException` deployment fault the stamp already
     reports (never an `IllegalArgumentException`, which ADR-036 §2 reads as the caller's).
2. **The owner is not in the `UPDATE … SET` list.** Of the brief's two options — drop it, or refuse a
   change — dropping it is the one that holds everywhere:
   - it makes a move *impossible* rather than detected, on every engine and role, bound or not;
   - detecting a change needs the stored owner, which the repository only learns through a
     predicate (`AND owner = ?`) that turns a move attempt into zero rows — a 404/409 that reports a
     caller fault as a missing row, the misreport this ADR exists to remove;
   - with a tenant bound the refusal in (1) already rejects a foreign owner before the statement;
   - on a UNIVERSE table it removes the takeover in Context §4 independently of the policy shape.

   The `INSERT` still writes the owner. The update still stamps an absent owner, so the returned
   entity names it. An entity left with nothing to write — no domain field, no audit or version
   column — emits `UPDATE … SET id = id WHERE id = ?`, which binds nothing and keeps the zero-row
   answer (404/409); a field-less `GLOBAL` entity emitted an empty, invalid `SET` list before this
   change and gets the same fix.
3. **The same rule for the caller-writable shared-scope field** (T29 slice B). A UNIVERSE entity's
   `@SharedScope` field is decoded from the request body like any field, so it gets the same
   treatment: with a scope bound (a non-blank `sharedScopeKey`), a row tagged with another scope is
   refused with `<Entity>SharedScopeMismatchException(String sharedScope)`; with none bound the
   caller's tag is kept. Unlike the owner it **stays in the SET list** — an owner moving its own row
   between scopes, or out of one, is a legitimate write, and the owner-pinned policy still decides
   whose row it is.
4. **The types** are emitted by `KernelErrorGenerator` next to the ADR-076 ones, in the repository
   package, per entity (ADR-076's reasoning for both choices applies unchanged): plain
   `RuntimeException`s carrying the rejected value, not the row id.
5. **The handler maps them to 400**, ahead of the `RuntimeException` → 500 tail, on the three routes
   that hand the repository an entity to write — `POST`, `PUT /{id}`, and every action (which persists
   through `service.update`). A UNIVERSE entity catches both types in one multi-catch. No log, like
   the 404 beside it.
6. **The contract says so.** The emitted OpenAPI marks the owner — and a UNIVERSE entity's shared-scope
   field — `readOnly: true` on the entity schema and leaves both out of the create/update DTOs. The
   TypeScript emitter omits the same fields from its create DTO and schema (`systemFieldNames`, now
   also for a tenant-partitioned entity without a `systemFields` block) and from the generated form.

### Exception → status chain

| Where | What happens | Answer |
|---|---|---|
| `<Entity>Repository.save/update` → `refuseForeignTenant` | bound isolation key ≠ `entity.<owner>` | throws `<Entity>TenantMismatchException` |
| `<Entity>Repository.save/update` → `refuseForeignSharedScope` (UNIVERSE) | bound shared scope ≠ `entity.<scope>` | throws `<Entity>SharedScopeMismatchException` |
| `<Entity>Service.save/update` | delegates; does not catch | propagates |
| `<Entity>Handler.handleCreate / handleUpdate / handle<Action>` | `catch (<Entity>TenantMismatchException [| <Entity>SharedScopeMismatchException] e)` | **400 Bad Request**, no body, no log |
| same, no tenant bound | no refusal; the row reaches the database; RLS decides | 201/200, or 500 on an RLS violation (unchanged) |
| same, non-UUID isolation/scope key | `IllegalStateException` | 500 (deployment fault, unchanged) |

### Why 400 — and not 403, 409 or 500

- **Not 500:** the caller is at fault (ADR-083 `CALLER`; ADR-036 §2's 4xx arm).
- **Not 403:** the emitted application binds no authorization model — ADR-079 withdrew the spec's
  authentication claim for the same reason. A 403 would assert a permission system that does not
  exist.
- **Not 409:** ADR-076 gave 409 one meaning, a version conflict; a client retries on it.
- **400:** the request body is wrong in a way the caller can fix, and is wrong the same way every time.

### Why the types do not extend `ExerisKernelException`

`FaultOrigin.classify` answers `CALLER` only for an `ExerisKernelException` that says so. Extending it
would make these types classify themselves for a generic adapter, but that base class is the kernel's
own: it requires an `EX-<DOMAIN>-<ID>` code from kernel-owned domains and allocates a trace UUID and a
timestamp per construction. The generated handler already catches by type, as ADR-076 does, and the
exception never leaves it. If a consumer adapter ever needs classification, a tooling-owned marker is
the change to make, not borrowing the kernel's base.

### The required database role model

The refusal covers a **bound** request. Everything else still rests on row-level security, so a
deployment of generated code must connect as a role that:

- is `NOSUPERUSER` **and** `NOBYPASSRLS` — either attribute makes every policy, forced or not, inert;
- does **not own** the tables, or the tables are `FORCE ROW LEVEL SECURITY` (the emitted migrations
  force them; a hand-written table must too);
- is granted `SELECT, INSERT, UPDATE, DELETE` on the tables and nothing that implies the above.

A role holding either bypass attribute turns every isolation test into a vacuous pass. Checking the
role at boot belongs to the kernel or the persistence driver, which own the connection — not to
generated code — and is recorded as an ask, not built here.

## Consequences

### ✅ Positive Outcomes

- A body naming a foreign tenant is refused on every engine and role while a tenant is bound, and is
  reported as the caller fault it is.
- No update can move a row between tenants, bound or not, RLS or not.
- On a UNIVERSE table the repository adds a second barrier against re-owning a partition-mate's row.
- The published contract stops inviting clients to send a field the server owns.
- The emitted handler test of a tenant-partitioned entity now runs: it dispatches with a tenant bound.
  Before this change every one of its cases failed on the T41 guard (500), which no gate noticed,
  because `GeneratedTestsE2ETest` ran only the global entity's handler test.

### ⚠️ Trade-offs

- **This is a second implementation of part of RLS — on purpose, and narrowly.** It covers a bound
  request's write, and nothing else: reads and deletes are still partitioned only by the policy. The
  two cannot drift in the dangerous direction — the refusal is strictly narrower than the policy's
  `WITH CHECK`, so it can only turn a would-be 500 into a 400, never admit what the policy refuses.
- **A partition-mate acting on a shared row it can read** (an action route loads it, then persists)
  gets 400, not the 404/409 the database's zero-row update would have produced. The caller is at fault
  either way; 400 now says so consistently.
- **Scope pinning on write is stricter than the kernel's reference policy** while a scope is bound: an
  owner in a session bound to scope V cannot save its own row tagged W. The kernel has not ruled on
  §4b.4's write-side scope pin (tooling ask k1); this ADR chooses the conservative reading for
  bound requests and leaves unbound ones unchanged.
- **One more emitted type per tenant-partitioned entity**, two per UNIVERSE entity with a
  `@SharedScope` field — public API of the generated app, covered by the 0.x regeneration contract.
- **`@TenantId(validateOnMutation = false)` does not turn the refusal off.** The attribute is still
  inert (the strict-audit note says so); the refusal is unconditional for a bound request.

### 📋 What is NOT in scope

- Reads and deletes on a non-RLS engine or a bypassing role — still unpartitioned there. Filtering them
  in Java would be the full second implementation the original T36 note rightly warned against.
- A boot-time role check (above).
- 401 and authentication — ADR-079.
- `readOnly` for the audit/version columns — the entity schema does not mark them today either; a
  separate change if anyone wants the contract to say so.

## Cross-references

- **ADR-076** — the typed-rejection pattern and the per-entity / repository-package placement reused
  here.
- **ADR-036 §2** and kernel **ADR-083** (`FaultOrigin`) — the caller-vs-system split the 400 follows.
- **ADR-079** — why not 403.
- **ADR-058** — the emitted tests that prove the chain run under the generated-test gate.
- **ADR-059** (link stub) and **T29 slice B** — the UNIVERSE tier whose shared-scope field gets the
  same rule, and the PostgreSQL measurement behind Context §4.
- **T36**, **T41**, **T10** in `ROADMAP.md`.

### Verification

- Generated repository test, executed by `GeneratedTestsE2ETest` for a TENANT and a UNIVERSE entity:
  bound tenant accepted; foreign tenant refused with the typed exception and nothing bound;
  unbound write left to the database; an update never binds the owner. For UNIVERSE, additionally:
  foreign shared scope refused; unbound tag kept.
- Generated handler test, executed by the same gate: `POST` and `PUT` answer 400 for a foreign tenant
  (and `POST` for a foreign shared scope) *and* the decoded entity reached the write — so the 400 is
  not one of the guard 400s ahead of it.
- Perturbation: removing the emitted catch turns all four executed 400 cases into
  `expected 400 but was 500`; disabling the comparison in `refuseForeignTenant` fails both executed
  `saveRefusesATenantThatIsNotTheBoundOne` cases.
- OpenAPI: entity-schema `readOnly: true` and DTO omission asserted on the model, on the emitted YAML
  (parsed back), and end to end from an annotated source.
- The compile gate compiles the new types and the multi-catch against kernel 0.12.
