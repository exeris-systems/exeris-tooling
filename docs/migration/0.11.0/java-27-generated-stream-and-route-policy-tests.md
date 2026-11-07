---
title: "0.11.0 migration step: generated tests for the stream handlers and the route policy"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### Generated tests for the stream handlers and the route policy

When test generation is enabled (`-Dexeris.tests=true`), the regenerated test tree gains:

- In `{E}HandlerTest` of an entity with a stream handler, cases for the live view (`GET {base}/stream`), the spectate stream (`GET {base}/{id}/stream`) and each streaming action: the `stream-error` frame of a malformed id, an absent row, an undecodable body and a refused write; the frames of the events each stream forwards, and the spectate stream's filter on the row id; and the stream closed once with its subscriptions dropped.
- `ApplicationRoutePolicyTest` in the base package, beside `Application`: `routePolicy()` hands every policy it folds the path without its query string and a `HEAD` request as `GET`, `applicationPolicy()` answers ahead of the generated policy, and `unmatchedRoutes()` answers a route no policy describes, `permitAll()` by default. When an entity declares access, a further case asserts the generated policy answers that entity's routes through the fold.
- `RecordingStreamExchange` in the `testsupport` package, the stream exchange double these cases drive.

`RecordingEventEngine` in the `testsupport` package delivers a publish to the subscribers of the event's registered type, on the publishing thread, where it only recorded it, and returns a subscription token from `subscribe`, where it returned `null`. A test of your own that subscribes on it and publishes on it receives the event. No step is needed.

Applications with test generation disabled (the default) are unaffected. The new cases import only JUnit 5, AssertJ and the kernel SPI.
