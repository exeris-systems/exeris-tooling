---
name: exeris-tooling-strict-audit-review
description: Strict-mode completeness-audit review for exeris-tooling. Use whenever a generator (Java or TS) starts or stops consuming an annotation attribute or a whole annotation, when an SDK annotation surface changes, or when the INERT_ATTRIBUTES / INERT_ANNOTATIONS registries in ExerisDomainProcessor are touched. Catches stale registry entries that produce false "no effect" warnings under -Aexeris.strict.
---

# Exeris Tooling Strict-Audit Review

## Purpose
Keep the `-Aexeris.strict` completeness audit honest. Under `-Aexeris.strict`, the
processor warns when an annotation attribute — or a whole annotation — is *set in
user source* but consumed by *no generator* (well-defined because all SDK
annotations are `@Retention(SOURCE)`: erased from bytecode, so the build-time
pipeline is the only possible consumer; an unconsumed attribute has zero effect).

The audit is backed by two **hand-maintained** registries in
`ExerisDomainProcessor.java`:
- `INERT_ATTRIBUTES` — per-attribute (`@Field.dataType`, `@ActionParam.description`,
  `@ActionParam.required` at time of writing).
- `INERT_ANNOTATIONS` — whole-annotation (`@EventSourced`, until its generator lands).

The load-bearing rule (CLAUDE.md): **when a generator starts consuming one of these,
DELETE its registry entry in the same change.** A stale entry produces a *false*
"no effect" warning on an attribute that now matters — the inverse failure of the
audit it backs.

## When to Use
- A generator (any `*Generator.java` or TS emitter) starts reading a metadata field
  it did not read before — check whether that field has an `INERT_*` entry to delete.
- A generator stops reading a field, or a feature is reverted — an entry may need
  *re-adding*.
- An SDK annotation attribute is added/removed/renamed (the audit's input surface shifts).
- Any direct edit to `INERT_ATTRIBUTES` or `INERT_ANNOTATIONS`.
- A generator starts consuming a new action surface (e.g. a `methodName` or an
  `@ActionParam` attribute) — confirm no matching `INERT_*` entry survives the same
  change while its attribute is now rendered.

## Required Inputs
- The diff for the generator/processor change.
- Current `INERT_ATTRIBUTES` / `INERT_ANNOTATIONS` lists.
- The set of metadata fields the changed generator now reads vs. read before.

## Review Procedure
1. **Diff the consumed-field set** — for each generator in the change, list metadata
   fields/annotations it reads after vs. before.
2. **Cross-check against the registries** — for every newly-consumed field, confirm a
   matching `INERT_*` entry was *deleted* in the same change. A surviving entry for a
   now-consumed field is a **stale-entry bug** (false-positive strict warning).
3. **Reverse check** — for every field that *stopped* being consumed, confirm an entry
   was added (the audit would otherwise miss a genuinely-inert attribute).
4. **Parity of the audit** — a field consumed only on the Java side is *not* inert
   (TS-only consumption counts too, and vice versa). Don't delete an entry on the
   strength of one emitter unless the surface is genuinely one-sided.
5. **Note accuracy** — each entry's human note must still describe *why* it's inert; a
   note that now lies (e.g. "no emitter renders it" after one does) is a stale entry.
6. **Decision and report** — produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.

## Decision Logic
- **APPROVE**: Newly-consumed fields have their `INERT_*` entries removed; notes accurate;
  no field is both consumed and registered as inert.
- **CONDITIONAL**: A stale or missing entry with a named one-line fix (delete/add entry X).
- **REJECT**: A generator consumes a field whose `INERT_*` entry survives (the audit will
  emit a false "no effect" warning into real `javac` output), with no remediation.

## Completion Criteria
- Consumed-field delta computed for every changed generator.
- Each delta reconciled against both registries.
- Note-accuracy checked for any touched entry.
- Verdict and minimal remediation provided.

## Review Output Template
1. **Scope analysed** (generators / processor / registries touched)
2. **Consumed-field delta** (added / removed per generator)
3. **Registry reconciliation** (entries that must be deleted / added; stale entries found)
4. **Parity note** (is the consumption one-sided or shared?)
5. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
6. **Required actions** (precise registry edits)

## Non-Negotiable Rules
- Never leave an `INERT_*` entry for a field a generator now consumes — that is the
  canonical stale-entry regression.
- Never delete an entry on single-emitter evidence when the field could be consumed by
  the other emitter — verify both sides (ties into emitter-parity).
- The registries are hand-maintained on purpose; do not propose auto-deriving them from
  generator reflection — generators consume JSON via `MetadataLoader`, not typed reads.
