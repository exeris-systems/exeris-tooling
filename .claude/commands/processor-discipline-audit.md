---
# DO NOT EDIT — generated from .agents/workflows/processor-discipline-audit.md (agents-md-schema.md rule 7). Edit the source.
description: Audit annotation processor changes for classpath isolation and compiler contract compliance.
argument-hint: Modified processor classes or PR diff
---
<!-- DO NOT EDIT. Generated from .agents/workflows/processor-discipline-audit.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Review annotation processor changes against build-time isolation constraints.

Priorities:
1. Classpath isolation: verify no runtime frameworks (Spring, kernel runtime, Jackson databind) are on the processor classpath.
2. Input boundary: verify only `javax.lang.model` and `exeris-sdk-source-model` records are used.
3. Diagnostic quality: verify compiler diagnostics use `e.toString()` (not `e.getMessage()`).
4. Verbosity gating: verify entity-level logging is guarded by `-Aexeris.verbose`.
5. Strict mode compliance: verify newly extracted annotations update allowlists/denylists for `-Aexeris.strict`.

Changed scope:
$ARGUMENTS

Please produce:
- Affected processor classes
- Classpath and dependency assessment
- Diagnostic and strict-mode verification
- Final verdict: APPROVE / CONDITIONAL / REJECT
