---
title: "ADR-048 — A peer's contract is a dependency, and its types are the first thing worth generating from it"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-048
---

# ADR-048 — A peer's contract is a dependency, and its types are the first thing worth generating from it

- **Status:** ACCEPTED (2026-08-28) · amended 2026-09-26 (Amendment 1 — §6's client-slice gate is additive, not a binary break) · amended 2026-10-02 (Amendment 2 — the T58 precondition is met) · amended 2026-11-28 (Amendment 3 — every backend publishes its contract; the registry slice's manifest shape)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen (pipeline input shape)
- **Visibility:** public
- **Milestone:** 0.8.0 (types slice, **T42**) → 0.9.0 (client + registry, **T12** / **T17**)
- **Driven By:** [RFC-2026-06-29](../rfc/RFC-2026-06-29-cross-app-contract-mesh-tooling.md), ACCEPTED 2026-08-28 with Amendments 1–2
- **Supersedes / superseded by:** —

## Context

The pipeline emits the seams of a distributed system and flattens every cross-service edge to a
local call. Each generated `<Entity>Client` addresses only this app's own entities;
`@SagaStep(service, command)` is captured and dropped; `CapabilityGraph` is closed-world, so a
`@Requires(S)` a peer provides looks unprovided and fails the build.

The consumer-visible half is blunter than any of that. A mesh consumer **retypes the peer's
vocabulary by hand**, across a language boundary, with no compiler in between. Nothing detects the
drift; the first symptom is a field that silently stopped arriving.

### Measured before deciding

- **No mesh code exists.** `exeris-codegen-core` carries `capability/`, `driver/`, `generator/`;
  `grep` for a contract registry or peer client across all three emitters returns nothing. Nothing
  has been decided by code.
- **Nothing names an application.** `CapabilityModuleDescriptor` carries `name`, `packageName`,
  `qualifiedName` — of a *module*. `CompositionStamp` carries `validated`, `compositionVersion`,
  `contentBinding`. There is no app-level identity anywhere in the emitted artefacts, so "which peer
  is this" is a question the current surface cannot answer.
- **Peer addressing arrived while this was being decided.** Kernel **ADR-074** (ACCEPTED 2026-08-26)
  puts an `authority` on `HttpRequest` and gives `KernelWebClient.withAuthority(String)`. Before it,
  the client dialled the address its own server listened on — an app could not address even the
  first peer. The kernel holds the `ServiceResolver` seam **post-1.0**, deliberately.

## 🏁 The Decision

**A peer's contract is a published artifact the consumer depends on, and the first thing generated
from it is types — nothing else.**

### 1. Contract source and content

The source is a **published contract artifact**: the peer's `cap-manifest.json` plus the **full
`DomainMetadata`** of the entities it provides. Peers in one build are the *degenerate same-build
case* — the same JSON on the same path — never a second input model. `schemaVersion` floor is **2**;
a v1 peer is **rejected with an actionable error**, not accepted in a degraded mode, because
ADR-042's baseline-trust check has nothing to verify against below it.

**Full metadata, not a pruned "contract subset"** (founder ruling, 2026-08-28). Pruning would require
deciding what a consumer needs *before knowing what it generates*, and it would create a second
schema to keep in step with the first — this repository's own backlog is a catalogue of what happens
to a second surface that drifts. ADR-042's `sourceDigest` is computed over what the producer emitted,
so a pruned artifact would need a digest story of its own.

> **Implementation note (types slice, 0.8.0) — which `schemaVersion`.** The artifact carries
> **two**, versioned independently: an integer on `cap-manifest.json`
> (`CapabilityGraph.SCHEMA_VERSION`, today `2`) and a *string* on each entity JSON
> (`SchemaVersion.CURRENT`, today `"0.11.0"` — the ADR-042 baseline-trust stamp). "Floor 2" above
> is the manifest's, and that is what the loader enforces. The entity stamp is a **skew** check
> rather than a floor: ADR-042 refuses a baseline whose version differs from the reader's, and
> enforcing that here would require every peer to have been built with the consumer's exact SDK
> version — which contradicts this ADR's own "apps stay independently built and versioned". It is
> therefore not checked by the types slice; it belongs with the registry slice, which is the one
> that reads a peer's contract for trust rather than for shape.

> **Recorded as a consequence, not hidden:** a full artifact exposes the producer's whole domain
> surface to every consumer of the contract, including entities they never import. That is a
> disclosure decision, and it is taken here knowingly. An app that must not publish an entity keeps
> it out of the artifact by not providing it.

### 2. A peer is named by the consumer

The peer's name — the one that appears in the consumer's import paths — is **declared by the
consumer**, not read from the artifact. Two reasons, one of them measured: nothing in the emitted
artefacts carries an app identity today, and the name lands in the consumer's own source tree, where
it must stay stable regardless of what the producer later renames itself.

### 3. Peer types are namespaced, and never merged

Peer DTOs are emitted **per peer**, under that peer's namespace, with **its own enum module and its
own barrel**. They are never merged into the app's own `types/` barrel.

Two peers may both call an entity `Order`, and **T40** is this repository's own record of what
happens when two identifiers meet in one namespace: an emitted app that does not compile, found by
`ng build` and not by any unit test. A merged barrel would reproduce it at mesh scale.

### 4. Copies per consumer, not a shared package

Each consuming app emits its own copy of the peer types it imports. A shared generated package would
be a *distribution* decision — publishing types on someone's behalf — and this pipeline generates
into one app's tree. Dedup within an app is what the peer namespace already provides.

### 5. The generated client takes an authority, and names no resolver

Per kernel ADR-074, the addressee rides on the request. The generated peer client takes an
**authority** (`host:port`) and defines **no resolver interface of its own**. The RFC had proposed a
`PeerAddressResolver` as "the shape K4 drops into unchanged"; the kernel has since decided the
opposite disposition on purpose — multi-peer addressing in 1.0, the `ServiceResolver` seam post-1.0 —
and records that a future resolver's output *becomes* the authority.

Emitting our own resolver would stand up a second addressing vocabulary one train before the
platform's own. That is the same argument the SDK used to refuse a `ROLE_x`-to-scope convention, and
the same failure mode as the inert emitted machinery recorded in D10, D11 and the unwired templates.

### 6. Slice boundary

| Slice | Contents | Gate |
|---|---|---|
| **Types (T42)** | peer DTOs only, namespaced per peer | none from the kernel — `DomainMetadata` in, types out |
| **Client + registry (T12 / T17)** | peer remote-client; open-world resolution so a peer-provided `@Requires` resolves instead of hard-failing | a **final** kernel 0.12 — ADR-074 takes a binary break on `HttpRequest`, a `stable` carrier, behind a bridge constructor *(amended 2026-09-26: measured additive, not a break — see Amendment 1)* |
| **Saga remote-dispatch body** | command dispatch + park on the peer's `@DomainEvent`s | T1 command surface, SDK **S5** |

The RFC originally called the client slice "kernel-free, ships now" — true only because the kernel
could not address a peer at all, so a stub was the best available. ADR-074 makes it addressable and
thereby makes that slice kernel-**pinned**. The genuinely kernel-free half is the one the RFC had not
carved out: types.

### 7. Parity

The types slice is **TS-first, deliberately**, and this is the parity statement rather than an
omission: its consumer is a *frontend* retyping a Java service's vocabulary, which has no Java
counterpart — a Java app importing a peer's DTOs is the **client slice**, and that one is Java∪TS as
the RFC pins it. A Java peer-DTO emitter without a client would generate records nobody calls.

## Consequences

### ✅ Positive Outcomes

- The drift class is removed outright at the point it costs most: a peer's field rename becomes a
  compile error in the consumer instead of a runtime absence.
- The types slice ships against no kernel version, so it is not held by the 0.12 pin, by K4, or by
  the capability twin.
- Choosing the published artifact over co-located sources means apps stay independently built and
  versioned; the monorepo case still works, as the degenerate mode.

### ⚠️ Trade-offs

- **The artifact carries more than any one consumer needs** — see the disclosure note in §1.
- **A consumer-declared peer name can disagree with the producer's own.** Accepted: the alternative
  is a producer identity that does not exist yet, and a name that changes under the consumer's
  imports when the producer renames.
- **Per-consumer copies mean N copies of the same peer DTO across N apps.** Accepted for the same
  reason the pipeline emits per-app trees at all; a shared package is a distribution decision.
- **The client slice inherits a binary break.** ADR-074's bridge constructor keeps existing call
  sites compiling, but the pin move is a real cut in the release order. *(Amended 2026-09-26 —
  Amendment 1: there is no binary break. The pin move is still a cut, for a behavioural reason.)*

### 📋 What is NOT in scope

- The `ServiceResolver` / logical-name discovery — the kernel holds it post-1.0 and tooling models
  nothing of it.
- Saga remote-dispatch mechanics (the seam is named; the body is gated).
- Publishing a shared types package on a producer's behalf.

## Cross-references

- **RFC-2026-06-29** — the design gate, ACCEPTED 2026-08-28 with Amendments 1–2.
- **kernel ADR-074** — a request names its own peer; the reason §5 names no resolver.
- **ADR-042** — `schemaVersion` / `sourceDigest` baseline trust; the floor in §1.
- **ADR-040 / capability graph** — the closed-world resolution the registry slice opens up.
- **T40** — the emitted-identifier collision that §3's namespacing exists to prevent.

### Verification (owed by the slices, not by this ADR)

- The types slice: a generated app that imports two peers each declaring an entity `Order` must
  compile — the `ng build` gate, not a substring assertion, per T40's lesson.
- A v1 peer artifact must fail the build with a message naming the peer and the floor.
- Determinism: peers sorted by declared name; entities in the order the local path already uses.

## Amendment 1 — the client slice's gate is additive, not a binary break (2026-09-26)

**Trigger:** the ROADMAP's 0.12 readiness measurement (2026-09-01, a `javap` diff of
`exeris-kernel-spi` 0.11.0 → 0.12.0-SNAPSHOT), re-checked on 2026-09-26 against kernel `0.12.0`
(`development/0.12.0`, code cut 2026-09-03), and the architect review of the same day.

### What

§6 said the client + registry slice is gated on "a **final** kernel 0.12 — ADR-074 takes a binary
break on `HttpRequest`, a `stable` carrier, behind a bridge constructor". The Trade-offs said "the
client slice inherits a binary break". Measured, there is no binary break:

- `HttpRequest` gained `authority()`, `withAuthority(String)` and a six-argument constructor. The
  five-argument constructor is kept as an overload that delegates with `null`, and nothing was
  removed. Only a record pattern over `HttpRequest` would break, and no emitter writes one.
- `KernelWebClient.withAuthority(String)` is `@since 0.12`, an addition.

What ADR-074 does change is **behaviour, not binary shape**. A CLIENT-mode `HttpConfig` built
through the ten-argument overload no longer dials its `bindHost`/`port`, so an unaddressed request
is refused at its first send (K8). No signature diff shows that, which is why the emitted `*Client`
Javadoc now carries it (B0).

### What the gate is now

The client slice still waits on a **final** kernel 0.12, for two reasons, and neither is a binary
break:

1. It needs ADR-074's addressing surface (`HttpRequest.authority`, `KernelWebClient.withAuthority`).
   That surface exists only from 0.12, and a tooling cut takes no pre-release pin.
2. The generated client's `update` has to reach the generated server first (T58). The router serves
   `PUT`, and `KernelWebClient` 0.12.0 has no `put`. The fix is a kernel ask, or an architect
   decision to serve a `PATCH` alias.

### What this amendment does NOT decide

- The T17 manifest shape: how peer bindings are recorded, and their exclusion from `initOrder` and
  `contentBinding`. That needs its own amendment before T17 ships. The architect review adds one
  constraint on it: it must not raise `cap-manifest.json`'s `schemaVersion`, because the SDK boot
  check (`KNOWN_SCHEMA_VERSION = 2` in `CompositionStampAssertion`) refuses anything above 2.
- The saga remote-dispatch body, which stays gated as §6 says.

## Amendment 2 — the T58 precondition is met (2026-10-02)

- **Status:** ACCEPTED (2026-10-02).
- **Amends:** Amendment 1, "What the gate is now", reason 2. Reason 1 and everything else are unchanged.

Kernel 0.12.0 is final on Maven Central, and its `KernelWebClient` has `put` (exeris-kernel#579).
The generated `*Client.update` sends `PUT {base}/{id}`, the verb the generated router serves, and
`CrudRouteParityE2ETest` asserts it with no exemption (T58). Reason 2 no longer gates the client
slice; reason 1, ADR-074's addressing surface, is available on the same final kernel.

## Amendment 3 — every backend publishes its contract, and the registry slice's manifest shape (2026-11-28)

- **Status:** ACCEPTED (2026-11-28).
- **Amends:** §1 (where the contract artifact is, what it holds, how a consumer reaches it, and the
  last sentence of its disclosure note), §1's implementation note on the entity `schemaVersion`,
  and Amendment 1's "What this amendment does NOT decide", first entry (the T17 manifest shape).
  §2 to §7 are unchanged.
- **Issues:** exeris-tooling#391, exeris-tooling#550.

### Context

§1 makes a peer's contract its `cap-manifest.json` plus the full `DomainMetadata` of its entities.
`CodegenPipeline` writes `cap-manifest.json` only for a build that declares a `@CapabilityModule`,
and writes it at the root of the generated source tree, which is not on the classpath. A backend
without a capability module therefore has no contract, and no backend's jar carries one; ADR-091
Amendment 5 withholds the frontend-only topology for that reason. The entity metadata itself is in
every jar already: the processor writes `exeris-metadata/*.json` to the class output.

The registry slice needs a place in `cap-manifest.json` for a requirement a peer satisfies.
`schemaVersion` cannot rise above 2, because the SDK composition runtime refuses any higher value.
The SDK's manifest record reads `schemaVersion`, `stamp`, `modules` and `initOrder`, and ignores
every other member.

### Decision

**Every backend publishes its contract inside its own jar, and a consumer reads it by Maven
coordinate or by path; the registry slice adds members to `cap-manifest.json` that a
`schemaVersion` 2 reader ignores.**

1. **Content.** The contract artifact is a directory holding:
   - `cap-manifest.json`, the resolved capability graph of the build. A build with no
     `@CapabilityModule` writes the graph of the empty composition: `schemaVersion` 2, a stamp
     with `validated: true` and the content binding of the empty module set, and empty
     `modules`, `resolutions`, `initOrder` and `warnings`;
   - one file per `@ExerisDomain` entity of the build, byte for byte as the processor wrote it,
     under the same file name;
   - each `enum_*.json`, byte for byte.

   It holds no `view_*.json` and no `capability_*.json`. "The entities it provides" in §1 means
   every entity the build declares; an application keeps an entity out of its contract by keeping
   it out of the build.
2. **Place.** The directory is `META-INF/exeris/contract/` in the backend's main jar.
3. **Writer.** A goal `exeris:contract` of `exeris-codegen-maven-plugin`, default phase
   `process-classes`, reads `${project.build.outputDirectory}/exeris-metadata`, deletes
   `${project.build.outputDirectory}/META-INF/exeris/contract/` and writes it anew. It builds the
   graph with the same method and serializer as `exeris:generate` and the same
   `exeris.composition.version` input, so for the same metadata the two manifests are
   byte-identical. A graph that does not resolve fails the goal and writes nothing. It is skipped
   by `exeris.codegen.skip` and by `exeris.contract.skip`. The emission logic lives in
   `CodegenPipeline`; the mojo is a shell.
4. **Publication reach.** `exeris-app-parent` binds `exeris:contract` after `verify-capabilities`
   and `verify-runtime`; the opt-out is `exeris.contract.skip`. Three places state the bound goals
   and change in the same change as the binding, as obligations of M1 (#391):
   `exeris-app-parent/pom.xml`, the copy of the build block in `README.md`, and the comment that
   lists the bound goals in the initializr's backend `pom.xml.tmpl`. An application that uses
   neither the parent nor that build block publishes nothing.
5. **Unchanged outputs.** `exeris:generate` still writes `cap-manifest.json` into the generated tree
   only for a build with a `@CapabilityModule`. A build without one emits byte-identically to 0.11.
6. **Determinism.** The set of entries and the bytes of each are a function of the processor's
   output and the composition version. Entity and enum files are copied, not re-serialized. An
   entity file keeps the `schemaVersion` and `sourceDigest` the processor stamped, and the digest
   is over the Java source. An enum file carries neither. Copying keeps the contract one schema and
   deterministic.
7. **What a reference is.** A peer reference is a consumer-declared name (§2, the name rule the
   TS emitter already applies) and exactly one of:
   - a **path**: a contract directory; a directory containing `META-INF/exeris/contract/`; or a
     `.jar` file, of which only entries under `META-INF/exeris/contract/` are read and nothing is
     extracted to disk;
   - a **Maven coordinate** `groupId:artifactId:version`, naming the backend's main jar.
8. **Java consumer.** `exeris:generate` takes a `peers` parameter. A coordinate is resolved through
   the Maven resolver against the project's remote repositories and is never added to the
   project's dependencies, so no class of the peer reaches the consumer's classpath.
   - Maven orders reactor modules by project dependencies, the parent, plugin dependencies and
     build extensions. A `<path>` reference creates no ordering edge.
   - A consumer in the same reactor as its producer declares the peer by Maven coordinate and lists
     the producer under `exeris-codegen-maven-plugin`'s `<dependencies>`. That orders the reactor
     without putting the producer on the project's classpath.
   - A `<path>` reference is for a contract built outside the current build. A path that resolves
     inside a module of the current reactor is refused with a diagnostic.
9. **npm consumer.** `@exeris/codegen-ts` accepts the same references (`peers[].path` or
   `peers[].coordinate`; on the command line, `--peer <name>=<path>` or
   `--peer <name>=mvn:<groupId>:<artifactId>:<version>`). A coordinate is looked up in the local
   Maven repository, then in the configured repositories (default Maven Central) over HTTPS. The
   local repository path is configuration, defaulting to `~/.m2/repository`. A remote download is
   a release version only, is checked against the repository's `.sha1` before use, and is not
   cached. A SNAPSHOT resolves from the local repository only. No credentials are read. The jar is
   read without a dependency: the central directory is parsed and DEFLATE entries are inflated
   with `node:zlib`.
10. **Readers.** The Java reader in `exeris-codegen-core` and the TS reader apply the same rules:
    - `schemaVersion` floor 2, refused with a message naming the peer;
    - a missing manifest refused;
    - unknown manifest members ignored;
    - files read by name: `cap-manifest.json`, `enum_*.json` and entity files only;
    - peers sorted by declared name.

    Entities and enums are sorted by code unit, as `String.compareTo` and the default
    `Array.prototype.sort` order them, each side by the key its local path already uses. The Java
    reader sorts entities by qualified name (`CodegenPipeline.DOMAIN_ORDER`) and enums by qualified
    name (`EnumTypes.sorted`). The TS reader sorts entities by `entityName` and enums by `name`
    (`exeris-codegen-ts/src/peers/peer-contract.ts`). The sides emit different languages, so the
    two keys need not agree.

    Every refusal names the peer. A Java refusal carries `EXT-PLUG-2006` (ADR-095). The TS command
    line prints no identifier until ADR-095 allocates a `TS` area.
11. **Registry slice, manifest shape.** `cap-manifest.json` stays `schemaVersion` 2 and gains two
    members, both omitted when empty:
    - `peers`: one entry per declared peer whose contract was read, sorted by name:
      `{ "name", "compositionVersion", "contentBinding", "provides": [ { "service", "version",
      "module" } ] }`, the two stamp values copied from the peer's manifest and `provides` sorted
      by service, then module.
    - `peerProviders` on a resolution: `[ { "peer", "module" } ]`, sorted, naming each peer module
      whose provided version matches the requirement's range.

    A requirement is satisfied when a local provider or a peer provider matches. `providers` lists
    local modules only. A peer never enters `modules`, `initOrder`, cycle detection or the stamp's
    `contentBinding`. A requirement nothing in the declared mesh provides fails as it does today.
12. **The bump rule.** A member a `schemaVersion` 2 reader can ignore does not raise
    `schemaVersion`. Removing or renaming a member, or changing the meaning or type of one, does.
    The `CapabilityGraph.SCHEMA_VERSION` documentation states this rule.
13. **Entity schema skew.** When a peer entity's `schemaVersion` differs from the consumer's
    `SchemaVersion.CURRENT`, the build warns with `EXT-PLUG-2007`, naming the peer, the entity and
    both versions. An entity with no `schemaVersion` is refused with `EXT-PLUG-2006`.
14. **What M2 generates from a contract.**
    - **Java peer types:** a record per entity and an enum per `enum_*.json`, in
      `<basePackage>.peers.<segment>`, where `<segment>` is the peer name lowercased with
      `Locale.ROOT` and its hyphens removed. A segment for which
      `javax.lang.model.SourceVersion.isName` is false (keywords, and the literals `true`,
      `false` and `null`) is refused. Two peers whose segments are equal after that step are
      refused.
    - **Java peer client:** one per entity that serves an HTTP API, taking a `KernelWebClient` and
      an authority (`host:port`, kernel ADR-074) and sending every request through
      `withAuthority(authority)`. It sends the verbs and paths the local client derives for the
      same entity. It names no resolver (§5).
    - **An entity with `restApi = false`:** on the Java side it gets no peer client, and gets a peer
      type only when an entity that has a peer client references it. On the TS side it keeps its
      peer types. The decision on `restApi` itself is W8's (#539), which has the final word.
    - **TS peer client:** a client per entity that serves an HTTP API beside the peer types under
      `peers/<name>/`, exported from that peer's barrel only, reading the entity through the
      functions the local service emitter uses.
15. **Room for the event specification.** The name `events.json` at the contract root is reserved
    for the per-event specification (name, topic, ordinal, payload schema) that the events
    decision defines. It carries an integer `schemaVersion` of its own, starting at 1, and is
    written by `exeris:contract`. Readers that predate it skip it by name.
16. **Diagnostic identifiers (ADR-095).** `EXT-PLUG-2006`, a peer contract refused: an unreadable
    or missing contract, a missing manifest, a `schemaVersion` below the floor, an entity with no
    `schemaVersion`, a checksum mismatch, a refused package segment, a segment collision, a
    `<path>` inside the current reactor. `EXT-PLUG-2007`, a peer entity's `schemaVersion` differs
    from the consumer's (a warning). `EXT-PLUG-2401`, `exeris:contract` could not build the
    contract; `24xx` is the group of that goal. The rows enter `docs/diagnostics.md` with M1.

### Consequences

#### Positive Outcomes

- **[+] Every backend is a peer.** A backend scaffolded without capabilities, as `exeris-initializr`
  writes it, publishes a contract a frontend-only application or another service can consume.
- **[+] No new disclosure.** The entity metadata in the contract is already in every jar under
  `exeris-metadata/`.
- **[+] The composition runtime is untouched.** A manifest with peers passes the SDK assertion
  unchanged, and a manifest without peers is byte-identical to today's.

#### Trade-offs

- **[-] Two copies of the metadata in a jar**, `exeris-metadata/` and the contract. The contract is
  the published one; the other remains the processor's output.
- **[-] A stamp over nothing.** The empty-composition manifest carries `validated: true` over the
  empty module set. It is a contract header, not a composition anyone boots.
- **[-] The TS package gains a ZIP reader and network access** for coordinates, both new surfaces.
- **[-] The contract is as fresh as `exeris-metadata/`.** An incremental build that leaves a
  deleted entity's file there publishes it.

#### What is NOT in scope

- The event specification's content (the events decision, then R1).
- Reading `cap-manifest.json` at boot from the jar's contract copy instead of the working directory.
- Peer references in `exeris-codegen-cli`.
- Credentials for a private repository in `@exeris/codegen-ts`.
- The saga remote-dispatch body (§6).

#### Non-Goals

- A contract artifact separate from the jar (a classifier or an npm package).
- Pruning the contract per consumer.
- Adding a peer to the consumer's dependency graph.
- An application identity read from the contract; §2 stands.

#### Risks and Assumptions

- **Assumes:** the SDK composition runtime keeps ignoring members it does not read, and keeps
  accepting `schemaVersion` 2.
- **Assumes:** a release version on a Maven repository is immutable, which is what makes a remote
  resolution reproducible.
- **Assumes:** the kernel's JSON body codec reads and writes records, which the Java peer client
  sends and receives.
- **Assumes:** the reactor resolves the producer's coordinate to its output directory once the
  producer has run `process-classes`. A build that stops earlier (`mvn compile`) gives the consumer
  no fresh contract, and fails with the diagnostic that names the missing contract. The M1 invoker
  fixture with two modules holds the same-build case.
- **Assumes:** Node's `fetch` reads proxy settings only when `NODE_USE_ENV_PROXY` is set. The
  default local repository `~/.m2/repository` does not follow a `localRepository` of `settings.xml`
  or `maven.repo.local`, so the TS side takes the local repository path as configuration.
- **Risk:** a stale `exeris-metadata/` entry is published. A release built with `clean` does not
  carry one, and the reader cannot detect it.
- **Reversed by:** a composition runtime that refuses an unknown manifest member, which would force
  peer bindings out of `cap-manifest.json`; or a consumer that cannot take the backend's jar,
  which would move the contract to an attached artifact.

### Verification

- The invoker fixture of ADR-091 obligation 7 asserts that its jar holds
  `META-INF/exeris/contract/cap-manifest.json` with `schemaVersion` 2 and empty `modules`, and
  each entity file byte-equal to `target/classes/exeris-metadata/`. A fixture with two modules, the
  producer listed under the plugin's `<dependencies>`, holds the same-build case.
- For capability metadata, the contract's manifest is byte-equal to the one `exeris:generate`
  writes; writing the contract twice gives identical bytes; a file absent from the metadata is
  absent from the contract.
- A v1 manifest, a missing manifest and a checksum mismatch are each refused with the peer's name.
- A manifest with `peers` and `peerProviders` passes `CompositionStampAssertion`; a graph with no
  peers serializes byte-identically to the 0.11 golden copy.
- A consumer with two peers that both declare `Order` compiles in Java (`KernelCodegenCompileTest`)
  and builds in TypeScript (`ng build`).
- A Java peer client's requests reach a generated producer at the authority it was given, with
  the verb and path the producer's router serves.
