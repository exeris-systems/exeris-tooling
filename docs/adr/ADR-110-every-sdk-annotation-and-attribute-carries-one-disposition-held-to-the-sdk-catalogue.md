---
title: "ADR-110 — Every SDK annotation and attribute carries one disposition, held to the SDK catalogue"
type: adr
visibility: public
owning-repo: exeris-tooling
status: active
slug: adr/ADR-110
---

# ADR-110 — Every SDK annotation and attribute carries one disposition, held to the SDK catalogue

- **Status:** ACCEPTED (2026-11-28) · accepted-on-merge per the per-repo pattern (ADR-047 / ADR-058)
- **Deciders:** `exeris-tooling` (registry format, test placement)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / processor — per-repo
- **Visibility:** public
- **Milestone:** 0.12.0 (`docs/0.12.0-release-plan.md`, wave W1, rows G1 and G2)
- **Driven by:** `docs/1.0.0-annotation-coverage-plan.md` decision W0-1 and row G1; exeris-tooling#535
- **Relates to:** ADR-015 (output stability), ADR-095 (diagnostic identifiers), ADR-097 (generator catalogue), ADR-042 (the SDK reader half), ADR-047 (field-level `@UI`)
- **Supersedes / superseded by:** —

## Context and Problem Statement

Every SDK annotation is `@Retention(SOURCE)`. The processor and the generators are its only consumer,
so an attribute no generator reads has no effect at any tier. The SDK freezes what it declares at
1.0.0, and after that an attribute can be deprecated but not removed before 2.0. This repository has
committed to 1.0.0 shipping no frozen annotation or attribute without an effect (coverage plan W0-1).

What this repository knows about consumption sits in three hand-written registries in
`ExerisDomainProcessor`: `INERT_ATTRIBUTES` (by simple name and attribute), `INERT_ANNOTATIONS` (by
qualified name) and `UNREAD_NOTES` (by simple name), plus the allowlist `EXTRACTED_ANNOTATIONS`. They
drive `-Aexeris.strict`, and they are a lower bound rather than a census. `@InternalApi` is extracted,
read by no generator, and absent from `INERT_ANNOTATIONS`. Nine `@ExerisDomain` attributes reach no
output and have no entry. A whole-SDK count has been taken by hand three times and gave three numbers
for three different sets, none of which covered attributes.

The SDK publishes `META-INF/exeris/annotation-catalog.json` in `exeris-sdk-annotations`
(`catalogFormat` 1): every annotation by qualified name, nested ones included, with its attributes,
repeatable container and `deprecated.forRemoval`. It carries no stability field: Preview status
exists only in prose and in the SDK's `docs/stability-matrix.md` (exeris-sdk#283 asks for one).

**The question this ADR answers:** in what form does this repository record the disposition of every
annotation and attribute of the pinned SDK, what is that record checked against, and how does it
bind the strict-audit registries?

## 🏁 The Decision

**A committed registry in `exeris-processor`'s test resources gives every annotation and attribute in
the pinned SDK's annotation catalogue exactly one of six states, and a test holds it to the catalogue,
to the SDK's Preview list and to the strict-audit registries. The processor does not read it.**

**Concrete obligations:**

1. **Location and form.** The registry is
   `exeris-processor/src/test/resources/eu/exeris/tooling/processor/annotation-dispositions.json`: a
   JSON object with `registryFormat: 1` and a `rows` array. It carries no timestamp and no version.
   Rows are sorted by `annotation`, then the annotation-level row, then `attribute`. The test fails
   when the file differs from its canonical serialisation (two-space indent, one row object per line).
   It is not packaged in any jar, and no main source in this repository reads it.
2. **Row key.** `annotation` is the catalogue's `qualifiedName`. `attribute` is a catalogue attribute
   name; when it is absent, the row is the annotation-level row. `on` (`TYPE` or `NON_TYPE`) appears
   only on the annotation-level rows of an annotation in `ExerisDomainProcessor.TYPE_LEVEL_EXTRACTION`.
   Such an annotation has both rows; the attribute rows describe the use the processor reads.
3. **Census.** Every catalogue entry has an annotation-level row, except a repeatable container: an
   entry another entry names as its `repeatableContainer`. A container takes no rows, and its use is
   its member's. Every attribute of every entry with rows has an attribute row, except where the
   annotation-level row is `SDK_REMOVED`, `OUTSIDE_FREEZE` or `REFUSED`: those attributes inherit the
   state, and an attribute row under such an annotation is stale. A row whose key the catalogue does
   not contain is stale.
4. **States.** The set is closed. Each state requires exactly the key shown, and a row carries no key
   besides the key fields, `state`, that key and an optional one-line `note`.

   | State | Key | Holds when |
   |---|---|---|
   | `EMITTED` | `emittedBy`: a non-empty sorted array of `java:<id>` (a row id of `exeris-codegen-java`'s `generator-catalogue.json`, ADR-097) and `ts:<path>` (a generator listed under "TypeScript generators" in `docs/generators.md`) | The value reaches emitted output on at least one side. The Java and TypeScript emitters together are the consumer. |
   | `SDK_REMOVED` | none | The catalogue marks the attribute, its annotation, or the annotation that declares a nested one `deprecated.forRemoval`. Every pair the catalogue marks so is `SDK_REMOVED`. |
   | `OUTSIDE_FREEZE` | `ruling`: the SDK section or obligation that lists it | The pair is on the SDK Preview list (obligation 6). |
   | `REFUSED` | `diagnostic`: an `EXT-PROC-NNNN` identifier of `DiagnosticId` | The processor refuses the declaration at build time. |
   | `EXCEPTION` | `ruling`: the SDK issue or document section that freezes it as declared without an effect | A named SDK ruling exists. |
   | `OPEN` | `planRow`: `0.12.0/<row>`, `0.13.0/<row>`, `W0-<n>`, `withdraw` or `outside-freeze` | The pair has no disposition yet. The reference names the coverage plan row, wave-0 decision or SDK request list that closes it. |

5. **The catalogue.** The test reads `META-INF/exeris/annotation-catalog.json` from the
   `exeris-sdk-annotations` jar on the processor's test classpath, which is the version the BOM pins.
   It fails unless `catalogFormat` is 1. It reads only structural keys, never prose.
6. **The Preview list.** Until the catalogue carries a stability field,
   `sdk-preview-surfaces.json`, beside the registry, transcribes the SDK's Preview surfaces. It holds
   `sdkVersion`, which must equal the catalogue's; `source`, naming the SDK `docs/stability-matrix.md`
   and MIGRATION §2; and `surfaces`, by qualified name or qualified name and attribute. Every
   `OUTSIDE_FREEZE` row is a Preview surface, and every Preview surface is `OUTSIDE_FREEZE` or
   `REFUSED`. A surface this repository has asked the SDK to move to Preview stays `OPEN` with
   `planRow: outside-freeze` until the SDK lists it. Once the catalogue carries the field, the test
   reads it and fails while the transcript exists.
7. **Agreement with the strict-audit registries.** `INERT_ATTRIBUTES`, `INERT_ANNOTATIONS`,
   `UNREAD_NOTES`, `EXTRACTED_ANNOTATIONS` and `TYPE_LEVEL_EXTRACTION` stay Java constants and remain
   what `-Aexeris.strict` runs from. The registry neither generates nor replaces them. The test fails
   unless:
   - an annotation-level `EMITTED` row names an annotation in `EXTRACTED_ANNOTATIONS` and not in
     `INERT_ANNOTATIONS`;
   - an annotation-level row in `OPEN`, `EXCEPTION`, `SDK_REMOVED` or `OUTSIDE_FREEZE` names an
     annotation that strict mode reports: it is in `INERT_ANNOTATIONS`, or it is absent from
     `EXTRACTED_ANNOTATIONS` (for a `NON_TYPE` row, it is in `TYPE_LEVEL_EXTRACTION`);
   - for an annotation whose annotation-level row is `EMITTED`, an attribute has an
     `INERT_ATTRIBUTES` entry exactly when its state is `OPEN`, `EXCEPTION`, `SDK_REMOVED` or
     `OUTSIDE_FREEZE`;
   - every `UNREAD_NOTES` key names an annotation whose row is not `EMITTED`, and that is not
     extracted on the element kind the note covers.
8. **Reachability.** For every simple name in `INERT_ATTRIBUTES` and every entry of
   `INERT_ANNOTATIONS`, a processor compilation under `-Aexeris.strict` places the annotation and
   asserts the warning. A structural assertion makes the set of names the fixtures cover equal to the
   set of names in the registries. An entry with no audit call site therefore fails the build.
9. **References resolve.** Each `java:` id is a row, not a retired row, of
   `exeris-codegen-java/src/main/resources/META-INF/exeris/generator-catalogue.json`. Each `ts:` path
   is in `docs/generators.md`'s TypeScript table. Each `diagnostic` is a `DiagnosticId`. Each
   `planRow` names a row or list in `docs/1.0.0-annotation-coverage-plan.md`. The test reads the
   repository files by path, so `exeris-processor` gains no dependency.
10. **Failure output.** One run lists every failure. A missing pair is printed as a ready-to-paste
    row whose `planRow` is a placeholder the test refuses, so an SDK bump fails until each new pair
    has a disposition.
11. **Change discipline.** A change that makes a generator start or stop consuming an attribute edits
    its row, its strict-audit entry and its `contract-coverage.ts` row in the same change. A change
    that adds a refusal moves the row to `REFUSED` with the new identifier.
12. **The cut.** For 1.0.0, the test passes against the SDK 1.0.0 catalogue with no `OPEN` and no
    `SDK_REMOVED` rows, `REFUSED` rows for `@Schedule` alone, and `EXCEPTION` rows each citing its
    ruling.

## Consequences

### ✅ Positive Outcomes

- **[+] A census instead of a lower bound.** Every pair of the pinned SDK has a state, and the number
  of open pairs is a query on one file.
- **[+] The strict audit cannot drift silently.** An attribute that reaches no output and has no
  warning, or a warning on an attribute that now reaches output, fails the build.
- **[+] It moves with the pin.** The test reads the file the SDK publishes, so an SDK bump surfaces
  its new pairs at once.
- **[+] The SDK requests are filed from data.** The withdrawal and outside-freeze lists are the
  `OPEN` rows with those references.

### ⚠️ Trade-offs

- **[-] About eleven hundred hand-written rows.** Inheritance and the ready-to-paste output reduce the
  work; they do not write the disposition.
- **[-] Two records of one fact.** An inert attribute is both an `OPEN` row and an `INERT_ATTRIBUTES`
  entry. The agreement test holds them together, at the cost of two edits per change.
- **[-] Every SDK bump fails the build** until its new pairs are classified, and until the Preview
  transcript is re-stamped.
- **[-] `EMITTED` names a consumer; it does not prove an effect.** The processor-compiled fixture each
  emitting row adds (coverage plan, *What the test cannot prove*) remains the proof.

### 📋 What is NOT in scope

- The disposition of any individual pair. The coverage plan and its wave-0 decisions decide those.
- The content of the SDK requests (exeris-sdk#278, #283).
- AST record components no annotation fills (the SDK's record-growth rule and ADR-042).
- Inertness specific to one form of an annotation, such as `name` on a nested-class `@DomainEvent`,
  which its own diagnostic reports.
- A TypeScript generator catalogue (ADR-097 keeps `outputRoot: ts` reserved).

### 🚫 Non-Goals

- **Driving the processor.** The registry is not read at javac time, and the strict audit keeps its
  Java registries.
- **Deriving the registries from generator code.** Generators read JSON through `MetadataLoader`, and
  consumption is recorded by hand.
- **Merging with `contract-coverage.ts`.** That table classifies `DomainMetadata` keys, a different
  unit from annotation attributes.
- **Publishing the registry.** It is not in a jar, and its format carries no compatibility promise to
  another repository.

### ⚠️ Risks and Assumptions

- **Assumes:** the SDK keeps publishing `annotation-catalog.json` in `exeris-sdk-annotations` with
  `qualifiedName`, `attributes[].name`, `repeatableContainer`, `memberValueOnly` and
  `deprecated.forRemoval`, and moves `catalogFormat` when they change.
- **Assumes:** the test classpath resolves the SDK version the BOM pins. A drifted local repository
  can test against a different catalogue, and CI is the reference.
- **Risk:** a row is marked `EMITTED` on a generator that reads the metadata key without the value
  changing output. The emitting row's non-default fixture is the check; the registry cannot detect it.
- **Risk:** the Preview transcript lags an SDK patch that changes the list without a version the test
  compares. Re-stamping is part of every SDK pin change.
- **Reversed by:** a decision that 1.0.0 may freeze an attribute with no effect (W0-1 withdrawn),
  which removes the registry's purpose; or a mechanical census in which each attribute's effect is
  proved by a processor-compiled non-default fixture, which makes hand-written states redundant.

## Cross-references

- ADR-015 — output stability; an `EMITTED` row is a commitment under it.
- ADR-042 — no extraction ships without the SDK reader half; decides when an `OPEN` row can close.
- ADR-095 — the `EXT-PROC` identifiers a `REFUSED` row cites.
- ADR-097 — the generator catalogue whose row ids `emittedBy` cites.
- ADR-047 — field-level `@UI`, the `NON_TYPE` exception.
- `docs/1.0.0-annotation-coverage-plan.md` — dispositions, wave-0 decisions, rows G1 and G2.
- `docs/0.12.0-release-plan.md` — rows W1, G1, G2, V8.
- `exeris-sdk` `docs/modules/04-annotation-catalog.md`, `docs/stability-matrix.md`; exeris-sdk#283.

## Engineering Protocol

1. `AnnotationDispositionRegistryTest`, in `exeris-processor` beside `StrictAuditRegistryKeysTest`,
   enforces obligations 1 to 7, 9 and 10.
2. The strict-mode reachability tests in `ExerisDomainProcessorTest` enforce obligation 8.
3. `.agents/skills/exeris-tooling-strict-audit-review` names the registry as the record each
   `INERT_*` edit is checked against; `docs/modules/02-processor.md` and `docs/glossary.md` describe it.
4. No public Java API changes, so no `accepted-api-changes.json` entry. Generated output does not
   change, so no migration fragment. Changes to `-Aexeris.strict` output are listed in the release
   notes.
5. Migration owner: `exeris-tooling`, target 0.12.0, rows G1 then G2.
