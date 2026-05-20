---
description: Audit an exeris-processor change for build-time-only purity — no runtime classpath leakage, no Spring, no kernel runtime deps.
argument-hint: exeris-processor diff or files to audit
---

Audit this `exeris-processor` change for build-time purity.

Processor purity rules:
- Depends only on JDK (`javax.lang.model`, `javax.annotation.processing`, standard library) and the SDK source model.
- NO runtime libraries (no Spring, no kernel runtime, no Jackson runtime types except where bounded to write `DomainMetadata` JSON).
- NO loading of classes from the user's project classpath — only the annotation surface and `javax.lang.model` are legitimate inputs.
- Self-registered via `@AutoService(Processor.class)` — do not introduce alternative discovery.
- Diagnostics go through `Messager` and stay actionable; `-Aexeris.verbose` opt-in for per-entity chatter.
- Error messages use `e.toString()` (always populated), not `e.getMessage()` (often `null` for JDK exceptions).

Change:
$ARGUMENTS

Please review:
1. Does this change pull a runtime library onto the processor classpath that it didn't need before?
2. Does it attempt to load or reflect on user-project classes outside `javax.lang.model`?
3. Are processing-failure diagnostics still actionable to a user reading raw `javac` output?
4. Is `-Aexeris.verbose` still the gate for per-entity chatter?
5. Minimal correction if purity is violated.
