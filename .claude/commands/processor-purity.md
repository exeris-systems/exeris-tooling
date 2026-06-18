---
description: Audit an exeris-processor change for build-time-only purity — no runtime classpath leakage, no Spring, no kernel runtime deps.
argument-hint: exeris-processor diff or files to audit
---

Run the **`exeris-tooling-processor-discipline-review`** skill against the change below.
That skill owns the processor purity rules, classpath/diagnostic/discovery audit, and
output template — this command is the explicit entry point; do not restate the rules here
(single source, to avoid drift).

Change:
$ARGUMENTS
