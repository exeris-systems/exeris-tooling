---
description: Check Java/TS emitter parity — shared DomainMetadata surfaces must be visible to both `exeris-codegen-java` and `exeris-codegen-ts`.
argument-hint: PR diff or DomainMetadata / generator surface changes
---

Run the **`exeris-tooling-emitter-parity-review`** skill against the change below.
That skill owns the parity rules, SHARED/JAVA_ONLY/TS_ONLY classification, cross-build
evidence check, and output template — this command is the explicit entry point; do not
restate the rules here (single source, to avoid drift).

Change:
$ARGUMENTS
