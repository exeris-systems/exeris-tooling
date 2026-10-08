---
title: "0.10.0 migration step: The composed Application holds its conductor in an unnamed resource"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### The composed Application holds its conductor in an unnamed resource

Regeneration rewrites `Application.java` of a composed build. Its boot block declared the
`CompositionConductor` as a named try-with-resources variable that the body never used, so
`javac -Xlint:try` reported `compiler.warn.try.resource.not.referenced` and a consumer building with
`-Xlint:try -Werror` could not compile the application. The resource is now the unnamed variable
`_`, which `-Xlint:try` does not report.

The emitted statement changes from `try (CompositionConductor conductor = ...)` to
`try (CompositionConductor _ = ...)`. The conductor still starts before the lifecycle runs and
closes after it returns; there is no behaviour change. No public Java API changes, and generated
TypeScript is unchanged.

**If you build with `-Xlint:try -Werror`,** regenerate; the warning no longer appears.
