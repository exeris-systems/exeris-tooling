---
title: "0.11.0 migration step: generated handler tests for actions, versioning, and soft delete"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-01
---

### Generated handler tests for actions, versioning, and soft delete

When test generation is enabled (`-Dexeris.tests=true`), generated `{E}HandlerTest` suites include test cases covering:
- `@Action` routes: respond-once success (200 OK with updated entity), missing aggregate row (404 Not Found), undecodable request body (400 Bad Request), version conflict on versioned entities (409 Conflict), and missing required query parameter (400 Bad Request).
- Versioned entities: stale update requests (`PUT`) answering 409 Conflict.
- Soft-delete entities: read and update requests on soft-deleted rows answering 404 Not Found, and exclusion of soft-deleted rows from list queries.

Applications with test generation disabled (the default) are unaffected. Applications with test generation enabled will see additional test cases executed in their test phase.
