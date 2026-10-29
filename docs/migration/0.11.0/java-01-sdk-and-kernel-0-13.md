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
