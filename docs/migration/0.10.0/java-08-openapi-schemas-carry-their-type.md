---
title: "0.10.0 migration step: The OpenAPI document gives every schema its type and defines each action's request schema"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### The OpenAPI document gives every schema its type and defines each action's request schema

Regeneration rewrites `openapi/<entity>-api.yaml`. Through 0.9.0 no schema in it carried a `type`:
entity, DTO and page properties, list-query parameters and path parameters were written with their
`format` and constraints only, and a filter on a `String`, enum or `boolean` property came out as
`schema: {}`. Every schema now carries its OpenAPI 3.1 `type`: `object` for the entity, DTO, page
and request schemas, `array` for the page's `content`, and `string`, `integer`, `number` or
`boolean` for each property and query parameter.

An action that declares `@ActionParam` parameters had its request body reference
`#/components/schemas/<Entity><Action>Request`, a schema the document did not define. The document
now defines it: an `object` with one property per parameter, in declaration order, with the
parameter's type and format. No property is listed as `required`, because no emitted handler
checks that a parameter is present.

Generated Java and TypeScript are unchanged. No public Java API changes.

**If you generate a client or validate requests from the document,** regenerate from the new
document. A tool that treated an untyped schema as "any value" now applies the declared type, and a
tool that failed on the unresolved action reference now resolves it.
