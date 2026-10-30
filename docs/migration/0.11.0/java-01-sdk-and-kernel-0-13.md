---
title: "0.11.0 migration step: exeris-app-bom pins SDK 0.13.0 and kernel 0.13.0"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-29
---

### `exeris-app-bom` pins SDK 0.13.0 and kernel 0.13.0

`exeris.sdk.version` and `exeris.kernel.version` in `exeris-app-bom` are `0.13.0`, the versions this
release's suites ran against. An application on `exeris-app-parent` or importing the BOM moves to
both on its next build.

After regenerating, every `exeris-metadata/<entity>.json` writes both `inCreate` and `inUpdate`
on every field, a `false` included, and its `schemaVersion` is `0.13.0`. The generated code does not
change for either: both loaders already read an absent flag as `false`.

The SDK's `pluralName()` counts an uppercase vowel before a trailing `y` as a vowel, so an entity
named like `Ay` without an explicit `path` is served under `/ays`, where it was `/aies`. The
generated frontend follows it.

Kernel 0.13.0 asks an `HttpRoutePolicy` about a request's path without its query string, and about
a `HEAD` request as the `GET` route that serves it. An application that binds its own policy and
matched on a query string, or on `HEAD`, adjusts that policy.

Kernel 0.13.0 refuses an event published under a tenant's bound storage context unless that tenant's
isolation key is registered with the event engine (`EX-EVENT-6009`, reason
`unregistered-isolation-key`). The generated publishers publish under the request's context, so an
application with a `TENANT` entity that publishes events registers every tenant key before the first
request is served, with an ordinal it keeps for the life of the deployment, for example in its
`RuntimeComponents` subclass, which runs inside the boot scope:

```java
@Override
public HttpHandler decorate(HttpRouter router) {
    IsolationKeyRegistry isolation = KernelProviders.eventEngine().isolationRegistry();
    isolation.register(IsolationKeySpec.of(tenantKey, ordinal));
    return super.decorate(router);
}
```

Without it, a generated `@Action` that publishes answers 500 for every tenant-bound request, and a
streaming one sends a `stream-error` frame. The kernel's `MIGRATION.md` (0.12.x → 0.13) has the full
step.
