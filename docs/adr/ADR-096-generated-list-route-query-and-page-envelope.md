---
title: "ADR-096 — The generated list route pages, sorts and filters on the server, and answers a page envelope"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-096
---

# ADR-096 — The generated list route pages, sorts and filters on the server, and answers a page envelope

- **Status:** ACCEPTED (2026-10-04) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058)
- **Deciders:** the founder (server-side paging in 0.9.0; parameter strictness; system fields;
  size bounds)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen (emitted artefact contract, Java and TypeScript emitters)
- **Visibility:** public
- **Milestone:** 0.9.0, before the first Maven Central release (`ROADMAP.md`, 0.9.0 scope item 4b)
- **Relates to:** ADR-015, ADR-036, ADR-058, ADR-076, ADR-079, ADR-090, ADR-092
- **Supersedes / superseded by:** —

## Context and Problem Statement

The generated list route `GET {base}` read no query parameter and answered every row of the entity
as a JSON array: `KernelHandlerGenerator.handleGetAll` called `service.findAll()`, and
`KernelRepositoryGenerator.buildFindAll` was a `SELECT` with the soft-delete predicate and nothing
else. The OpenAPI list operation declared no parameter.

The emitted front was written against a paged contract. `service-gen.ts` `findAll(pageRequest,
filter)` sends `page`, `size`, `sort=<field>,<asc|desc>` and one parameter per filter key, and types
the response `Page<T>` with the members `content`, `totalElements`, `totalPages`, `size`, `number`,
`first`, `last`. `store-gen.ts` reads `response.content`. The generated Java client sent `page` and
`size` and decoded an array.

The response shape is part of what a consumer builds against. Changing it from an array to an
envelope is a breaking change for every caller of the route, and it is cheaper before the first
published release than after it.

**The question this ADR answers:** what the generated list route reads from a request, what it
refuses, which properties it sorts and filters on, how it reaches the database, and what it answers.

## 🏁 The Decision

**`GET {base}` reads `page`, `size`, `sort` and one equality filter per filter property, refuses
everything else with `400`, runs one bound, whitelisted page query and one count, and answers
`{content, totalElements, totalPages, size, number, first, last}`.**

**Concrete obligations:**

1. **Server-side paging, and the envelope.** The handler answers `200` with a per-entity
   `<Entity>Page` record whose components are, in order, `content` (the rows of the page),
   `totalElements` (`long`, the rows the query matched), `totalPages` (`int`, the pages of `size`
   those rows fill, `0` when none matched), `size`, `number` (zero-based), `first` and `last`. With
   no row matched, the page is both `first` and `last`.
2. **The query grammar.** `page` is a zero-based integer, default `0`. `size` is an integer, default
   `20`. `sort` is `<property>,asc` or `<property>,desc`; a bare `<property>` sorts ascending and the
   direction is case-insensitive; one property per request. A filter is `<property>=<value>`,
   matched by equality: a boolean is exactly `true` or `false`, an enum is a constant's name, a
   `LocalDate` is ISO `yyyy-MM-dd`, a UUID is its canonical text. Every `MANY_TO_ONE` relationship
   adds `<base>Id=<uuid>`, where `<base>` is the relationship name with a trailing `Id` removed.
   Names and values are percent-decoded as `application/x-www-form-urlencoded`.
3. **Strictness.** The route answers `400` with no body, before the service is reached, for: an
   unknown parameter name; a parameter given twice; a sort property outside the whitelist, or a
   direction other than `asc` or `desc`; a filter value that does not parse as its property's type;
   a negative page; a size below `1` or above `100`; malformed percent-encoding. An out-of-range
   size is refused, not clamped. `search` is not a parameter of this route.
4. **Size bounds.** Default `20`, maximum `100`. The emitted `<Entity>ListQuery` publishes them as
   `DEFAULT_SIZE` and `MAX_SIZE`.
5. **Which properties.**
   - Sortable: a field with `@Field(sortable = true)`, and a field without `@Field`, which the
     processor records as sortable. A `List` field is not sortable.
   - Filterable: a field with `@Field(filterable = true)`, and a field without `@Field`, whose type
     is a UUID, `String`, `long`, `int`, `boolean`, `double`, `BigDecimal`, `LocalDate` (either
     spelling, boxed or not) or an enum; and the `MANY_TO_ONE` foreign keys of obligation 2. A field
     named `page`, `size` or `sort` is not a filter.
   - Never a sort key or a filter, with or without `@Field`: the primary key, the owning tenant,
     the shared-scope field, the audit fields (created and updated at and by), the version and the
     soft-delete fields — under their declared `SystemFieldsMetadata` names or the canonical
     defaults, for each role the entity's `dataScope`, `audited`, `versioned` and `softDelete`
     switch on, plus every name `SystemFieldsMetadata` declares for a role. The owner and the
     shared-scope field are resolved by `DataScopeSupport`, the resolution ADR-090's read-only set
     uses.
   - One table decides this for every Java artefact: `ListQuerySupport.sortable` and
     `ListQuerySupport.filters`.
6. **The SQL.** `<Entity>Repository.findPage(<Entity>ListQuery)` runs, in one `executor.query`, a
   `SELECT COUNT(*)` and a `SELECT … ORDER BY <column> <ASC|DESC>, id LIMIT ? OFFSET ?` over the same
   `WHERE`. The `WHERE` is the soft-delete predicate `findAll` applies, then one fixed
   `<column> = ?` per non-null filter, in a fixed order. The sort column comes from a `switch` over
   the whitelisted property names; no request text is placed in the statement. Every filter value,
   the limit and the offset are bound, each filter value through the bind its column's writes use.
   Without `sort`, rows come in `id` order. The primary key is the last `ORDER BY` key in every case.
7. **Tenant and shared-scope visibility.** `findPage` binds no tenant and no shared scope. What the
   list returns is what the session's row-level-security policy shows, exactly as for `findAll` and
   `findById`. The handler's tenant guard runs before the query is parsed, as on every route of a
   tenant-partitioned entity.
8. **Placement of the types.** `<Entity>ListQuery` (the parsed query, with `parse(String)`,
   `of(int, int)`, `toQueryString()` and a nested `Filter` record of one nullable component per
   filter) and `<Entity>Page` are emitted per entity into the generated repository package, by
   `KernelListQueryGenerator` (`ArtifactType.LIST_QUERY`). They use JDK types only. The generated
   client sends `<Entity>ListQuery.toQueryString()` and decodes `<Entity>Page`; its unpaged
   `findAll()` is removed.
9. **The cross-build pin.** `exeris-e2e-tests/src/test/resources/contract/list-query.json` states the
   parameter names, defaults and bounds, the reserved names, the filterable scalar types, the
   property rules and the envelope. The Java build tests against it; the TypeScript emitter's tests
   read the same file. Changing the contract means changing that file and both emitters together.
10. **The OpenAPI list operation** declares `page`, `size` (with its default and maximum), `sort` (an
    enum of `<property>,asc` and `<property>,desc` for the sortable properties, absent when there is
    none) and one typed query parameter per filter; it answers `200` with the `<Entity>Page` schema
    and declares `400` and `500` (ADR-079, Amendment 1).

## Consequences

### ✅ Positive Outcomes

- **[+] A list scales with the page, not the table.** One request reads at most `100` rows and one
  count.
- **[+] The front and the server state one contract,** pinned in one file both builds read.
- **[+] A mistyped parameter is an error.** An unknown name answers `400` instead of an unfiltered
  result that looks correct.
- **[+] Paging is stable.** The `id` tiebreak keeps rows that tie on the sort column in one order
  across pages.
- **[+] No request text reaches SQL.** Sort goes through a fixed column table; every value is bound.

### ⚠️ Trade-offs

- **[-] Breaking for every caller of the list route.** The array becomes an envelope, a request
  without `size` gets twenty rows, and the generated client's `findAll()` is gone.
  `docs/MIGRATION-0.x-to-1.0.md` carries the consumer steps.
- **[-] A field without `@Field` widens the query surface.** The processor records it sortable and
  filterable, so an ordinary field becomes a parameter without an annotation saying so. System
  fields and the primary key are excluded by obligation 5; nothing else is.
- **[-] Reads rely on row-level security alone.** ADR-090 refuses a foreign tenant on write because
  RLS is not always in force (a `SUPERUSER` or `BYPASSRLS` role, an engine without RLS). The list
  route keeps the read posture of `findAll` and `findById`: with no policy in force it returns other
  tenants' rows. This is unchanged by this ADR and applies to every generated read.
- **[-] `NULL` ordering is the engine's.** PostgreSQL sorts `NULL` last ascending and first
  descending; another engine may differ. The route does not normalise it.
- **[-] Two statements per request.** The count and the page are separate queries on one connection;
  under read-committed isolation a concurrent write can make them disagree by the rows it touched.

### 📋 What is NOT in scope

- **Search.** `search` is refused as an unknown parameter until a server-side search exists.
- **Multi-property sort.** One `sort` per request.
- **Range filters** for dates, instants and numbers. `Instant` and `LocalDateTime` are sortable but
  not filterable until then.
- **The TypeScript half** — the list sending its page, sort and filters to the route, and
  related-record panels fetching `?<base>Id=<id>` — is an `exeris-codegen-ts` change classified under
  ADR-092.

### 🚫 Non-Goals

- **Spring Data compatibility.** The envelope's member names match what the emitted front reads; no
  Spring Data type or behaviour is emitted or implied.
- **A configurable page-size bound.** `100` is emitted; changing it is a change to this ADR.

### ⚠️ Risks and Assumptions

- **Assumes:** the front sends only the parameters of obligation 2, and a filter only for a property
  of a filterable type.
- **Assumes:** `<Entity>Page`'s members decode with the kernel's JSON codec (records, component
  names) on the client as they encode on the server.
- **Risk:** a consumer's hand-written client of `GET {base}` sends a parameter the route does not
  read and starts receiving `400`.
- **Reversed by:** a need for cursor-based paging, a server-side search, or a query language richer
  than equality filters — any of which would supersede obligations 2, 3 and 6.

## Cross-references

- **ADR-015** — the Java emission strategy; the generated Java half of this change is classified
  breaking against it.
- **ADR-036** — the status mapping for request decoding; a malformed query is the caller's fault, the
  same class ADR-036 §2 answers with `4xx`, and is answered `400` here without a decoder involved.
- **ADR-058** — the generated-test channel; `<Entity>HandlerTest` asserts the page, sort, filter and
  refusals, and the service and repository tests cover `findPage`.
- **ADR-076** — typed write rejections; the list route adds a caller-fault `400` and no row-level
  `404`.
- **ADR-079** — per-operation response sets; Amendment 1 records the list operation's `400`.
- **ADR-090** — the server-owned owner and shared-scope fields; obligation 5 excludes the same fields,
  and the read posture of obligation 7 is its counterpart on reads.
- **ADR-092** — the TypeScript output contract; the front's half is classified under it.

## Engineering Protocol

1. **`ListQuerySupport`** (`exeris-codegen-java`, `support`) holds the parameter names, the bounds,
   the envelope and the sortable, filterable and system-field rules; `ListQuerySupportTest` pins
   obligation 5.
2. **`KernelListQueryGeneratorTest`, `KernelRepositoryGeneratorTest`, `KernelHandlerGeneratorTest`,
   `KernelClientGeneratorTest`, `KernelServiceGeneratorTest`, `OpenApiPathsBuilderTest` and
   `OpenApiComponentsBuilderTest`** pin the emitted shape of obligations 1–4, 6, 8 and 10.
3. **`ListQueryContractE2ETest`** builds an application through the processor and the pipeline,
   runs the emitted `<Entity>ListQuery`, and holds it, `<Entity>Page`, the OpenAPI document and
   `ListQuerySupport` to `contract/list-query.json` (obligation 9).
4. **`ListRouteBootE2ETest`** boots an emitted application on the kernel with H2 in PostgreSQL mode
   and drives paging, sorting, filtering, percent-decoding, a quoted value and the refusals over a
   socket; the body decodes into `<Entity>Page`.
5. **`KernelCodegenCompileTest`** compiles every filter bind kind and the sort-column table against
   the kernel SPI; **`GeneratedTestsE2ETest`** runs the emitted list-route tests.
6. **`docs/MIGRATION-0.x-to-1.0.md`** carries the consumer-facing change for 0.9.0.
7. Migration owner: `exeris-tooling`, target 0.9.0.
