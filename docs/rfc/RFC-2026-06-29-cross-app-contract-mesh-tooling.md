# RFC-2026-06-29: What shape should the tooling cross-app contract mesh take, now that each app already emits a closed-world capability/contract surface?

| Field             | Value                                                                 |
|:------------------|:----------------------------------------------------------------------|
| **Status**        | **DRAFT** — proposal pending founder review. No code has landed (unlike the SSE/`@View` slices); this RFC is the **design gate** for the T12/T17 mesh epic and the **RFC half** of the reserved ADR-048. |
| **Author(s)**     | arkstack-dev                                                          |
| **Date Opened**   | 2026-06-29                                                           |
| **Date Closed**   | —                                                                    |
| **Target ADR(s)** | **ADR-048** (reserved in `exeris-docs/adr-index.md`, 2026-06-29) — "cross-app contract mesh" — ratifies this RFC's recommendation; sibling to [ADR-044](../adr/ADR-044-tooling-sse-stream-emitter-shape.md). |
| **Affected Repos**| `exeris-tooling` (a contract-registry stage in `exeris-codegen-core`; a peer **remote-client + DTO** emitter in `exeris-codegen-java` **and** `exeris-codegen-ts` — Java∪TS parity); `exeris-sdk` (the `@SagaStep(service, command)` surface + capability inertness **S5** — named, gated); `exeris-kernel` (**K4** logical-name→endpoint addressing — named, gated; `KernelWebClient` is single-host today) |
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
- the base host is an **injected addressing seam** — a narrow `PeerAddressResolver { String resolve(String service); }` interface resolved at call time (`addressing.resolve("<service>")`), **not** a relative own-app path and **not** a raw config map. Pinning the *interface* (over a map default) is deliberate: it is the shape K4 runtime discovery drops into unchanged, so the generated client's public API does **not** break when addressing lands — a map default would force a client-signature change at that point. K4-shaped, so the runtime binding drops in without reshaping the generated client;
- the DTOs are emitted from the **peer's** contract (imported), deduped when multiple consumers import the same peer entity.

**Java∪TS parity (load-bearing):** the peer client is emitted by **both** `exeris-codegen-java` and `exeris-codegen-ts` — a TS app calling a Java service needs the same typed client. This is the parity obligation the SSE/`@View` emitters established.

### 4. Saga remote-dispatch (named, **gated** follow-up — seam now, body later)
Generalize `KernelSagaGenerator` so a `@SagaStep(service, command)` resolved to a peer **dispatches the command to the resolved service and parks on the peer's `@DomainEvent`s** (replacing the local lambda). This RFC fixes the **resolution + the seam**; the dispatch **body** is the follow-up slice, gated on: the saga command surface (T1-track — the remote-command dispatch shape, SDK `@SagaStep` half **S5**), **K4** addressing, and EV1 event payloads (now shipped, [ADR-046](../adr/ADR-046.link.md) / PR #123 — the park-on-events half is unblocked).

### 5. Kernel-target discipline + the Wall + inert honesty
The mesh is a **platform** concern: the contract registry and addressing live platform-side (platform → kernel, never reverse), and **no mesh type leaks into the kernel**. The generated peer client binds only the tier-neutral `KernelWebClient` facade (ADR-034). The contract-registry artifact follows the `cap-manifest` precedent — deterministic, ADR-042 baseline-trust fields, no timestamps. Per the strict-inert rule (`-Aexeris.strict`), if cross-app `@Requires`/`@SagaStep.service` resolution is extracted in a window before its generator lands, the registry entry is added then removed in lock-step.

### Slice boundary (build-gate honesty)
- **Kernel-free, ships now:** the contract registry (open-world resolution, **T17**) + the peer remote-client + DTO generator (the **client+DTO slice**), Java∪TS, with the K4-shaped addressing seam stubbed (config-injected logical name).
- **Gated, named not built:** **K4** runtime addressing (kernel-core); the saga remote-dispatch **body** (T1 command surface + S5); S5 SDK capability inertness.

## Open questions / follow-ups (technical — gated, not blocking the slice)
- **Published-contract artifact format** — the provided-entity `DomainMetadata` in full vs a pruned "contract subset" (only the `@Provides` services + the entities they expose). *Version/compat is decided (§1): ADR-042 `schemaVersion`, floor 2.*
- **Addressing seam shape** — *decided (§3): a `PeerAddressResolver` interface, not a config map.* Genuinely open only at its **K4 convergence** — the kernel-side logical-name→endpoint discovery that backs the resolver (`KernelWebClient` host parameter).
- **Saga remote-dispatch body** — the command-dispatch + park-on-`@DomainEvent` mechanics; follow-up slice on the T1 command-surface track.
- **DTO dedup / sharing** — one shared generated DTO per peer entity across N consumers vs per-consumer copies.

## Next action

On **ACCEPT**: author **ADR-048** (already reserved in `exeris-docs/adr-index.md`) fixing the contract-mesh shape — the registry (open-world resolution), the peer remote-client + DTO emitter (Java∪TS), and the K4-shaped addressing seam — as the contract, and scoping the gated follow-ups (K4 addressing, saga remote-dispatch body). Then build the **kernel-free slice**: the contract-registry stage + the peer client/DTO generator + T17 open-world capability resolution, behind the addressing seam, with determinism + parity gates. K4 addressing and saga remote-dispatch land as named follow-ups.
