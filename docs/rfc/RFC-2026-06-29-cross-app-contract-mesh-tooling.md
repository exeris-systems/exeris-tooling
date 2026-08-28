# RFC-2026-06-29: What shape should the tooling cross-app contract mesh take, now that each app already emits a closed-world capability/contract surface?

| Field             | Value                                                                 |
|:------------------|:----------------------------------------------------------------------|
| **Status**        | **ACCEPTED** 2026-08-28, with Amendments 1–2 (T42 as the first slice; §3 revisited against kernel ADR-074) and one ruling taken at acceptance: the contract artifact carries **full `DomainMetadata`**, not a pruned subset. Ratified as [ADR-048](../adr/ADR-048-cross-app-contract-mesh.md). No code has landed (unlike the SSE/`@View` slices); this RFC is the **design gate** for the T12/T17 mesh epic and the **RFC half** of the reserved ADR-048. |
| **Author(s)**     | arkstack-dev                                                          |
| **Date Opened**   | 2026-06-29                                                           |
| **Date Closed**   | 2026-08-28                                                           |
| **Target ADR(s)** | **ADR-048** (reserved in `exeris-docs/adr-index.md`, 2026-06-29) — "cross-app contract mesh" — ratifies this RFC's recommendation; sibling to [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md). |
| **Affected Repos**| `exeris-tooling` (a contract-registry stage in `exeris-codegen-core`; a peer **remote-client + DTO** emitter in `exeris-codegen-java` **and** `exeris-codegen-ts` — Java∪TS parity); `exeris-sdk` (the `@SagaStep(service, command)` surface + capability inertness **S5** — named, gated); `exeris-kernel` (**K4** logical-name→endpoint addressing — **delivered on the 0.12 train by ADR-074**; when this RFC was written `KernelWebClient` could not address a peer at all, see Amendment 2) |
| **Reviewers**     | —                                                                    |

## Question

The pipeline already emits the **seams** of a distributed system — a typed sync client, async `@DomainEvent`s, saga orchestration *intent* — but **flattens every cross-service edge to a local call**:

- each generated `<Entity>Client` wraps `KernelWebClient` with a **relative** base path, only for *this app's own* entities — there is no way to import a peer app's `DomainMetadata` and generate a client/DTOs against *its* contract;
- `@SagaStep(service, command)` is captured in `SagaStepMetadata` then **dropped** — the generated `<Saga>Flow` wires **local** lambdas, with no remote dispatch and no await-on-peer-events;
- `CapabilityGraph` is **closed-world per app**, so a legitimate cross-service `@Requires(S)` satisfied by a *peer* app's `@Provides(S)` looks unprovided and **hard-fails the build** (T17).

**What shape should the tooling generate to turn a cross-app edge into a real one — a cross-app contract registry, a typed remote client + DTOs against a peer's contract, mesh-aware (open-world) capability resolution, and saga remote-dispatch — such that the kernel-free slice ships now without breaking determinism (hard-constraint #3), kernel-target discipline / the Wall (hard-constraint #1), or the inert-attribute rule; the runtime-addressing (**K4**) and saga-command-surface (T1-track) dependencies are deferred behind a clean seam rather than blocking the slice; and Java/TS parity holds (a TS app calling a Java service gets the same generated client)?**

## Context

Generating **N independent apps already works** — each compiles, runs, and serves its own REST/event/saga surface. The missing piece is *strictly* the **cross-app edge**: importing another app's contract and turning a saga `service`/`command` (and a cross-app `@Requires`) into a remote dispatch instead of a local no-op.

The input the mesh resolves against **already exists**: the 0.5.0 capability pass emits a deterministic `cap-manifest.json` whose own Javadoc names this work — *"the platform-side capability registry (input for the cross-app mesh contract, T12)"*. The capability-composition model is [ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md) (authoritative in `exeris-docs`); its SDK realization is [ADR-038](../adr/ADR-038.link.md). Capabilities are a **PLATFORM** concern, not a kernel one — every SDK annotation is `@Retention(SOURCE)`, the dependency direction is platform → kernel, and the kernel neither sees nor should see the registry.

This is the **largest remaining generation gap**: a downstream dog-food app hand-writes the seam this RFC eliminates — a remote `UniverseClient` + in-process/HTTP adapters + ~15 DTO records (~1,230 LOC), plus delegating saga bodies (~210 LOC) that hand-wire `@SagaStep(service, command)` dispatch.

Precedents this design mirrors:
- **Capability extraction** (`@CapabilityModule` → `cap-manifest.json`) — the app-wide, deterministic, platform-side artifact this registry consumes and extends.
- **The SSE / `@View` emitters** (ADR-044 / RFC-2026-06-28) — the framework-neutral-IR + Java/TS emitters + parity/determinism-gate template a peer-client emitter follows.
- **`KernelClientGenerator`** (ADR-034 `KernelWebClient`) — the intra-app typed client whose shape the peer client generalizes.

## Investigation — what exists today

- **`CapabilityGraph` (codegen-core) is closed-world.** `build(List<CapabilityModuleDescriptor>)` builds `providersByService` **only** from the in-build module set; an unsatisfied non-optional `@Requires` is a hard build failure (`CapabilityGraphException`), version ranges matched by `VersionRange`, cycles detected, `initOrder` topo-sorted. `cap-manifest.json` carries the ADR-024 `CompositionStamp(validated, compositionVersion, contentBinding=sha256:…)`. **No external/remote provider source exists** — nothing reads another app's manifest.
- **`KernelClientGenerator` is intra-app.** It emits `<Entity>Client` against *this* app's own `/api/<version><path>` via the tier-neutral `KernelWebClient` (ADR-034) — CRUD with `Optional`-on-404. The host is implicit/relative; there is no host/baseUrl parameter and no foreign-app target.
- **`KernelSagaGenerator` is intra-app.** It emits `<Saga>Flow` over the kernel `FlowEngine` SPI; `@SagaStep(service, command)` is recorded in `SagaStepMetadata` but the generated steps are **local** `protected FlowOutcome <step>(FlowContext)` lambdas — no remote dispatch, no cross-app participant.
- **The app-wide-artifact pattern** is established: the processor writes `exeris-metadata/<prefix>_<Name>.json` via `writeMetadata(...)` (`capability_*`, `view_*`), and codegen-core loads a family by prefix-filter + a read-model record (`CapabilityModuleDescriptor`). A cross-app contract artifact follows this precedent.
- **Kernel gap:** `KernelWebClient` is **single-host** (no host/baseUrl param), so logical-service-name → endpoint discovery (**K4**) is not available at runtime today.
- **Confirmed absent:** any cross-app contract registry, remote/peer-client generator, saga remote-dispatch, or open-world capability resolution.

## Recommendation (the mesh shape; kernel-free first slice)

### 1. Contract source — a configured **peer-contract artifact set** (the crux)
An app imports a peer's contract through a **configured set of peer contract artifacts**, each carrying the peer's `cap-manifest.json` (its `@Provides`) **plus** the `DomainMetadata` for the provided entities (the DTO + REST contract). Two supply modes, one input shape:
- **multi-app reactor** — peers in one build; their `exeris-metadata/*.json` is on the build path (the monorepo / dev case);
- **published contract artifact** — each app publishes its contract (cap-manifest + the provided-entity `DomainMetadata`), consumers depend on it; [ADR-042](../adr/ADR-042.link.md)'s `sourceDigest` / `schemaVersion` baseline-trust fields give the cross-build compatibility check.

The registry resolves against the **union** of these — a single input that serves *both* the client/DTO (T12) and capability (T17) axes. **Decided: the contract source is the published artifact.** The artifact format and the resolver are designed around the **published** shape — real microservice independence, with cross-build compatibility gated by [ADR-042](../adr/ADR-042.link.md)'s `schemaVersion` / `sourceDigest`. The multi-app reactor is the **degenerate same-build case** that simply drops its `exeris-metadata/*.json` onto the same path; the registry never *assumes* peers share a build. This fixes the artifact format and the build-coupling model: apps are independently built and versioned, and a peer contract is a dependency, not a co-located source.

**Minimum peer `schemaVersion` = 2 (reject, don't degrade).** The registry requires each peer contract at or above the `CompositionStamp` baseline (`schemaVersion` 2, which introduced `validated` / `contentBinding`); a v1 peer is **rejected with an actionable error**, not silently accepted in a degraded mode. Rationale: [ADR-042](../adr/ADR-042.link.md)'s baseline-trust check has nothing to verify against on a pre-stamp v1 contract, and consuming an unvalidated peer contract is exactly the failure the stamp exists to prevent — a soft-degrade path would quietly reintroduce it. Bumping a peer to v2 is a rebuild on the current pipeline, so the requirement is a version floor, not a compatibility break.

### 2. Cross-app contract registry = open-world resolution (T17)
A new **contract-registry stage** in `exeris-codegen-core` extends `CapabilityGraph` from closed-world to **open-world**: feed the resolver the **union of peer cap-manifests**, so a `@Requires(S)` / `@SagaStep(service=S)` unprovided locally but provided by a peer resolves to a **remote binding** `(peerApp, service)` instead of hard-failing. The closed-world hard-fail stays for a requirement **no app in the configured mesh** provides. Deterministic (sorted union, stable resolution order); the existing `VersionRange` intersection and `CompositionStamp` discipline carry over. This *is* T17 — same input, same stage as the client/DTO resolution.

### 3. Peer remote-client + DTO generator (the kernel-free **client+DTO slice**)
Per resolved cross-app binding, generalize `KernelClientGenerator` to emit a typed **`<PeerEntity>Client` + shared DTOs against the *peer's* `DomainMetadata`** — the same client shape as intra-app, with two changes:
- the base host is an **injected addressing seam** — a narrow `PeerAddressResolver { String resolve(String service); }` interface resolved at call time (`addressing.resolve("<service>")`), **not** a relative own-app path and **not** a raw config map. Pinning the *interface* (over a map default) is deliberate: it is the shape K4 runtime discovery drops into unchanged, so the generated client's public API does **not** break when addressing lands — a map default would force a client-signature change at that point;
- the DTOs are emitted from the **peer's** contract (imported), deduped when multiple consumers import the same peer entity.

**Java∪TS parity (load-bearing):** the peer client is emitted by **both** `exeris-codegen-java` and `exeris-codegen-ts` — a TS app calling a Java service needs the same typed client. This is the parity obligation the SSE/`@View` emitters established.

### 4. Saga remote-dispatch (named, **gated** follow-up — seam now, body later)
Generalize `KernelSagaGenerator` so a `@SagaStep(service, command)` resolved to a peer **dispatches the command to the resolved service and parks on the peer's `@DomainEvent`s** (replacing the local lambda). This RFC fixes the **resolution + the seam**; the dispatch **body** is the follow-up slice, gated on: the saga command surface (T1-track — the remote-command dispatch shape, SDK `@SagaStep` half **S5**), **K4** addressing, and EV1 event payloads (now shipped, [ADR-046](../adr/ADR-046.link.md) / PR #123 — the park-on-events half is unblocked).

### 5. Kernel-target discipline + the Wall + inert honesty
The mesh is a **platform** concern: the contract registry and addressing live platform-side (platform → kernel, never reverse), and **no mesh type leaks into the kernel**. The generated peer client binds only the tier-neutral `KernelWebClient` facade (ADR-034). The contract-registry artifact follows the `cap-manifest` precedent — deterministic, ADR-042 baseline-trust fields, no timestamps. Per the strict-inert rule (`-Aexeris.strict`), if cross-app `@Requires`/`@SagaStep.service` resolution is extracted in a window before its generator lands, the registry entry is added then removed in lock-step.

### Slice boundary (build-gate honesty)

*Re-cut by Amendment 2 — the original boundary is kept below it, because the reason it moved is the
point.*

- **Kernel-free, ships first — the types slice (T42):** peer DTOs only, per peer, under its own
  namespace with its own enum module and barrel. No client, no registry, no addressing. Depends on
  nothing in the kernel and on no kernel version: it is `DomainMetadata` in, TypeScript (and, when
  its consumer exists, Java) types out. Its only gate is the artifact-format question below.
- **Pinned to a final kernel 0.12 — the client+DTO slice:** the peer remote-client + the contract
  registry's open-world resolution (**T17**). Addressing is no longer stubbed and no longer gated: a
  generated client takes an **authority** (ADR-074) and names no resolver. What this slice now waits
  for is the kernel **pin**, not the kernel **feature** — `HttpRequest` takes a binary break on a
  `stable` carrier there, behind a bridge constructor.
- **Still gated, named not built:** the saga remote-dispatch **body** (T1 command surface + S5); S5
  SDK capability inertness. The kernel's `ServiceResolver` seam is post-1.0 by its own ruling, and
  tooling models nothing of it — a resolver's output is simply the authority.

> **Original boundary (2026-06-29), superseded:** *"Kernel-free, ships now: the contract registry
> (T17) + the peer remote-client + DTO generator, Java∪TS, with the addressing seam stubbed behind
> the `PeerAddressResolver` interface (a config-injected logical name until K4 lands). Gated, named
> not built: K4 runtime addressing (kernel-core); the saga remote-dispatch body; S5."* It read the
> client slice as kernel-free because the kernel could not address a peer at all; ADR-074 makes it
> addressable and, in doing so, makes that slice kernel-**pinned** rather than kernel-free. The
> genuinely kernel-free half turned out to be the one this RFC had not carved out yet.

## Amendment 1 (2026-08-28) — where **T42** sits, and what it may not decide by itself

T42 in `ROADMAP.md` proposed a "cheap honest version" of the mesh: a **types-only** second emission,
"point the CLI at a second metadata directory and emit `types/` without services or components".
Picked up on 2026-08-28, it turned out to sit inside this RFC's scope and to contradict two things
this RFC has already decided. Recording that here rather than building it, because the difference is
not a detail:

1. **Contract source.** §1 decided the source is the **published contract artifact** (cap-manifest +
   the provided entities' `DomainMetadata`, `schemaVersion` floor 2), with peers-in-one-build as the
   *degenerate same-build case* that drops its `exeris-metadata/*.json` onto the same path. T42's
   "second metadata directory" **is** that degenerate case — so building it as *the* input shape
   would ship a second input model the artifact model then has to retire, and would skip the
   ADR-042 baseline-trust check that makes a peer contract trustworthy at all.
2. **Parity.** §3 pins the peer DTO emitter as **Java∪TS**, load-bearing. A types-only TS emission
   is not automatically a parity breach — a *frontend* retyping a Java service's vocabulary is a
   different consumer from a Java app calling a peer — but the distinction has to be stated rather
   than left to the diff, and the Java half named. It is the RFC's own client+DTO slice (T12).

**What T42 becomes:** the first slice of this RFC's recommendation, not a shortcut around it —
*peer types, no peer client*. Concretely, once ADR-048 is authored:

- input: a configured peer-contract artifact set, exactly §1's shape, with the same-build directory
  as the degenerate supply mode rather than as a separate CLI concept;
- output: peer DTOs only — the entity interface, its `Create`/`Update` shapes and (under
  `generateZod`) its schemas — emitted per peer under its own namespace, with its own enum module
  and its own barrel, never merged into the app's own `types/` barrel. Two peers may both call an
  entity `Order`, and T40 is the record of what happens when two identifiers meet in one namespace;
- explicitly **not** in it: the peer client (§3), the registry's open-world resolution (§2), saga
  remote-dispatch (§4). Those are what makes it a *slice* rather than a subset.

**Why it is worth having as its own slice:** it prevents the drift class outright — a mesh consumer
currently retypes a peer's vocabulary by hand across a language boundary, with no compiler between —
and it needs neither addressing (K4) nor the capability twin (T17), so it can ship while those are
still gated.

**What it still may not decide alone:** the peer-namespace scheme and DTO dedup are open questions
below, and both are visible in emitted output the moment this slice lands. The ADR settles them.

## Amendment 2 (2026-08-28) — kernel 0.12 addresses peers, and §3's seam has to change because of it

Checked before acceptance, on the founder's prompt that kernel 0.12 might already ease this. It
does, and it also **falsifies a premise this RFC rests on**.

**Verified on `exeris-kernel` `development/0.12.0`:** [`ADR-074 — A request names its own peer`]
is **ACCEPTED** (2026-08-26, scope `kernel/http`). The addressee now rides on `HttpRequest` as an
`authority` component, and `KernelWebClient` gained `withAuthority(String)` returning a client that
addresses that peer while sharing engine, allocator and retry policy —
`client.withAuthority("payments:8443").get(...)`. Authority is `host:port` (IPv6 bracketed), or
`null` to fall back to the engine default. ADR-074 fixes the order as *authority → enrich → send*, so
an outbound credential's audience binds to the peer rather than to the connect target.

Two consequences for this RFC:

1. **§3's stated premise — "`KernelWebClient` is single-host today" — is false on 0.12.** ADR-074's
   own spike found it was narrower still: through the supported path the client dialled the address
   its *own server* listens on (`targetHost = config.bindHost()`), so an app could not address even
   the first peer. That is fixed. **The client+DTO slice no longer waits on addressing at all.**
2. **`PeerAddressResolver` should be dropped, not stubbed.** This RFC proposed it as "the shape K4
   runtime discovery drops into unchanged". The kernel has since decided the opposite disposition
   deliberately: multi-peer addressing is 1.0, the **`ServiceResolver` seam is post-1.0**, and
   ADR-074 records that a future resolver "returns an endpoint, which becomes the authority on the
   request. No rework of this decision is implied." So a generated client should **take an
   authority**, and name no resolver of its own. Defining `PeerAddressResolver` here would stand up a
   second addressing vocabulary one train before the platform's own — the same mistake this
   repository has now recorded three times as inert emitted machinery, and the same argument the SDK
   used to refuse a `ROLE_x`-to-scope convention.

**What that leaves for the ADR:** the generated peer client takes an authority (a constructor
argument or a `withAuthority` call at the seam), the consumer supplies it, and when the kernel's
`ServiceResolver` lands its output *is* that authority — no generated signature changes.

**Cost to note, not to hide:** `HttpRequest` is a `stable` carrier and ADR-074 accepts a binary break
on it, mitigated by a bridge constructor. Tooling pins kernel 0.11 today, so the client slice
requires moving the pin to a **final** 0.12 — the release-ordering rule (no cross-repo SNAPSHOT at a
cut) applies. **T42, the types-only slice, is untouched by all of this**: peer types need no client,
no authority and no resolver, which is precisely why Amendment 1 cut the boundary where it did.

## Open questions / follow-ups (technical — gated, not blocking the slice)

*All four closed on 2026-08-28; struck through rather than deleted, so the record shows what
the platform answered rather than what we quietly dropped.*
- ~~**Published-contract artifact format**~~ — **CLOSED 2026-08-28 at acceptance: full `DomainMetadata`, no pruning.** Pruning would decide what a consumer needs before knowing what it generates, and would stand up a second schema to keep in step with the first. Recorded with it: a full artifact exposes the producer's whole domain surface to its consumers, which is a disclosure decision taken knowingly — an app that must not publish an entity keeps it out by not providing it.
- ~~**Addressing seam shape**~~ — **CLOSED 2026-08-28 by kernel ADR-074.** The addressee rides on `HttpRequest`; a generated client takes an authority and names no resolver. The "K4 convergence" this line held open does not arise: ADR-074 records that a future `ServiceResolver` returns an endpoint which *becomes* the authority, so nothing on our side converges later.
- ~~**K4 runtime addressing as a gate**~~ — **CLOSED**: delivered on the kernel 0.12 train. What replaces it is a version pin, not a gate.
- **Saga remote-dispatch body** — the command-dispatch + park-on-`@DomainEvent` mechanics; follow-up slice on the T1 command-surface track.
- ~~**DTO dedup / sharing**~~ — **CLOSED 2026-08-28 by [ADR-048](../adr/ADR-048-cross-app-contract-mesh.md) §4: per-consumer copies.** A shared generated package would be a *distribution* decision — publishing types on a producer's behalf — and this pipeline generates into one app's tree. Dedup within an app is what the peer namespace provides.

## Next action

*Superseded 2026-08-28 — this RFC is ACCEPTED and ratified as
[ADR-048](../adr/ADR-048-cross-app-contract-mesh.md). What follows is the plan as it stood before
acceptance, kept because the sequence it names is still the sequence.*

**Now:** build the **types slice (T42)** — peer DTOs, namespaced per peer, gated on no kernel
version. Then the client + registry slice, once the kernel pin moves to a final 0.12.

> **Before acceptance, this section read:** *"On **ACCEPT**: author **ADR-048** (already reserved in
> `exeris-docs/adr-index.md`) fixing the contract-mesh shape — the registry (open-world resolution),
> the peer remote-client + DTO emitter (Java∪TS), and the K4-shaped addressing seam — as the
> contract, and scoping the gated follow-ups (K4 addressing, saga remote-dispatch body). Then build
> the kernel-free slice … "* The ADR is written; the "K4-shaped addressing seam" in that sentence is
> the part ADR-074 retired.
