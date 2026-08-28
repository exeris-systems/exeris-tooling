# ADR-048 — A peer's contract is a dependency, and its types are the first thing worth generating from it

- **Status:** ACCEPTED (2026-08-28)
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
| **Client + registry (T12 / T17)** | peer remote-client; open-world resolution so a peer-provided `@Requires` resolves instead of hard-failing | a **final** kernel 0.12 — ADR-074 takes a binary break on `HttpRequest`, a `stable` carrier, behind a bridge constructor |
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
  sites compiling, but the pin move is a real cut in the release order.

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
