---
title: "0.11.0 migration step: the list route reads search over the searchable String fields"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### The list route reads `search` over the searchable String fields

`GET {base}` reads one more query parameter, `search=<term>` (ADR-096 Amendment 4). A row matches
when any searchable column contains the term, compared case-insensitively, and the term combines
with the filters, the sort and the paging: `totalElements` counts the rows that match both. A term
is stripped of surrounding whitespace, a blank one is no search, and `%`, `_` and `\` in it match
only themselves.

A field is searchable when its type is `String` and it carries `@Field(searchable = true)`, or no
`@Field` at all, which the processor records as searchable. A system field is never searchable. An
entity with no searchable field answers `400` to a non-blank `search`, and every entity answers
`400` to a term longer than 100 characters or to `search` given twice.

A regenerated `<Entity>ListQuery` gains a last component `String search`, and the constants
`SEARCHABLE` and `MAX_SEARCH_LENGTH`. Its constructor without `search` remains, so code that builds
a query with five arguments compiles unchanged. `toQueryString()` writes the term, so the generated
client sends it. The generated repository's `findPage` appends the match to its `WHERE` and the
OpenAPI list operation declares `search` when the entity has a searchable field. The generated
handler and repository tests gain a search case or the refusal of one.

**What breaks.**

- A record pattern or deconstruction of `<Entity>ListQuery` names five components and no longer
  matches. Add the sixth, `String search`.
- `search` is reserved, like `page`, `size` and `sort`. An entity with a filterable field named
  `search` loses that equality filter: `?search=x` is now the text search, and the field's
  component leaves `<Entity>ListQuery.Filter`. Rename the field to keep filtering on it by equality.
- A request to an entity with nothing searchable that sent `search` was already refused as an
  unknown parameter and is still refused with `400`.

**Indexing.** The generated migration adds no index for the match: a substring match cannot use
the btree index a searchable column already has. On PostgreSQL, an application with enough rows to
need one adds a trigram index on the expression the predicate uses, in a migration of its own:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_customers_name_trgm ON customers USING gin (lower(name) gin_trgm_ops);
```

**Where it applies.** To every entity: each `<Entity>ListQuery` and repository changes. The
OpenAPI document changes only for an entity with a searchable field.
