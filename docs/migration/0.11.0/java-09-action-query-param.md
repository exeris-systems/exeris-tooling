---
title: "0.11.0 migration step: a @QueryParam action parameter is read from the query string"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### A `@QueryParam` action parameter is read from the query string

A parameter of a respond-once `@Action` method that carries `@QueryParam` is bound from the request's
query string, under its query key (the annotation's `value`, else the parameter's name), and
converted to its declared type. A missing required value, or one that does not convert, answers
`400`; `required` and `defaultValue` are honoured. An action whose parameters all carry `@QueryParam`
decodes no request body. The generated OpenAPI document declares each such parameter `in: query`.

Two shapes are refused at compile time:

- a `@QueryParam` parameter whose type has no conversion from text (`EXT-PROC-1022`). Accepted types
  are the primitives and their boxes, `String`, `BigDecimal`, `UUID`, `LocalDate`, `LocalDateTime`,
  `Instant`, `OffsetDateTime` and enums; a collection or another type is bound from the body with
  `@ActionParam` alone;
- a `@QueryParam` parameter on an `@Action(streaming = true)` method (`EXT-PROC-1023`): a streaming
  action reads its parameters from the request body only.
