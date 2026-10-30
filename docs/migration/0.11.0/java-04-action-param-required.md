---
title: "0.11.0 migration step: an @Action method parameter without @ActionParam or @QueryParam is refused"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### A parameter of an `@Action` method without `@ActionParam` or `@QueryParam` is refused

The processor refuses a parameter of an `@Action` method that carries neither `@ActionParam` nor
`@QueryParam`, with the compile error `EXT-PROC-1020` naming the entity, the method and the parameter.
Before, an unannotated parameter was dropped: the generated handler called the method with fewer
arguments, and the application either failed to compile or called another overload with fewer
parameters, without a warning.

Annotate every parameter of an `@Action` method with `@ActionParam(label = "...")` to bind it from the
request body, or with `@QueryParam("key")` to bind it from the request query string. A zero-argument
`@Action` that delegated to an unannotated overload to work around the drop can call it with
annotated parameters instead.
