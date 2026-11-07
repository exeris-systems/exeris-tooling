---
title: "Contributing to Exeris Tooling"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Contributing to Exeris Tooling

This document describes how to build, test, and contribute to `exeris-tooling`, the build-time
code-generation pipeline of the Exeris platform.

## Build & test

```bash
# Full reactor build and verification
mvn clean install

# Compile gate: verifies emitted code compiles against kernel SPI via JavaCompiler
mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenCompileTest -Dsurefire.failIfNoSpecifiedTests=false

# E2E snapshot suite: asserts exact emitted code structures
mvn -pl exeris-e2e-tests -am test -Dtest=KernelCodegenE2ETest -Dsurefire.failIfNoSpecifiedTests=false

# Standalone TypeScript emitter build and test suite
cd exeris-codegen-ts && npm install && npm test
```

Checkstyle runs at `validate` and PMD at `verify`, over main sources, and fail the build in the
six Java modules (`exeris-diagnostics`, `exeris-codegen-core`, `exeris-codegen-maven-plugin`,
`exeris-codegen-java`, `exeris-processor`, `exeris-initializr`).

**JDK 25 LTS is the baseline** across the reactor (`maven.compiler.release=25`, per kernel ADR-066 / SDK ADR-069).
Run Maven on a GA JDK 25 or 26; an early-access JDK such as sdkman's 26-ea cannot compile at `--release 25`.
Maven 3.9+ for Java modules; Node 18+ for `exeris-codegen-ts`.

### PostgreSQL suite

The `@Tag("postgres")` classes in `exeris-e2e-tests` need a real PostgreSQL: H2 implements no
row-level security, and reports a foreign-key violation with another SQL state and constraint-name case.
They are the shared-scope access matrix, the field-type matrix, the booted tenant-isolation test and the
booted foreign-key refusal test (422 for a missing referenced row, 409 for a delete of a referenced
one). The default build excludes the tag. The `e2e-postgres` profile selects it and nothing else, and
the `e2e-postgres` CI job runs it on JDK 25. Without `-Dexeris.e2e.postgres.url` the profile fails at
`validate`, and a `postgres` class selected some other way fails rather than skipping.

```bash
docker run --rm -d --name exeris-e2e-pg -p 127.0.0.1:55432:5432 -e POSTGRES_PASSWORD=e2e postgres:16-alpine

mvn -pl exeris-e2e-tests -am verify -Pe2e-postgres \
    -Dexeris.e2e.postgres.url=jdbc:postgresql://127.0.0.1:55432/postgres \
    -Dexeris.e2e.postgres.username=postgres -Dexeris.e2e.postgres.password=e2e

docker stop exeris-e2e-pg
```

The login must be allowed to create schemas and roles. Each class creates its own schema and drops
it afterwards. `SharedScopePostgresE2ETest`, `TenantIsolationPostgresBootE2ETest` and
`ForeignKeyRefusalPostgresBootE2ETest` also create a `NOSUPERUSER NOBYPASSRLS` role to act as and drop
it afterwards; `PostgresTypeMatrixE2ETest` creates no role.

### SDK examples gate

`SdkExamplesE2ETest` runs the processor and the Java pipeline over the SDK's example sources
(`exeris-sdk-examples`, the corpus the SDK guides quote) at tag `v<exeris.sdk.version>`, the SDK version
the build pins. It covers:

- every scenario package at the tag is classified, so a new scenario fails the gate until it is added;
- the processor accepts the domain scenarios (`graph`, `invoicing`, `ordering`, `presentation`) with no
  error or warning and writes one metadata file per entity and per view;
- the Java application and the frontend half are generated from every domain scenario, and every entity
  is served by an emitted handler, service and repository;
- the emitted main and test trees compile with no error or warning under `-Xlint:all`, and the emitted
  tests pass when run against the emitted code and the example entities;
- `capability` is refused by the capability graph: `LedgerCap` requires `AuditTrail`, which no module
  of the scenario provides;
- a second run in a fresh workspace writes the same metadata and emitted trees, file for file.

The examples module is not published, so the test reads it from an `exeris-sdk` checkout:
`-Dexeris.sdk.examples.dir` (or `EXERIS_SDK_EXAMPLES_DIR`) naming a checkout at the tag, else an
`exeris-sdk` clone that has the tag, beside this repository or, from a checkout added with
`git worktree`, beside the main checkout, exported with `git archive` whatever branch its working tree
is on. A named directory that is not a checkout at the tag fails the class. With neither source the
class is skipped, except under `CI=true`, where it fails. The tag is `v` followed by the version of
the `exeris-sdk-annotations` jar on the test classpath.

```bash
mvn -pl exeris-e2e-tests -am verify -Dexeris.sdk.examples.dir=/path/to/exeris-sdk
```

The Angular half reads the metadata the test leaves in `exeris-e2e-tests/target/sdk-examples/`, generates
the app twice through the CLI and requires identical output, then builds it and runs its emitted specs.
The `sdk-examples-app` CI job runs it.

```bash
cd exeris-codegen-ts && npm ci && npm run build
node scripts/gen-sdk-examples-app.mjs \
    ../exeris-e2e-tests/target/sdk-examples/app/target/classes/exeris-metadata /tmp/sdk-examples-app
cd /tmp/sdk-examples-app && npm install && npx ng build --configuration development && npx ng test --watch=false
```

### Javadoc gate

A pull request that changes a Java file under `src/main/java` of `exeris-diagnostics`,
`exeris-processor`, `exeris-codegen-core`, `exeris-codegen-java`, `exeris-codegen-maven-plugin` or
`exeris-initializr` is held to the organisation Javadoc gate ([ADR-085](docs/adr/ADR-085.link.md),
[`javadoc-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/javadoc-conventions.md)
rule 11). The workflow is `.github/workflows/javadoc.yml`. The unit is the file: a changed file
must be clean as a whole, not only on the lines the pull request touched. Javadoc that a generator
emits through `addJavadoc(...)` or a text block is generated output and is changed only together
with the emitted code it documents.

The gate has two halves, and both can be run locally against a checkout of
[`exeris-systems/.github`](https://github.com/exeris-systems/.github) (here `../.github`), from
the repository root, after `mvn install -DskipTests`:

```bash
# Checkstyle: the organisation ruleset, on the engine version the bundle pins
mvn -q -f ../.github/java/checkstyle-engine-pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/cs-cp.txt
java -cp "$(cat /tmp/cs-cp.txt)" com.puppycrawl.tools.checkstyle.Main \
  -c ../.github/java/checkstyle-javadoc.xml exeris-codegen-core/src/main/java

# doclint: javadoc with the flags the gate uses, on the files to check
mvn -q -pl exeris-codegen-core dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
javadoc -Xdoclint:all -quiet --release 25 \
  -tag 'apiNote:a:API Note:' -tag 'implSpec:a:Implementation Requirements:' -tag 'implNote:a:Implementation Note:' \
  -cp "$(cat /tmp/cp.txt):exeris-codegen-core/target/classes" -d /tmp/javadoc-out \
  $(find exeris-codegen-core/src/main/java -name '*.java')
```

Either half reporting a finding fails the gate; a doclint warning counts as a finding.

## `exeris-codegen-ts`

The package README ([`exeris-codegen-ts/README.md`](exeris-codegen-ts/README.md)) is the package's
page on npmjs, so it covers using `exeris-gen` only. Building, testing and releasing the package are
described here.

```bash
cd exeris-codegen-ts
npm install

npm run build              # tsc into dist/
npm run dev -- generate --input path/to/metadata   # run the CLI from source (tsx)
npm test                   # vitest
npm run lint               # eslint
npm run test:coverage      # vitest with coverage; fails on a threshold violation
npm run verify:generated   # generate a sample app and type-check its data layer (no Angular needed)
npm run check:version      # package.json and package-lock.json versions equal the root pom.xml version
```

`node scripts/gen-sample-app.mjs <dir>` writes the complete sample app that CI installs and builds
with `ng build`; `--view-only` writes the backend-less variant, and `--ssg` the backend-less variant
under `render: 'ssg'` with a parameterised page. `EXERIS_UI_KIT_PATH` points the sample
at a local `exeris-sdk-ui-kit` checkout instead of the published UI kit.

The package version is the Maven reactor's version: one `vX.Y.Z` tag releases both, and
`check:version` holds them equal on every commit, `-SNAPSHOT` included.

## Architectural invariants

`exeris-tooling` is published under **Apache-2.0**. Non-negotiable architectural principles maintain
the pipeline's integrity:

1. **Single target: Exeris kernel:** Spring, Quarkus, Micronaut, and Vanilla generators were removed
   in 0.1.0. Spring hosting belongs downstream in `exeris-spring-runtime`.
2. **Build-time only processor:** `exeris-processor` runs inside `javac` and depends only on
   `javax.lang.model`, the `com.sun.source` tree API (for the source digest), the SDK annotations and
   source model, `exeris-diagnostics` and the JDK, with Jackson to write the metadata and
   `@AutoService` to register itself. No runtime framework and no kernel runtime
   ([policy](.agents/policies/processor-build-time-only.md)).
3. **DomainMetadata is the sole contract:** Generators read the processor's JSON metadata into the SDK
   `DomainMetadata` records (`CodegenPipeline.loadMetadata`, `MetadataLoader`); they never inspect
   compiler AST elements directly.
4. **Codegen determinism:** Identical input produces byte-identical output across runs and locales.
   No timestamps, random UUIDs, or uncontrolled iteration orders in emitted text.
5. **Committed generated code:** Emitted code lands in `src/main/generated/` and supports the detachment
   lifecycle (`exeris:detach`). Never assume "always regenerate".
6. **Consumer-build contracts:** Emitted code logs through `System.Logger` with `{0}` placeholders
   (ADR-060); emitted tests in `src/test/generated/java` import only JUnit 5 + AssertJ (ADR-058);
   emitted integration tests in `src/test/generated-it/java` import JUnit 5, AssertJ and the JDK,
   and need H2 at test runtime (ADR-058 Amendment 1); `CapTierWall` uses dependency-free
   Class-File API (ADR-055).

## Contributor terms & DCO sign-off

External contributions require a Developer Certificate of Origin sign-off via the `Signed-off-by:`
trailer (`git commit -s`), per [ADR-085 §K](docs/adr/ADR-085.link.md).

The sign-off certifies that you have the right to submit the contribution under the Apache-2.0 licence
published in `LICENSE`. Organisation members are exempt from the trailer, but remain accountable for
every merged change.

## AI provenance

Exeris is developed with AI assistance and states the terms openly per
[`ai-provenance.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/ai-provenance.md):

- **Provenance is kept:** An AI-assisted commit carries `Co-authored-by: <model name> <noreply@...>`
  (or equivalent). Stripping it is a defect; adding it where no AI was involved is prohibited.
- **A named human is accountable for every line:** The PR author must be able to explain and defend any part
  of the change in review. "The agent produced it" is never an acceptable explanation.
- **Agents do not open pull requests, file issues or post comments unattended:** Automated review comments
  are allowed; automated contributions without human review are not.
- **Verification is stated, not assumed:** PR descriptions must name the exact test commands executed.
- **Reject hollow tests:** AI-generated tests that assert nothing observable or only restate mocks are rejected.

## Conventions

Development standards are binding per [ADR-085](docs/adr/ADR-085.link.md) and hosted in
[`exeris-docs/standards/`](https://github.com/exeris-systems/exeris-docs/tree/main/standards):

- [`commit-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/commit-conventions.md) — Conventional Commits with Netty-form body (`Motivation:`, `Modification:`, `Result:`).
- [`pr-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/pr-conventions.md) — structured PR bodies and scope classification.
- [`javadoc-conventions.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/javadoc-conventions.md) — Oracle doc-comment standards.
- [`docs-style-guide.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/docs-style-guide.md) — validated frontmatter and naming conventions.
- [`agents-md-schema.md`](https://github.com/exeris-systems/exeris-docs/blob/main/standards/agents-md-schema.md) — [`AGENTS.md`](AGENTS.md) as the canonical entry point and [`.agents/`](.agents) as the semantic source.
- A change a consumer sees after regenerating writes its MIGRATION step as one file under
  `docs/migration/<version>/`, never as an edit of `docs/MIGRATION-0.x-to-1.0.md`; the release
  assembles them ([format](docs/migration/README.md)).
- Language: English everywhere (code, identifiers, comments, commit messages, PR titles/bodies, documentation).
