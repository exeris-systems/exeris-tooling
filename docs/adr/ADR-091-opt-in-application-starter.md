---
title: "ADR-091 — Publish an opt-in application starter, so a consumer does not hand-write its build"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-091
---

# ADR-091 — Publish an opt-in application starter, so a consumer does not hand-write its build

- **Status:** ACCEPTED (2026-09-30) · amended 2026-10-01 (Amendment 1 — backend, frontend and backend + frontend; Amendment 2 — starter scope, publication metadata, starter version)
- **Deciders:** the founder (scope); `exeris-tooling` (module layout)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / build (consumer-build contract)
- **Visibility:** public
- **Milestone:** 0.9.0, published to Maven Central (`ROADMAP.md`, "0.9.0 sequencing", Scope group 3)
- **Relates to:** ADR-078 (narrows its premise; does not supersede it)
- **Supersedes / superseded by:** —

## Context and Problem Statement

`exeris-tooling` emits a generated application's sources, migrations and OpenAPI, and no build file.
Every consumer therefore writes the following by hand:

| What | Scope / binding | Why it is needed |
|---|---|---|
| `eu.exeris:exeris-sdk-annotations` | `provided` | The annotations are `@Retention(SOURCE)` (`exeris-sdk/exeris-sdk-annotations/src/main/java/eu/exeris/sdk/annotation/ExerisDomain.java:72`), so they are needed by javac and the processor, never at run time. |
| `eu.exeris:exeris-kernel-spi`, `exeris-kernel-core` | `compile` | The generated sources import them (`KernelApplicationGenerator.java:548-549` writes this into the emitted `Application` Javadoc). |
| A runtime driver — `eu.exeris:exeris-kernel-community` | `runtime` | Every subsystem provider arrives from it through `ServiceLoader`; Core registers none. ADR-078 gates its absence. |
| A JDBC driver | `runtime` | The application's database. |
| `tools.jackson.core:jackson-databind` (Jackson 3) | `compile`, conditional | A repository for an entity with a `List<X>` field imports `tools.jackson.databind.ObjectMapper`, `tools.jackson.core.JacksonException` and `tools.jackson.core.type.TypeReference` (`KernelRepositoryGenerator.java:124-128`, condition at `:178`). |
| `eu.exeris:exeris-sdk-composition-runtime` | `compile`, conditional | With at least one `@CapabilityModule`, the emitted `Application` imports `eu.exeris.sdk.composition.runtime.CompositionConductor` (`KernelApplicationGenerator.java:160`, `:550-554`). |
| `eu.exeris.tooling:exeris-processor` | `annotationProcessorPaths` | Writes `DomainMetadata` JSON during `compile`. |
| `eu.exeris.tooling:exeris-codegen-maven-plugin` | `generate` at `generate-sources`; `verify-capabilities` and `verify-runtime` at `process-classes` | `GenerateMojo.java:72`, `VerifyCapabilitiesMojo.java:42,46`, `VerifyRuntimeMojo.java:41-47`. A goal nobody binds does nothing (ADR-078, Trade-offs). |
| `release 25`, Maven 3.9 | compiler / enforcer | The reactor's own floor (`pom.xml:107-109`, `pom.xml:179-196`); the plugin loads into Maven's JVM. |
| A `<resource>` for generated migrations and OpenAPI | build | See below. |

The last row is a defect a consumer meets without being told. Every generated file is written under
one root, `${project.basedir}/src/main/generated/java` (`GenerateMojo.java:82`;
`CodegenPipeline.java:656` writes each file at `GeneratedFile.relativePath()`,
`GeneratedFile.java:39-42`). Flyway migrations are emitted into package `db/migration`
(`KernelFlywayGenerator.java:125`, also `KernelSharedScopeMigrationGenerator.java:126`,
`KernelApplicationGenerator.java:350`) and OpenAPI into `openapi`
(`KernelOpenApiGenerator.java:36`). The plugin registers that root as a **compile source root**
(`GenerateMojo.java:220-221`) and as nothing else. Maven compiles `.java` files from a source root
and copies nothing else from it to `target/classes`, so the generated `.sql` and `.yaml` files are not
on the application's classpath unless the consumer adds a `<resource>` entry.

**No POM in the ecosystem declares the whole set.** The fourteen `exeris-caps-*` capability modules
bind `generate` and `verify-capabilities`; none binds `verify-runtime` and none declares
`exeris-kernel-community`. `exeris-e2e-tests` drives the pipeline in-process
(`KernelCodegenE2ETest`, `KernelCodegenCompileTest`, `GeneratedTestsE2ETest` call the pipeline and
`javax.tools.JavaCompiler` directly) and does not depend on `exeris-codegen-maven-plugin`; the plugin
module's own tests are unit tests (`exeris-codegen-maven-plugin/src/test/java/.../*MojoTest.java`)
and it has no `src/it`. **The Maven plugin has never been run end to end by any test.**

**`exeris-tooling-bom` cannot be handed to a consumer.** Its parent is the reactor root
(`exeris-tooling-bom/pom.xml:6-10`); it manages build-internal libraries — JavaPoet (`:75`),
Jackson 2 (`:78`), swagger-parser and swagger-core (`:88`, `:95`), compile-testing (`:104`), H2
(`:113`) — and it manages neither `exeris-codegen-maven-plugin` nor any `pluginManagement`. Importing
it would pin a consumer's Jackson 2 and H2 to tooling's internal choices while leaving the plugin
unmanaged.

ADR-078 lists "emitting a `pom.xml` or a dependency fragment" as out of scope, on the premise that
"tooling does not own the consumer's build".

**The question this ADR answers:** how does a consumer get a correct build for a generated
application — the dependency set, the processor, the three bound goals and the resource wiring —
without tooling emitting a build file?

## 🏁 The Decision

**`exeris-tooling` publishes three opt-in POM modules — `exeris-app-bom`, `exeris-app-parent` and
`exeris-app-starter` — that a consumer may build on. The generators never emit any of them, nor any
other build file.**

**Concrete obligations:**

1. **`exeris-app-bom` (packaging `pom`) names a tested triple.**
   - `dependencyManagement` for `exeris-processor` and the kernel (`exeris-kernel-spi`,
     `exeris-kernel-core`, `exeris-kernel-community`, `exeris-kernel-community-testkit`) and SDK
     (`exeris-sdk-annotations`, `exeris-sdk-composition-runtime`) artefacts, at exactly the versions
     this tooling release was built and tested against — the values of `exeris.kernel.version` and
     `exeris.sdk.version` in `exeris-tooling-bom` (`exeris-tooling-bom/pom.xml:72`, `:44`) at the
     release commit — and Jackson 3 (`tools.jackson.core:jackson-databind`) at the kernel's line,
     the value of `jackson3.version` (`exeris-tooling-bom/pom.xml:87`).
   - `pluginManagement` for `exeris-codegen-maven-plugin` at the tooling version.
   - **Every tooling, kernel and SDK version in it is a literal, or a property whose value is a
     literal** — never `${project.version}` or any expression derived from it. Values that
     `exeris-app-parent` passes on by inheritance are interpolated in the *consumer's* model, where
     `${project.version}` is the consumer's own version. (An imported BOM is interpolated in its own
     context, so the import route would be safe either way; the parent route is not.)
   - `exeris-kernel-community-testkit` is managed for the application's own tests only. The generated
     tests do not import it — `Kernel*TestGenerator` emits imports from `eu.exeris.kernel.spi.*`,
     `org.junit.jupiter.api` and `org.assertj.core.api` alone — so an application declares it only
     to boot a kernel in tests it writes itself.
   - It manages nothing else: no JavaPoet, swagger, Jackson 2, H2, compile-testing, JUnit or
     AssertJ entry. *(Amended: see Amendment 2.)*
   - Release order is kernel → SDK → tooling, so the tooling version is the last of the three to be
     fixed and names the triple. A tooling release whose BOM names a `-SNAPSHOT` kernel or SDK
     version violates this obligation.

2. **`exeris-app-parent` (packaging `pom`, parent `exeris-app-bom`) wires the build.**
   - `maven.compiler.release` 25.
   - `maven-enforcer-plugin`: JDK `[25,)` and Maven `[3.9,)`.
   - `maven-compiler-plugin` with `exeris-processor` on `annotationProcessorPaths`.
   - `exeris-codegen-maven-plugin` with `generate` (`generate-sources`), `verify-capabilities` and
     `verify-runtime` (`process-classes`) bound.
   - A `<resource>` entry that exposes the generated `db/migration` and `openapi` directories under
     the generated root, so migrations and the OpenAPI document reach the classpath.
   - `maven-surefire-plugin` at 3.2.5 or later (the version the reactor uses, `pom.xml:154-155`),
     configured to run JUnit 5, the runner the generated-test channel requires (ADR-058).
   - It blanks `url`, `licenses`, `developers` and `scm` (the `spring-boot-starter-parent`
     precedent), so a consumer's effective POM does not claim Exeris's project metadata as its own. *(Amended: see Amendment 2.)*
   - It inherits from `exeris-app-bom` rather than importing it, because properties of an imported
     BOM do not reach `<build><pluginManagement>` (`pom.xml:111-114` records this for the reactor
     itself); inheritance carries the plugin version.

3. **`exeris-app-starter` (packaging `pom`, parent `exeris-app-bom`) carries the dependency set.**
   - `exeris-sdk-annotations` (`provided`), `exeris-kernel-spi` and `exeris-kernel-core` (`compile`),
     `exeris-kernel-community` (`runtime`), `exeris-sdk-composition-runtime` (`compile`), and
     Jackson 3 `jackson-databind` (`compile`). Jackson 3 and the composition runtime are `compile`
     because emitted **main** code imports them. *(Amended: see Amendment 2.)*
   - `exeris-kernel-community` is the **default** driver, not the only one. An application on another
     driver (for example an enterprise one) excludes it from `exeris-app-starter` and declares its
     own; `exeris:verify-runtime` checks for registered providers, not for a particular artefact, so
     it accepts any driver (ADR-078).
   - It declares **no JDBC driver** and **no logging backend**. Generated code logs through
     `System.Logger` and binds no facade (ADR-060); which backend, if any, routes it, and which
     database driver the application talks to, are the application's choices.
   - Test libraries are not in the starter: the generated tests' contract is JUnit 5 + AssertJ in the
     consumer's `test` scope (ADR-058 §2), declared by the application.

4. **None of the three inherits `exeris-tooling-root` or `exeris-tooling-parent`.** Inheriting
   either would hand a consumer the reactor's enforcer messages, its `pluginManagement`, the JaCoCo
   gate and, through `exeris-tooling-parent`'s import of `exeris-tooling-bom`, every internal pin.
   - `exeris-app-bom` has **no parent**. A publish-only parent above it would itself be inherited by
     every consumer of `exeris-app-parent`, so there is none. It carries the publication metadata
     Central requires (`name`, `description`, `url`, `licenses`, `developers`, `scm`).
   - **A consumer inherits no publishing.** `exeris-app-bom` declares **no `distributionManagement`**,
     and its publishing profile mirrors the reactor root's `release` profile (`pom.xml:215` onward:
     sources, javadoc, `maven-gpg-plugin`, `central-publishing-maven-plugin`) under an id no consumer
     uses, **`exeris-tooling-release`**. `.github/workflows/release.yml` activates it beside `release`
     (today it passes `-P 'release,!github-packages'`, `release.yml:221,223`). A consumer that runs
     its own `-P release` therefore activates nothing from this chain.
   - With that, `central-publishing-maven-plugin` is declared twice in one reactor — in the root's
     `release` profile and in `exeris-tooling-release` — each with its own `excludeArtifacts`.
     `tools/release-readiness` is extended to check both lists.
   - `exeris-app-parent` and `exeris-app-starter` inherit from `exeris-app-bom`. This parentage is the
     module layout `exeris-tooling` chose inside the scope the founder accepted.
   - All three remain **modules of the tooling reactor** and are versioned with it: a release sets
     one version across every module, these three included.
   - None of the three is in `central-publishing-maven-plugin`'s `excludeArtifacts`
     (`pom.xml:312` onward); all three publish.

5. **Two supported ways to consume, both documented in the README quick start.**
   - **As parent:** `<parent>exeris-app-parent</parent>` plus a dependency on `exeris-app-starter`
     with `<type>pom</type>`.
   - **For an application that already has a parent:** import `exeris-app-bom` in
     `dependencyManagement` and copy the plugin block — compiler with the processor, the three bound
     goals, the `<resource>` entry — from the README quick start. Importing a BOM brings
     `dependencyManagement` only; Maven does not import `pluginManagement` or bindings from it, which
     is why the block is copied.

6. **ADR-078's gate is unchanged.** `exeris:verify-runtime` stays exactly as ADR-078 states it.
   `exeris-app-parent` binds it; a build that does not use the starter binds it itself and keeps
   the gate's full behaviour. This ADR does not supersede ADR-078. It narrows the premise stated in
   ADR-078 §What is NOT in scope — "Tooling does not own the consumer's build" — to **"tooling emits
   no build; it publishes an optional one."** The out-of-scope item that premise is attached to,
   "Emitting a `pom.xml` or a dependency fragment", still holds: no generator writes one.

7. **Verification: a `maven-invoker-plugin` fixture application in the reactor.** It is built on
   `exeris-app-parent` and depends on `exeris-app-starter`, declares only what the starter leaves to
   the application, and runs the real plugin end to end: `generate` → `compile` →
   `verify-capabilities` → `verify-runtime` → the generated tests. It fails when an emitter starts
   importing a type the starter does not carry. The fixture is not deployed.

## Consequences

### ✅ Positive Outcomes

- **[+] A one-file build.** A consumer's POM is a parent, one starter dependency, a JDBC driver and
  its own code.
- **[+] A tested triple.** The tooling version names one kernel version and one SDK version that this
  release's suites ran against, instead of a consumer choosing three versions independently.
- **[+] The Maven plugin's first end-to-end test.** The invoker fixture exercises the goals in a
  real Maven lifecycle, phase binding and classpath resolution included, which the in-process e2e
  suite cannot reach.
- **[+] The resource defect is fixed for starter users.** Migrations and OpenAPI reach the classpath
  without the consumer discovering that a source root is not a resource root.
- **[+] The starter turns emitter imports into a checked contract.** An emitter that adds a
  third-party import fails the fixture, rather than a consumer's build after release.

### ⚠️ Trade-offs

- **[-] Another published surface to version.** Three more POMs go to Central with every release and
  must stay consistent with what the emitters import.
- **[-] The parent occupies the single `<parent>` slot.** An application with its own corporate or
  framework parent cannot use `exeris-app-parent`; the BOM-import route exists for that case and
  costs a copied plugin block that can drift from the README.
- **[-] Starter versions lag a kernel patch.** A kernel or SDK patch release reaches a starter user
  only when tooling re-releases. A consumer can override a managed version locally, outside the
  tested triple.
- **[-] The starter carries conditional dependencies unconditionally.** Jackson 3 and
  `exeris-sdk-composition-runtime` are on the classpath of an application with no `List<X>` field
  and no `@CapabilityModule`.
- **[-] The two-pass first build (D2) is not solved by the parent.** The processor writes
  `target/classes/exeris-metadata/*.json` during `compile`, after `generate-sources`
  (`GenerateMojo.java:28`; `ROADMAP.md`, D2), so a from-scratch build still needs two passes. The
  README quick start documents it.
- **[-] Kernel and SDK versions are declared in two BOMs.** `exeris-tooling-bom` (what the reactor
  builds and tests against) and `exeris-app-bom` (what a consumer gets) each declare them, and the
  tooling version itself is a literal in the parentless `exeris-app-bom`. `tools/release-readiness`
  fails the release when the kernel or SDK versions in the two BOMs differ.

### 📋 What is NOT in scope

- **Gradle.** No Gradle plugin, platform or convention plugin.
- **The TypeScript / npm side.** `exeris-codegen-ts` packaging is a separate plan.
- **A Maven archetype.** Later; it would generate a consumer POM from these modules, not replace them.
- **Emitting any build file** from the generators — `pom.xml`, dependency fragment or otherwise.
- **Choosing a JDBC driver or a logging backend** for the application.

### 🚫 Non-Goals

- **Making the starter mandatory.** Hand-written builds stay supported, with every gate they bind.
- **Serving tooling's own build from these modules.** The reactor keeps `exeris-tooling-bom` and
  `exeris-tooling-parent` for its internal pins.

### ⚠️ Risks and Assumptions

- **Assumes:** the kernel → SDK → tooling release order holds, so the BOM can name final versions of
  both.
- **Assumes:** the set of third-party packages emitted code imports stays small enough to carry in a
  starter (today: kernel SPI/Core, Jackson 3, the composition runtime).
- **Reversed by:** an emitter that needs a dependency the starter cannot reasonably carry for every
  application, or a consumer population that predominantly cannot take a parent, which would move
  the weight to the BOM and the archetype.
- **Risk:** the BOM-import route copies a plugin block that the README owns; the invoker fixture
  tests the parent route only, so a drifted README block is noticed by a consumer first.
- **Open:** whether the conditional dependencies (Jackson 3, the composition runtime) stay
  unconditional or become optional, and a BOM-route invoker fixture; revisit at 0.10.0.

## Cross-references

- **ADR-078** — the runtime-driver gate; its premise is narrowed here, its decision is unchanged.
- **ADR-058** — the generated-test channel; JUnit 5 + AssertJ in the consumer's `test` scope, and the
  surefire configuration the parent provides.
- **ADR-060** — generated code logs through `System.Logger`; why the starter carries no logging
  backend.
- **ADR-055** — the cap-tier Wall scan run by `exeris:verify-capabilities`, one of the three goals the
  parent binds.
- **ADR-070** — the generated composition root; unchanged by this ADR.
- **ADR-042** — the processor/reader metadata pair; the BOM pins the SDK version whose `-io` reader
  pairs with this processor.
- `ROADMAP.md`, "0.9.0 sequencing" → Scope group 3 (Application starter) and group 4 (Maven Central).
- `ROADMAP.md`, D2 (two-pass first build) and D3 (committed L1 for hand-written glue).

## Engineering Protocol

1. **`exeris-app-bom`, `exeris-app-parent` and `exeris-app-starter`** land as reactor modules in
   0.9.0, parented as obligation 4 states.
2. **The `maven-invoker-plugin` fixture** (obligation 7) runs in the reactor build and gates merges;
   it is the enforcement for obligations 2, 3 and 6.
3. **The release sets and checks the literal versions.** The release sets the tooling-version
   property in `exeris-app-bom` and checks that it equals the reactor version.
4. **`tools/release-readiness` is extended** (none of these checks exists today):
   - it fails when `exeris-app-bom` names a `-SNAPSHOT` kernel, SDK or tooling version;
   - it fails when the kernel or SDK versions in `exeris-tooling-bom` and `exeris-app-bom` differ;
   - it checks the `excludeArtifacts` of both `central-publishing-maven-plugin` declarations (root
     `release` and `exeris-app-bom`'s `exeris-tooling-release`) against `maven.deploy.skip`.
   It already walks every reactor module and requires a signed `.pom` for each `pom`-packaged one
   (`tools/release-readiness/release-readiness.sh:96-100`), so the three modules are covered by
   that check once they are in `<modules>`.
5. **The README quick start** documents both consumption routes, the plugin block for the BOM route,
   and the two-pass first build (D2).
6. Migration owner: `exeris-tooling`, target 0.9.0.

## Amendment 1 — Backend, frontend, or both (2026-10-01)

- **Status:** ACCEPTED (2026-10-01). Decided by the founder.
- **Amends:** the *What is NOT in scope* entry "The TypeScript / npm side". The npm packaging stays a
  separate deliverable. What this amendment adds is how a consumer chooses between the three shapes
  an Exeris application takes. Obligations 1–7 and the Engineering Protocol are unchanged by this amendment (Amendment 2 amends obligations 1–3).

A generated application is a backend, a frontend, or both. The Maven starter decided above covers
the first. The other two need the TypeScript emitter, which reads `DomainMetadata` JSON and never
Java sources (`exeris-gen generate --input`, default `target/classes/exeris-metadata`). A frontend
with no Java entities of its own therefore still needs metadata from somewhere.

1. **Backend.** The three Maven modules of this ADR, unchanged.
2. **Frontend only.** An npm starter on the `@exeris/codegen-ts` side. Its metadata source is the
   backend's **published contract artifact** as ADR-048 defines it: the peer's `cap-manifest.json`
   plus its full `DomainMetadata`, with the `cap-manifest.json` `schemaVersion` floor 2
   (ADR-048 §1), resolved by coordinate. A hand-maintained
   local directory of metadata JSON is not a supported source, because nothing would keep it in step
   with the backend it describes. The starter's name and shape belong to the codegen-ts plan.
3. **Backend + frontend.** An opt-in addition to `exeris-app-parent` that runs the TypeScript
   emitter on the build's own `target/classes/exeris-metadata` after the processor has written it.
   This is ADR-048's degenerate same-build case: the same JSON on the same path, never a second input
   model. It is off unless the application asks for it, so a backend-only build is byte-for-byte the
   build obligations 1–7 describe. The mechanism (activation, and which Maven plugin runs Node) is an
   implementation choice recorded with its pull request.

**Gate.** Shapes 2 and 3 depend on `@exeris/codegen-ts` being published to npmjs (codegen-ts track
plan, Stage 4). Shape 1 does not, and ships first. Each later shape brings its own fixture, as
obligation 7 does for the backend.

## Amendment 2 — what the invoker fixture forced (2026-10-01)

- **Status:** ACCEPTED (2026-10-01).
- **Amends:** obligation 1 (what `exeris-app-bom` manages), obligation 2 (the metadata bullet) and
  obligation 3 (the scope of `exeris-sdk-annotations`). Everything else is unchanged.
- **Trigger:** the obligation-7 fixture, built on the modules exactly as obligations 1–3 state them.

1. **`exeris-sdk-annotations` is `compile` in `exeris-app-starter`, not `provided`.** Maven does not
   pass a dependency's `provided` dependencies on to the consumer. With `provided`, an application on
   parent + starter has no annotations on its compile classpath, and the fixture's first pass fails
   with `package eu.exeris.sdk.annotation does not exist`. The annotations are `@Retention(SOURCE)`,
   so the jar on the runtime classpath is inert. A library that wants them `provided`, as the
   `exeris-caps-*` modules do, declares them itself and does not use the starter.
2. **`exeris-app-parent` carries full Exeris publication metadata.** It does not blank `url`,
   `licenses`, `developers` or `scm`. It is itself a published coordinate, and Maven Central requires
   those elements in what it publishes. The blanking moves to the consumer's own POM, which declares
   empty `<url/>`, `<licenses><license/></licenses>`, `<developers><developer/></developers>`,
   `<scm>` children and its own `<description>`. The README template and the fixture show this; it
   is the shape Spring Initializr generates beside `spring-boot-starter-parent`. Until an
   application does this, its effective POM inherits Exeris metadata, which is a cosmetic leak and
   not a build or publication fault.
3. **`exeris-app-bom` also manages `exeris-app-starter`** (type `pom`), so an application on the
   parent route states the tooling version once, in `<parent>`.
