---
title: "0.11.0 migration step: a @QueryParam action parameter is read from the query string"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### A `@QueryParam` action parameter is read from the query string

An `@Action` method parameter carrying `@QueryParam` is bound from the HTTP request query string.
The generated action handler decodes the query parameter under its effective query key (the declared
`value` or parameter name when unset) and converts it to its declared type.

Missing required query parameters or malformed values reject the request with HTTP `400 Bad Request`,
honouring `required` and `defaultValue`. An action whose parameters all carry `@QueryParam` decodes no
request body and emits no request DTO record. In the emitted OpenAPI specification, query parameters
are declared under `parameters` with `in: query`.
