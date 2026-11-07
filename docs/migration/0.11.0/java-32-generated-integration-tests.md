---
title: "0.11.0 migration step: generated integration tests, opt-in"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-07
---

### Generated integration tests, opt-in

`exeris:generate` takes `-Dexeris.integrationTests=true` (`--integration-tests` with
`--integration-test-output-dir` on the command line) and writes integration tests into their own
root, `src/test/generated-it/java` (`exeris.integrationTestOutputDir`), with their own manifest.
They boot the generated `Application` once per test JVM on an in-memory H2 database in PostgreSQL
mode, apply the generated migrations to it, and call the routes over HTTP: per entity
`<Entity>CrudIT` (the CRUD round trip, `404`, `400`, the stale-version `409`, the foreign-key `422`
and `409`, the `@Validation` boundaries), and `GeneratedRoutePolicyIT` when a route demands an
identity (`401`, `403`, admission), with tokens verified against a key set the test serves on a
loopback port.

Nothing changes for an application that leaves the flag off, which is the default. To turn it on:

- Add `com.h2database:h2` at `test` scope beside JUnit 5 and AssertJ. `exeris-app-bom` manages its
  version. `exeris:generate` warns with `EXT-PLUG-2004` when it is missing.
- Run them with Failsafe: `exeris-app-parent` binds `maven-failsafe-plugin` (execution
  `exeris-integration-tests`). An application on README route 2 copies the plugin from the build
  block. `mvn test` does not run them; `mvn verify` does.
- Keep the generated migrations on the test classpath, as `exeris-app-parent`'s `<resource>` entry
  does.
- Add `src/test/generated-it/** text eol=lf` to `.gitattributes` beside the other generated trees.

They are written only for an application whose entities are all `GLOBAL` and that has no graph
entity, no `@Blob` field and no `@CapabilityModule`. Any other application gets the warning
`EXT-GEN-3105`, naming the reason, and its integration-test root is left as it was.
`-Dexeris.it.application=<class>` boots a subclass of the generated `Application` in its place.
