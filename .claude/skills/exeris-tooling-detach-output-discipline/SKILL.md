---
name: exeris-tooling-detach-output-discipline
description: Generated-output lifecycle discipline for exeris-tooling. Use whenever a change touches how code is emitted to or pruned from src/main/generated, proposes "always regenerate" semantics, or affects the L1-committed / L2-detach story (OutputWriter, the codegen Maven plugin generate/detach mojos, the generated-output pruner, mvn clean interactions). Guards hard-constraint #6.
---

# Exeris Tooling Detach-Output Discipline

## Purpose
Protect hard-constraint #6: **generated code is committed.** Emission lands in
`src/main/generated/` (L1) and stays in the repo until a user app runs
`exeris:detach` (L2). The pipeline must NOT be designed assuming "always regenerate" —
the detachment story (and the reattach provenance-manifest RFC) is load-bearing for
downstream user apps that edit, diff, and own the generated tree.

## When to Use
- Any change to `OutputWriter` or the filesystem write path.
- Any change to the codegen Maven plugin `generate` / `detach` mojos.
- Any change to the generated-output pruning logic, or anything that decides which
  committed files get removed (esp. interactions with `mvn clean` wiping the L1 tree).
- Any proposal whose premise is "the output is always regenerated so X is safe".
- Any change to provenance/manifest emission tied to the reattach (edit-safety) story.

## Required Inputs
- The diff for the emission/pruning/plugin change.
- Whether the change assumes regeneration-on-every-build or respects committed L1.
- For pruning: what set of files is deleted, and on which lifecycle phase.

## Review Procedure
1. **Regenerate-assumption scan** — does any new logic treat `src/main/generated` as
   disposable/always-rebuilt? Flag it. Downstream apps may have detached (L2) and own
   that tree; clobbering user-owned files is data loss.
2. **Pruning safety** — a pruner must delete only files it provably emitted this run
   (or tracked via the generation manifest), never user-authored or detached files.
   Confirm the deletion set is bounded by provenance, not by directory glob.
3. **`mvn clean` interaction** — verify the `generate-sources`-before-`compile` ordering
   and `mvn clean` cannot deadlock on stale metadata, nor silently wipe the committed L1
   tree without intent. Name the guard.
4. **Detach/reattach respect** — edit-safety is via detach-time provenance manifest, not
   regenerate-and-compare (see the reattach RFC). Don't introduce a
   regenerate-and-overwrite path that bypasses the manifest.
5. **Determinism tie-in** — committed output only stays diff-clean if emission is
   deterministic; if this change alters emission, hand off to
   `exeris-tooling-codegen-determinism-review`.
6. **Decision and report** — produce one of: `APPROVE`, `CONDITIONAL`, `REJECT`.

## Decision Logic
- **APPROVE**: Committed-L1 semantics preserved; pruning bounded by provenance; no
  always-regenerate assumption; clean/ordering guard named.
- **CONDITIONAL**: A bounded fix (e.g. scope the pruner to the manifest, add a clean guard).
- **REJECT**: Always-regenerate assumption that could clobber detached/user-owned files,
  or unbounded directory-glob deletion of the committed tree.

## Completion Criteria
- Regenerate-assumption and pruning-bound checks performed.
- `mvn clean` / phase-ordering interaction assessed.
- Determinism handoff made if emission style changed.
- Verdict and minimal remediation provided.

## Review Output Template
1. **Scope analysed** (OutputWriter / mojo / pruner / manifest touched)
2. **Lifecycle findings** (regenerate assumption? L1/L2 respected?)
3. **Pruning safety** (deletion set bound; clean-phase interaction)
4. **Determinism handoff** (needed? to which skill)
5. **Verdict** (`APPROVE` / `CONDITIONAL` / `REJECT`)
6. **Required actions** (precise and minimal)

## Non-Negotiable Rules
- Never design the pipeline assuming "always regenerate" — the detachment story is load-bearing.
- Never let a pruner delete by directory glob what it cannot prove it emitted.
- Never bypass the detach-time provenance manifest with regenerate-and-overwrite.
