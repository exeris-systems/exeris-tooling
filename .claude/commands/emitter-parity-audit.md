---
# DO NOT EDIT — generated from .agents/workflows/emitter-parity-audit.md (agents-md-schema.md rule 7). Edit the source.
description: Audit Java and TypeScript emitters to verify capability parity for shared domain metadata surfaces.
argument-hint: Metadata changes, new annotations, or PR diff
---
<!-- DO NOT EDIT. Generated from .agents/workflows/emitter-parity-audit.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Review emitter changes across Java and TypeScript for shared surface parity.

Priorities:
1. Shared metadata scan: identify fields, actions, validations, events, or route access rules touched.
2. Java emitter coverage: verify `exeris-codegen-java` consumes and emits required structures.
3. TypeScript emitter coverage: verify `exeris-codegen-ts` consumes and emits matching Angular structures.
4. Divergence audit: verify any server-only or client-only omission is justified and documented.
5. Snapshot tests: verify snapshot assertions exist on both sides.

Changed scope:
$ARGUMENTS

Please produce:
- Shared metadata elements in scope
- Java emitter changes and corresponding TS emitter changes
- Identified parity gaps or contract bugs
- Final verdict: APPROVE / CONDITIONAL / REJECT
