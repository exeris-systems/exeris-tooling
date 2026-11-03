---
title: Initializr
type: module
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Initializr

**Module:** `eu.exeris:exeris-initializr`

`exeris-initializr` writes a new application on the starter, as a directory or a ZIP archive, from one runnable jar. A backend it writes is a Maven project whose parent is `exeris-app-parent` and which depends on `exeris-app-starter`, with one `@ExerisDomain` entity in `<package>.domain`; the project's own build generates everything else.

It writes a project once. It does not regenerate, upgrade or inspect an existing project, and it writes no Angular source: a frontend is an npm project that runs `@exeris/codegen-ts` on the backend's metadata.

## Coordinates and dependencies

| Item | Value |
|---|---|
| Group and artifact | `eu.exeris:exeris-initializr` |
| Published | Yes, to Maven Central |
| Bundled | Its one dependency, `exeris-diagnostics`, shaded into the jar |
| Manifest | `Main-Class: eu.exeris.tooling.initializr.cli.InitializrCliMain`, with `Implementation-Version` |
| Versions it writes | `META-INF/exeris/initializr/versions.properties`, filled by its build: the tooling version (the parent's and the npm generator's), and the H2, PostgreSQL driver, JUnit and AssertJ versions |

## Contract

```text
java -jar exeris-initializr-<version>.jar [--switch=value ...]
```

| Switch | Default |
|---|---|
| `--topology=backend-only\|e2e` | `backend-only` |
| `--group-id=<groupId>` | `com.example` |
| `--artifact-id=<artifactId>` | `demo` |
| `--package=<package>` | derived from the group and artifact ids |
| `--name=<text>` | the artifact id |
| `--database=postgresql\|h2` | `postgresql` |
| `--entity=<TypeName>` | `Item` |
| `--output-dir=<dir>` | `./<artifactId>` |
| `--zip=<file>` | write a ZIP archive instead of a directory |
| `--force` | write into a non-empty directory or over an existing archive |
| `--version`, `--help` | print and exit |

An unknown switch, a positional argument, a switch given twice, a value switch without its value, a flag with one, and a value the request does not accept are argument errors. `--output-dir` and `--zip` exclude each other.

| Topology | Writes |
|---|---|
| `backend-only` | `pom.xml`, `first-build.sh`, the entity, `README.md`, `.gitignore`, `.gitattributes`; with PostgreSQL, `docker-compose.yml` |
| `e2e` | The backend under `backend/`; under `frontend/`, a `package.json` that depends on `@exeris/codegen-ts` at the initializr's version and runs `exeris-gen` on the backend's metadata |

The POM declares only what the starter leaves to the application: the JDBC driver at runtime scope, and JUnit 5 and AssertJ at test scope, with `exeris.tests=true`. `first-build.sh` runs the two-pass first build, `mvn -Dexeris.codegen.skip=true compile` then `mvn verify`. The tree is a function of the request and the versions: the same input gives the same paths, order and bytes.

## Hot path

None: build-time. One process per project written.

## Failure modes

The command line exits `0` on success, `1` when the write was refused or failed, and `2` when the arguments were invalid, and prints an identifier on every non-zero exit.

| Situation | Exit | Identifier |
|---|---|---|
| Invalid arguments, or a value the request rejects (an unknown topology or database, an invalid group id, artifact id, package or entity name) | `2` | `EXT-INIT-5001`, followed by the usage text |
| The output directory is not empty, or the archive exists, without `--force` | `1` | `EXT-INIT-5002`; nothing is written |
| Writing the project fails | `1` | `EXT-INIT-5003`, with the cause |

What to do about each is in the [diagnostics reference](../diagnostics.md). Under `--force`, files at the same paths are replaced and every other file is left in place.

Trade-off: the database is a scaffold default the application owns from its first commit. H2 builds the project and runs its generated tests with no server, but it cannot run the application, because the generated migrations are PostgreSQL SQL; the README of an H2 project says so.

## Owning ADRs

| ADR | Decision |
|---|---|
| [ADR-091](../adr/ADR-091-opt-in-application-starter.md) | Amendment 5: the initializr, its topologies, versions and command line; Amendment 6: a new project pins its generated trees to LF |
| [ADR-095](../adr/ADR-095-stable-diagnostic-identifiers.md) | Amendment 2: the `INIT` area |

## Where this does not apply

- **A frontend with no backend of its own.** ADR-091 Amendment 1's shape 2 is not written; it waits on a way for a backend without `@CapabilityModule` to publish its contract artifact.
- **Capabilities.** Neither the initializr nor a project it writes declares a `@CapabilityModule`; an application adds one itself.
- **An existing project.** Moving a project to a new tooling release is a version bump and a regeneration, not an initializr run.
