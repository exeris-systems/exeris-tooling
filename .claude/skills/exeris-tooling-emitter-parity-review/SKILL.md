---
name: exeris-tooling-emitter-parity-review
description: Java/TS emitter parity review for exeris-tooling. Use when DomainMetadata shape changes, when a new generator surface is added on one side, or when one side starts consuming a field the other does not emit.
---

# Exeris Tooling Emitter Parity Review

## Purpose
Enforce: DomainMetadata is the sole contract between processor and generators; shared surfaces stay aligned across `exeris-codegen-java` and `exeris-codegen-ts`.

The TS package is intentionally outside the Maven reactor — coordination is manual and explicit. This skill is the manual gate.

## When to Use
- Any PR that adds, removes, or renames a `DomainMetadata` field.
- Any PR that adds a new generator shape on one side (Java or TS) consuming a field the other side does not emit.
- Any PR that introduces a new metadata-driven feature (validation extension, action trigger family, event shape).
- Any PR that touches `MetadataLoader` or `GeneratorRegistry`.

## Required Inputs
- PR diff scoped to processor (DomainMetadata producer) and generators (Java + TS consumers).
- Statement of intent: is this shared surface or one-sided?
- Cross-build status: did the author run `mvn install` AND `cd exeris-codegen-ts && npm test`?

## Review Procedure
1. **Identify the metadata change** — what field/shape was added, removed, renamed, or had its semantics shifted?
2. **Classify surface scope** — `JAVA_ONLY`, `TS_ONLY`, `SHARED`. Most DomainMetadata fields are SHARED by default.
3. **For SHARED**: check both sides emit / read consistently. Missing TS side = parity gap.
4. **For JAVA_ONLY / TS_ONLY**: require explicit justification — why is this not shared?
5. **Migration check** — does `docs/MIGRATION-0.x-to-1.0.md` (or future MIGRATION) need an entry? Downstream user apps regenerate; user-visible field renames are a migration story.
6. **Cross-build evidence** — was both `mvn install` AND `npm test` run? If only one, flag.
7. **Decision and report** — produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.

## Decision Logic
- **APPROVE**: Surface is shared and both sides updated; OR surface is genuinely one-sided with explicit rationale; cross-build evidence present.
- **CONDITIONAL**: Parity gap with a named follow-up issue and a documented temporary divergence.
- **REJECT**: Unannounced asymmetry, missing cross-build evidence, or shared-by-default field added on one side only without rationale.

## Completion Criteria
- DomainMetadata change identified and classified.
- Both Java and TS consumer surfaces audited.
- Cross-build evidence confirmed or flagged.
- Verdict and follow-ups recorded.

## Review Output Template
1. **Metadata change** (field, type, semantics)
2. **Surface classification** (`JAVA_ONLY` / `TS_ONLY` / `SHARED`)
3. **Java-side coverage** (which generators read this; gaps)
4. **TS-side coverage** (which generators read this; gaps)
5. **Cross-build evidence** (`mvn install`, `npm test` — present / missing)
6. **Migration impact** (downstream regen story; MIGRATION entry needed?)
7. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
8. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Don't propose merging Java + TS builds to "solve" parity — the split is intentional per top-level `CLAUDE.md`.
- Don't approve shared-surface changes without both `mvn install` and `npm test` evidence.
- Don't silently downgrade a SHARED field to one-sided.
