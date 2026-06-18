---
description: Enforce single-target story — Exeris kernel only. Refuse any reintroduction of Spring/Quarkus/Micronaut/Vanilla backend generators.
argument-hint: PR diff or generator/codegen change to audit
---

Run the **`exeris-tooling-kernel-target-discipline`** skill against the change below.
That skill owns the single-target story, backend-shape/import/strategy scan, cross-repo
redirect to `exeris-spring-runtime`, and ADR requirement — this command is the explicit
entry point; do not restate the rules here (single source, to avoid drift).

Change:
$ARGUMENTS
