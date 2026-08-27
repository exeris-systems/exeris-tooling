# ADR-078 — The build fails when the generated application has no driver to run on

- **Status:** ACCEPTED (2026-08-27)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / build (consumer-build contract)
- **Visibility:** public
- **Milestone:** 0.8.0 (backlog item **T50**)
- **Supersedes / superseded by:** —

## Context

The emitted `Application.main()` boots the kernel by handing it
`BootstrapSelector.forNames(subsystems())`, where the generated `subsystems()` returns the
fixed string `http,persistence,graph,flow,events,crypto`. Every provider behind those names
is discovered at boot through `ServiceLoader`, and every one of them arrives from a **runtime
driver artefact**.

`exeris-tooling` emits no `pom.xml`. So nothing in the consumer's build declares that
dependency, and until this ADR nothing checked it. The first report was a bootstrap error at
start-up naming a subsystem — which is the wrong noun. The missing thing is a jar.

This is **T30 one phase later, and it fails worse**: T30 was a compile-time symbol that was
not there, which javac names precisely; this is a runtime lookup that returns nothing, and the
message blames the lookup.

### Measured, against the published artefacts

The finding as written says the providers "are in `exeris-kernel-community`". Checking the
jars rather than the source tree sharpened it into something a gate can stand on:

| Artefact | `META-INF/services` entries |
|---|---|
| `exeris-kernel-core-0.11.0.jar` | **0** |
| `exeris-kernel-community` (0.11.0) | 15, including `SubsystemProvider`, `PersistenceProvider`, `HttpProvider`, `EventProvider`, `GraphProvider`, `FlowProvider` |

Subsystems are not resolved one SPI at a time — `BootstrapSelector` selects **by name** from
`ServiceLoader<SubsystemProvider>`. Core registers no `SubsystemProvider` at all, so an
application built on SPI + Core alone has no name that can resolve, not merely a subsystem
that fails. That fact is also what makes a build-time gate worth having: **Core cannot satisfy
it**, so the check is not a formality that every classpath passes.

## 🏁 The Decision

**A new goal, `exeris:verify-runtime`, fails the build when the emitted application has no
provider registered for an SPI it needs.**

1. **Bound to `process-classes`**, like `exeris:verify-capabilities` and for the same reason:
   the metadata it reads is the output of the annotation processor that ran in `compile`, so
   at `generate-sources` it would be the previous build's.

2. **It scans the resolved runtime classpath for `META-INF/services/<spi>`** — the mechanism
   `ServiceLoader` itself uses. `requiresDependencyResolution = RUNTIME` makes Maven inject
   exactly the set the application will start with: not the compile classpath, where a driver
   may be `provided` and absent at run time, and not the test classpath, which can carry one
   the application will not have.

3. **The required SPIs are derived from what the pipeline emitted**, never from the subsystem
   name string:

   | SPI | Required because |
   |---|---|
   | `SubsystemProvider` | always — it is how *any* name in `subsystems()` resolves |
   | `PersistenceProvider` | always — a repository is emitted per entity, and the composition root builds its `TransactionalExecutor` over `KernelProviders.persistenceEngine()` |
   | `HttpProvider` | always — handlers are emitted per entity and routed by `Application` |
   | `EventProvider` | some entity declares a `@DomainEvent`, which is what makes an `<Entity>EventPublisher` exist |
   | `GraphProvider` | some entity carries graph metadata |
   | `FlowProvider` | some entity declares a saga |

4. **Crypto is deliberately not required**, though the default `subsystems()` names it. No
   emitted artefact uses it, so requiring it would be a claim about the consumer's subsystem
   list rather than about this pipeline's output.

5. **The message names the artefact, not the subsystem** — which is the entire point of the
   goal — lists which SPIs are unregistered, reports how many classpath elements were scanned,
   and names the opt-out.

### Why derive from artefacts and not from `subsystems()`

The emitted `subsystems()` javadoc explicitly invites the consumer to override it ("Subclass
`Application` and override this method to add/remove subsystems — e.g. to drop `graph` when
the project has no graph projections"). A check driven by that string would therefore demand
providers for names the running application may never request, and would fail builds that are
correct. Deriving from emitted artefacts survives the override, because the artefacts do not
change when the string does.

### Why a resource scan and not a class load

A `META-INF/services` lookup is a file read. It loads no consumer class, instantiates nothing,
and adds no dependency — JDK `java.nio.file` and `java.util.zip` only, the same self-imposed
floor `CapTierWall` keeps for its class-file scan (ADR-055).

## Consequences

### ✅ Positive Outcomes

- The failure moves from start-up to `mvn verify`, and from "subsystem `persistence` failed to
  start" to "add `eu.exeris.kernel:exeris-kernel-community`".
- It is precise rather than binary: a classpath with a partial driver set is told *which* SPIs
  are unregistered.
- It costs one resource lookup per classpath element, and stops early once every required SPI
  is accounted for.

### ⚠️ Trade-offs

- **A goal nobody binds does nothing.** This is the failure shape this repo keeps
  rediscovering — an emitted flag read by no one, an emitter wired by nobody — and it applies
  to this gate too: an unbound `verify-runtime` is silent, exactly like an unbound
  `verify-capabilities`. Following that precedent is deliberate (a plugin cannot bind itself
  into a consumer's lifecycle without a custom packaging), but the limitation is named here
  rather than discovered later, and the migration entry leads with the execution block.
- **A pass proves registration, not capability.** It does not prove the registered provider
  supplies a particular subsystem name, satisfies a version range, or starts. Any of those
  means running the provider, which a build-time gate deliberately does not do.
- **A multi-module consumer may see a false positive** where one module generates code that
  another runs. `-Dexeris.verifyRuntime.skip=true` covers it, and degrades the verdict to a
  WARNING rather than removing it — the build still says what it found.
- **The SPI names are string constants in tooling.** A kernel that renames or repackages a
  provider interface breaks the gate silently (it would report a missing driver that is
  present). Not tracked by a compile dependency because tooling does not depend on the kernel
  at build time; the mitigation is that these six names are stable API on the kernel's own
  stability matrix.

### 📋 What is NOT in scope

- **Emitting a `pom.xml` or a dependency fragment.** Tooling does not own the consumer's
  build; that is the constraint the whole finding lives inside, not something to route around.
- **Checking version ranges** between the driver and the tooling that emitted the app.
- **Answering "does this driver supply `graph`".** See the trade-off above.
- **Improving the kernel's own boot-failure message.** Worth doing and not tooling's to do.

## Cross-references

- **ADR-055** — the cap-tier Wall scan, the precedent for a build-time gate reading a second
  input from the consumer's build, and for keeping that read dependency-free.
- **ADR-070** — the composition root; `RuntimeComponents` is where a consumer's own provider
  wiring would enter, and nothing here changes it.
- **T30** in `ROADMAP.md` — the same defect one phase earlier.
- **T50** in `ROADMAP.md` — the finding, and its own preference for a build failure over a
  documented requirement.

### Verification

`RuntimeDriverCheck` is covered against real jars and real exploded directories, including the
Core-shaped element that registers nothing. The gate was then perturbed: removing the archive
entry check (so every SPI counts as found) fails `reportsEverySpiWhenNoDriverIsPresent` and
`reportsOnlyTheMissingHalf` — the two tests that encode the T50 shape and its partial case.
