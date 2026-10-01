---
title: "ADR-092 — What a change to the emitted TypeScript owes its consumers"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-092
---

# ADR-092 — What a change to the emitted TypeScript owes its consumers

- **Status:** ACCEPTED (2026-10-01)
- **Deciders:** the founder
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen (emitted artefact contract, `exeris-codegen-ts`)
- **Visibility:** public
- **Milestone:** 0.9.0, before the first npm publication (`docs/codegen-ts-track-plan.md`, P1, P5, P15)
- **Driven By:** `docs/codegen-ts-track-plan.md`, stage 4 ("A stability decision for the TS output");
  `ROADMAP.md`, "Versioning policy"
- **Relates to:** ADR-015 (the Java counterpart), ADR-044, ADR-076, ADR-093
- **Supersedes / superseded by:** —

## Context and Problem Statement

ADR-015 makes the emitted Java a stability contract, and scopes itself to `exeris-codegen-core` and
`exeris-codegen-java`; it names `exeris-codegen-ts` out of scope (§Consequences → Neutral), and
`docs/MIGRATION-0.x-to-1.0.md` records the same exclusion. No decision says what a change to the
emitted Angular application owes the application that committed it.

Two consequences follow. The organisation's pull-request classification requires
`Compatibility impact: none | additive | breaking (ADR-NNN)`, and a TS-only breaking change has no
ADR to name. And some TS changes fit none of the three values without a rule: a regenerated view that
no longer renders a column changes no type, yet a user of the application loses something.

`@exeris/codegen-ts` also carries its own version (`exeris-codegen-ts/package.json`), independent of
the Maven reactor, while it consumes the `DomainMetadata` JSON written by the same release's
processor. Emitted files carry hard-coded package versions in their header comments (`v0.2.0`,
`v0.3.0`, `v0.4.0`), none of which is the package's version.

**The question this ADR answers:** how `@exeris/codegen-ts` is versioned, and which changes to its
emitted output are breaking, additive or neither — in 0.x and in 1.x.

## 🏁 The Decision

**`@exeris/codegen-ts` carries the reactor's version and is released on the reactor's tag. A change
to its emitted output is classified against the surface below; this ADR is the one a TS-only
`breaking` classification cites.**

1. **Lockstep version.** `@exeris/codegen-ts` carries the Maven reactor's version and is released
   on the same `vX.Y.Z` tag, to npmjs under the `@exeris` scope. There is no independent TS version
   line. A CI guard fails the build when `exeris-codegen-ts/package.json` `version` (and the root
   entry of its `package-lock.json`) differs from the reactor version in the root `pom.xml`.

2. **The emitted surface.** A consumer's code, tests and users depend on:
   - the path of every emitted file, and every export, type and member of an emitted DTO, schema
     or type;
   - every emitted route path, service method (name, parameters, return type) and component
     selector, input and output;
   - every `data-testid` the emitted templates write (the consumer's end-to-end tests select on
     them);
   - what a regenerated view renders for unchanged metadata: its controls, columns, rows, panels
     and actions;
   - the dependency majors the emitted `package.json` pins (Angular, TypeScript, the Node engine);
   - the package's own interface: its CLI flags, configuration keys and exported functions.

3. **Breaking** is any change that removes, renames or narrows something in that surface:
   - removing or renaming an emitted file, export, type, DTO or type field, route, service method,
     component input or output, or `data-testid`;
   - making an optional member, parameter or input required, or narrowing a type;
   - **narrowing an emitted view** — a control, column, row, panel or action that the previous
     release rendered for the same metadata and the regenerated output no longer renders;
   - raising a dependency major in the emitted `package.json`;
   - removing or renaming a CLI flag or configuration key, or changing its default so that the same
     invocation emits a narrower output.

4. **Additive** is a change that adds to the surface and leaves every existing consumer compiling
   and behaving as before: a new file or export; a new optional member, parameter or input with a
   default; a new route; a view that renders more for the same metadata; new emission behind
   metadata that previously produced nothing; a new configuration key whose default keeps the old
   output.

5. **None** is a change outside the surface: comments and TSDoc in emitted code, whitespace and
   formatting, import order, styling classes, and the wording of presentational text. Such a change
   still changes the bytes of committed files, so it is made deliberately, not as a side effect:
   - **the emitted file header carries no value that changes per release** — no package version, no
     date. A release that changes no emitter regenerates byte-identical output. Removing the
     version strings the headers carry today is a one-time header change with a MIGRATION entry;
   - a change that rewrites every emitted file (a header, a formatter) gets a MIGRATION entry naming
     the expected regeneration diff, as ADR-015's migration did for the Java side.

6. **0.x and 1.x**, following `ROADMAP.md` "Versioning policy":
   - **0.x** — the emitted shape may change in any release, breaking changes included. Each
     breaking change carries a MIGRATION entry (obligation 7).
   - **1.x** — minors are additive only. A breaking change ships in the next major. Before it, at
     least one minor keeps the old surface working and marks it: a `@deprecated` TSDoc tag naming the
     replacement on an emitted member, or, for a view narrowing, the old rendering kept beside the new
     one behind a configuration key.

7. **The MIGRATION obligation.** A pull request classified `breaking (ADR-092)` adds an entry to
   `docs/MIGRATION-0.x-to-1.0.md` (or its 1.x successor) in the same pull request, stating: what a
   consumer sees after regenerating, which hand-written code is affected, and what to change.

8. **Classifying a TS pull request.** `Compatibility impact:` is the highest class any of its changes
   reaches under obligations 3–5 — `none`, `additive`, or `breaking (ADR-092)`. When a more specific
   ADR governs the changed surface, the pull request cites it as well: ADR-044 for the stream
   clients' routes and frames, ADR-076 for the write-rejection statuses the emitted front maps,
   ADR-093 for the shape of emitted forms. A change touching both emitters classifies the Java half
   against ADR-015 and the TS half against this ADR.

### Relation to other decisions

- **ADR-015** governs the emitted Java, SQL and YAML; this ADR governs the emitted TypeScript and the
  `@exeris/codegen-ts` package. Neither extends into the other's scope.
- **ADR-044, ADR-076** (and any later contract ADR) fix a specific wire or behavioural contract the
  front shares with the emitted backend. They are not restated here; a change to what they govern
  is classified here and justified there. Because the two emitters release in lockstep, a wire change
  that moves both sides in one release is classified by its effect on the consumer's own code.

## Consequences

### ✅ Positive Outcomes

- **[+] One version answers which metadata a package reads.** `@exeris/codegen-ts` `X.Y.Z` reads
  the JSON of processor `X.Y.Z`; no compatibility matrix is needed.
- **[+] A TS-only breaking change has an ADR to cite**, and a narrowed view has a class.
- **[+] Releases without emitter changes regenerate nothing.** Dropping release-varying values from
  headers keeps consumers' diffs to real changes.
- **[+] Consumers learn of breaks from MIGRATION**, not from a failed build after regeneration.

### ⚠️ Trade-offs

- **[-] Empty npm releases.** A reactor release with no TS change still publishes a new
  `@exeris/codegen-ts` version.
- **[-] View narrowing is judged by review.** No test measures "renders less for the same metadata"
  across releases; the classification relies on the author and the reviewer.
- **[-] The 1.x deprecation cycle costs emitter code.** Keeping an old rendering behind a key until a
  major doubles a generator path for one minor at least.

### 📋 What is NOT in scope

- The stability of the emitted Java (ADR-015) and the Maven plugin's goals.
- The ui-kit package (`exeris-sdk-ui-kit`), versioned by `exeris-sdk`.
- Release mechanics beyond the version rule: the npmjs publication job is P15 of the codegen-ts plan.

### 🚫 Non-Goals

- Freezing the 0.x shape. 0.x keeps the freedom to break; this ADR makes each break visible.
- Snapshot testing of emitted output as a gate; the substring specs and the determinism check stay
  the test surface.

### ⚠️ Risks and Assumptions

- **Assumes:** the TS package consumes the metadata JSON written by the same release's processor;
  the TS line never needs to read metadata from a processor of another version.
- **Assumes:** the TS side and the Maven artefacts release on the same cadence often enough that a
  version bump with no TS change costs consumers nothing beyond a regeneration.
- **Reversed by:** a consumer population that pins the TS package independently of the processor
  (for example, a studio that regenerates fronts from stored metadata of many tooling versions), or
  a TS release cadence that has to diverge from the reactor's — either would bring back a separate
  version line with a published compatibility matrix.
- **Risk:** the emitted-surface definition is enforced by review and MIGRATION discipline, not by a
  tool; a breaking change classified `additive` reaches consumers unannounced. The header guard and
  the stage-0 contract-coverage gate are the only automated parts.

## Alternatives considered

- **An independent TS version line** (the package keeps its own semver). Rejected: the package reads
  the same release's processor JSON, so every TS version would need a statement of which processor
  versions it accepts — a compatibility matrix maintained by hand, with no consumer who wants two
  different versions.
- **Extending ADR-015 to cover TS.** Rejected: ADR-015 is an emission-technique decision (JavaPoet,
  text blocks) whose stability clause rests on that technique; the TS surface (routes, views,
  `data-testid`, `package.json` pins) has no Java counterpart.
- **Treating every byte change as breaking.** Rejected: it would classify comment and formatting
  changes with removed exports and make the class meaningless.

## Compliance — testable obligations

1. **Version guard** (obligation 1): a CI step in `build.yml` compares `exeris-codegen-ts/package.json`
   and `package-lock.json` with the reactor version and fails on a difference (P1).
2. **Header guard** (obligation 5): a codegen-ts spec asserts that no emitted file header contains a
   version string; a release that changes no emitter regenerates byte-identical output.
3. **MIGRATION per breaking change** (obligation 7): a pull request classified
   `breaking (ADR-092)` that does not change `docs/MIGRATION-0.x-to-1.0.md` is rejected in review.
4. **Classification rule** (obligation 8): a pull request touching `exeris-codegen-ts` states
   `Compatibility impact:` per obligations 3–5 and cites the specific contract ADR where one governs.
5. Migration owner: `exeris-tooling`, target 0.9.0.
