---
title: "0.11.0 migration step: generated tenant policy raises for an absent tenant and pins the shared-scope tag"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### Generated tenant policy raises on absent tenant and pins shared scope on write

Regenerating the application emits a forward Flyway migration for each tenant-partitioned table
(`KernelTenantPolicyMigrationGenerator`): `V5<nnnnnn>__tenant_policy_<table>.sql`.

The initial CREATE table migration (`V1...` or `V2...`) remains byte-identical, so databases that have
already applied it do not suffer Flyway checksum verification errors. On application startup, Flyway
applies the tier 5 migration forward:

1. **Absent tenant raises instead of returning empty results:** The tenant policy's `USING` and
   `WITH CHECK` expressions drop `NULLIF`, using the unguarded cast
   `current_setting('exeris.tenant_id', true)::uuid`. When a connection executes without a tenant
   bound to the session, the kernel publishes `exeris.tenant_id` as `''`. The unguarded `::uuid`
   cast raises PostgreSQL error `22P02` (`invalid input syntax for type uuid: ""`), which the kernel
   reports as `EX-PERS-5003`. Unbound reads fail closed and loudly instead of silently returning an
   empty result (kernel ADR-012 §4c.3).
2. **Write check pins the shared-scope tag:** For `@ExerisDomain(dataScope = UNIVERSE)` tables
   carrying a `@SharedScope` column, `WITH CHECK` also pins the tag:
   - On `UUID` scope columns: `AND (shared_scope IS NULL OR shared_scope = NULLIF(current_setting('exeris.shared_scope', true), '')::uuid)`
   - On `TEXT`/`VARCHAR` scope columns: `AND COALESCE(shared_scope, '') IN ('', current_setting('exeris.shared_scope', true))`
   An `INSERT` or `UPDATE` tagging a scope the session does not publish is refused by PostgreSQL with
   SQLSTATE `42501` (`insufficient_privilege`). Un-sharing a row (`NULL` or `''`) remains admitted
   (kernel ADR-012 §4c.2).

The forward `V5...` migration is generated under `src/main/generated/`, committed with the rest of
the tree, and applied by Flyway at boot; no hand-written SQL is needed.

Code that reads a tenant-partitioned table with no tenant bound, such as a scheduled job that ran
outside a request, got an empty result before and gets `EX-PERS-5003` after the migration. Bind the
tenant's `StorageContext` around that work, or read the rows it needs through a request that carries
the tenant.
