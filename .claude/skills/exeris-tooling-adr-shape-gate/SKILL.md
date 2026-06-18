---
name: exeris-tooling-adr-shape-gate
description: Decision-document shape gate for exeris-tooling. Use BEFORE drafting an ADR, RFC, or research note — picks the right shape (Research vs RFC vs ADR) for the question, enforces number reservation in the central adr-index.md, and blocks refactor-only or measurement-missing material from becoming an ADR. Invoke whenever someone asks to "write an ADR", "document this decision", or capture a design choice.
---

# Exeris Tooling ADR Shape Gate

## Purpose
Stop the common failure of jumping straight to an ADR when the question is actually a
*research* question (needs measurement) or an *RFC* question (needs option comparison).
Three shapes for three question kinds — they are **not interchangeable** (top-level
CLAUDE.md, `exeris-docs/templates/`):

- **Research** (`RESEARCH-TEMPLATE.md`) — falsifiable hypothesis, lab-notebook shape,
  JMH/JFR-driven. Branch-scoped (`research/<slug>`), **no** central registry.
- **RFC** (`RFC-TEMPLATE.md`) — multi-option strategic question. Filename
  `RFC-YYYY-MM-DD <Short Title>.md`, **no** central registry.
- **ADR** (`ADR-TEMPLATE.md`) — decision **already made**. Filename
  `ADR-NNN <Short Title>.md`. **Enters** the registry.

## When to Use
- A request to "write an ADR", "document this decision", "capture the design choice".
- Before drafting any decision doc in `docs/adr/` or an RFC/research note.
- When unsure whether a change even warrants a decision doc (refactor-only → none).

## Decision Procedure
1. **Is the decision actually made?** If options are still open → **RFC**, not ADR.
2. **Does it hinge on a measurement not yet taken?** (perf claim, allocation, latency)
   → **Research** first; the ADR cites its result later.
3. **Is it refactor-only?** (rename, extract, dedup, no contract/shape change) →
   **no ADR** — goes in the PR description / commit history. Per CLAUDE.md, refactor-only
   ADRs are explicitly out of scope.
4. **Does it change pipeline shape, the processor↔generator contract, emitter parity, or
   kernel-target discipline?** → **ADR is warranted** (these are the triggers named in
   `exeris-tooling/CLAUDE.md`).
5. **Registry discipline (ADR only)** — RESERVE the number in
   `~/exeris-systems/exeris-docs/adr-index.md` FIRST, then write content. Single numbering
   namespace, chronological by decision date. Tooling ADRs live in `docs/adr/`; cross-repo
   ADRs leave a `docs/adr/ADR-NNN.link.md` stub in each affected repo.
6. **Visibility** — tag `public` or `enterprise-private` per the visibility taxonomy
   (ADR-020, indexed in `~/exeris-systems/exeris-docs/adr-index.md`). `public-staged` is
   deprecated; do not use it.
7. **Business vs tech** — legal/IP/financial/procurement decisions do NOT enter the public
   tech registry; they live in the private business registry and are referred to
   descriptively. Keep them out of `adr-index.md`.

## Decision Logic
- **ADR**: decision made, non-refactor, touches a named tooling trigger → reserve number, draft.
- **RFC**: options still open / strategic multi-way → `RFC-YYYY-MM-DD …`, no registry entry.
- **RESEARCH**: blocked on a measurement → `research/<slug>` branch, lab-notebook shape.
- **NONE**: refactor-only → PR description, no doc.

## Completion Criteria
- Question shape identified with a one-line justification.
- For ADR: number reserved in `adr-index.md` BEFORE content; visibility tagged; `.link.md`
  stubs named for any cross-repo reach.
- For non-ADR: the correct alternative shape (or "none") named.

## Output Template
1. **Question shape** (`ADR` / `RFC` / `RESEARCH` / `NONE`) + 1-line why
2. **Trigger check** (which tooling trigger, if any, makes it ADR-worthy)
3. **Registry action** (number reserved? visibility? cross-repo stubs?)
4. **Filename + location**
5. **Next action** (minimal)

## Non-Negotiable Rules
- Never draft an ADR for an open question — that's an RFC.
- Never draft an ADR for a refactor-only change.
- Never write ADR content before the number is reserved in `adr-index.md`.
- Never put a business (legal/IP/financial) decision into the public tech registry.
