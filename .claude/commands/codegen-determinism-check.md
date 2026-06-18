---
description: Check that an exeris-tooling change preserves deterministic codegen output — same DomainMetadata → byte-identical emission.
argument-hint: PR diff or files in exeris-codegen-* touched by the change
---

Run the **`exeris-tooling-codegen-determinism-review`** skill against the change below.
That skill owns the determinism contract, review procedure, decision logic, and output
template — this command is the explicit entry point; do not restate the rules here (single
source, to avoid drift).

Change:
$ARGUMENTS
