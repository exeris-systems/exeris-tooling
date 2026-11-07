---
title: Reference other entities
type: howto
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-04
---

# Reference other entities

**Audience:** your entities point at each other, belong to a tenant and carry audit times, and you want to know what the generated schema and repositories enforce.

**Goal:** generate the `invoicing` scenario's `Customer`, `Invoice` and `InvoiceLine`, read the foreign keys and the refusals that come with them, and see what tenant scope, audit and route access add.

> **Verified against** exeris-tooling `main` at commit `dc4564a2` (`0.11.0-SNAPSHOT`) and `exeris-sdk-examples` at tag `v0.13.3`, 2026-11-04. Generated snippets are quoted from the `SdkExamplesE2ETest` workspace, `exeris-e2e-tests/target/sdk-examples/app/`. If a snippet and its source disagree, **the source wins and this guide is the defect**.

## Prerequisites

- [02 Generate an application from an entity](02-generate-an-application.md), for the shape of one entity's output.
- The `invoicing` example: [`Customer.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/invoicing/domain/Customer.java), [`Invoice.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/invoicing/domain/Invoice.java) and [`InvoiceLine.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/invoicing/domain/InvoiceLine.java).

## Steps

### 1. Hold a reference as the other row's key

A reference is a `UUID` field holding the referenced row's primary key, with `@Relationship` naming the target.

Source: [`exeris-sdk-examples/.../invoicing/domain/Invoice.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/invoicing/domain/Invoice.java), the `customerId` field (quoted).

```java
@Relationship(
        targetEntity = Customer.class,
        displayField = "name",
        relationshipType = Relationship.RelationshipType.MANY_TO_ONE)
private UUID customerId;
```

A field typed as the entity itself, `private Customer customer;`, has no column to live in. The Java pipeline refuses it with `EXT-GEN-3003` and generates nothing; the message names the `UUID` field to declare instead ([diagnostics](../diagnostics.md)).

### 2. Read the foreign keys

Each `MANY_TO_ONE` to an entity of the same build becomes a `UUID` column, an index, and a constraint in one migration that runs after every `CREATE TABLE`.

Generated: `src/main/generated/java/db/migration/V3000000__foreign_keys.sql` (adapted: the comment lines removed).

```sql
ALTER TABLE invoice_lines ADD CONSTRAINT fk_invoice_lines_invoice_id FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE CASCADE;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_customer_id FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT;
```

The repositories turn a violation of these constraints into typed exceptions, which the handlers answer:

| Write | Exception | Answer |
|---|---|---|
| Create or update an `Invoice` whose `customerId` names no customer | `InvoiceReferenceNotFoundException` | 422, a problem object naming the field |
| Delete a `Customer` that an invoice still references | `CustomerStillReferencedException` | 409 |

`InvoiceRepository` also gains `findByCustomerId(UUID)`. Each exception's row, and the condition under which it is written, is in the [generator catalogue](../generators.md#main-root-entity-scope).

The other side of a key is a collection that names it. `Invoice.lines` is a `List<InvoiceLine>` declared `relationshipType = ONE_TO_MANY` with `mappedBy = "invoiceId"`, the field of `InvoiceLine` that holds the key. Its rows are the `invoice_lines` rows: `invoices` has no column for it, the repository neither writes nor reads it, the handler sets it to `null` after decoding a request body, and an invoice the routes answer with never holds lines. The lines of one invoice are read from the child's list route, `GET /invoice-lines?invoiceId={id}`, which the OpenAPI description of the field names. Its `cascadeDelete = true` sets `ON DELETE CASCADE` on the child's constraint above, so deleting an invoice deletes its lines.

A `List` of entities that no `mappedBy` ties to a key of the target (no `@Relationship`, a `ONE_TO_MANY` without `mappedBy`, a `MANY_TO_MANY`, or a `mappedBy` that names no `MANY_TO_ONE` back) is refused with `EXT-GEN-3003`. A `MANY_TO_MANY` held as `List<UUID>` keys is a JSON column of keys: no join table is generated for it, and its `cascadeDelete` has no effect.

### 3. Scope the rows to a tenant

All three entities declare `dataScope = ExerisDomain.DataScope.TENANT` and mark their owning-tenant field with `@TenantId`. That adds a `tenant_id UUID NOT NULL` column, row-level security on the table, and a second migration that replaces the policy.

Generated: `src/main/generated/java/db/migration/V2969369__create_customers.sql`, the row-level security statements (quoted).

```sql
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
```

The repository fills a missing `tenantId` with the acting tenant on insert, and refuses a row naming another tenant with `CustomerTenantMismatchException`, answered 400. The tiers are defined by the SDK's `DataScope`; the mismatch refusal is [ADR-090](../adr/ADR-090-reject-mismatched-tenant.md).

### 4. Turn on audit times

`Invoice` declares `audited = true` with `@AuditCreatedAt` and `@AuditUpdatedAt` fields. The table gains the audit columns, and the repository stamps both times on insert and `updatedAt` on update.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/invoicing/repository/InvoiceRepository.java`, method `save` (quoted, the first four statements).

```java
if (entity.getId() == null) entity.setId(UUID.randomUUID());
Instant now = Instant.now();
entity.setCreatedAt(now);
entity.setUpdatedAt(now);
```

The time comes from the server whatever the request body carries. The migration also declares `created_by` and `updated_by` columns. They are yours to fill: generated code has no principal to record, so it never sets an actor, and the [codegen-java module page](../modules/04-codegen-java.md#the-generated-application) says how a service of yours writes one.

A marker switches no role on by itself. All three entities mark a `@Version` field without `versioned = true`, and `Customer` marks an `@AuditCreatedAt` field without `audited = true`. Each such field is an ordinary column: the migration declares it from the field's type (`version BIGINT`, and `Customer`'s `created_at TIMESTAMPTZ`), and the repository writes the entity's value into it, with no optimistic lock and no stamp. Declare the flag with the marker when you want the role.

### 5. Open one action to anonymous callers

`Invoice.viewShared` declares `@RouteAccess(RouteAccess.Level.PUBLIC)`. The generated route policy answers that one route with `permitAll()` and abstains on every other.

Generated: `src/main/generated/java/eu/exeris/sdk/examples/app/GeneratedRoutePolicy.java`, the requirement constants and the action rows (adapted: the other rows of the table removed).

```java
private static final RouteRequirement ABSTAIN = RouteRequirement.abstain();

private static final RouteRequirement INVOICE_VIEW_SHARED_ACCESS = RouteRequirement.permitAll();

row("/invoices/{id}/actions/issue", ABSTAIN),
row("/invoices/{id}/actions/view-shared", INVOICE_VIEW_SHARED_ACCESS),
```

An abstention hands the decision to the application's own policy, then to `unmatchedRoutes()`, which permits by default. So without an application policy every route of this build is public, not only `view-shared`. How the policy is composed is on the [codegen-java page](../modules/04-codegen-java.md#the-generated-application) and in [ADR-105](../adr/ADR-105-generated-route-policy-emission.md).

The action's `@QueryParam("token")` parameter is read from the query string, and a request without it is answered 400.

The `issue` action reads `dueInDays` from the request body and declares `defaultValue = "30"` for it. The generated request record is `IssueRequest(Integer dueInDays)`, boxed so that an omitted member is told apart from an explicit `0`, and its compact constructor sets `30` when the body omits the member or sends `null`.

## Verify

`SdkExamplesE2ETest` builds these three entities into the same application as the other scenarios, so their repositories compile against each other's exceptions and the emitted `InvoiceRepositoryTest`, `CustomerRepositoryTest` and handler tests pass. The emitted tests run on the recording persistence double, not on a database, so they do not exercise the constraints of step 2; a test against PostgreSQL with the generated migrations applied does.

## Not available today

- **`@Action(httpMethod = "GET")`.** `viewShared` declares it, and the route is `POST /invoices/{id}/actions/view-shared`: every action is served on `POST`. Under `-Aexeris.strict=true` the processor reports the attribute as `EXT-PROC-1201`.

## Where this does not apply

- **Edges in a graph.** A reference between rows is a foreign key; a projection of it into a graph store is [guide 04](04-project-into-a-graph.md).
- **`UNIVERSE` scope and shared-scope rows.** The corpus declares none; their migrations and refusals are in the [generator catalogue](../generators.md) and the [diagnostics reference](../diagnostics.md).
- **Trade-off you accept.** Holding references as keys keeps every row one table and every constraint the database's, and gives up navigation: the generated `Invoice` carries `customerId`, and loading the customer is a second call your code makes. In the other direction, `lines` is always `null`, and loading the lines is a call to the child's list route.
