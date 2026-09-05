---
# DO NOT EDIT — generated from .agents/workflows/consumer-build-contract-audit.md (agents-md-schema.md rule 7). Edit the source.
description: Audit emitted code for consumer-build requirements across logging, testing, and capability verification.
argument-hint: Modified generators, scaffold, or PR diff
---
<!-- DO NOT EDIT. Generated from .agents/workflows/consumer-build-contract-audit.md by the AGENTS.md adapter step
     (agents-md-schema.md rule 7). Edit the source, not this file. -->
Review emitted code against downstream consumer build constraints.

Priorities:
1. Logging facade (ADR-060): verify `System.Logger` usage, `{0}` / `{1}` placeholders, and quote escaping via `KernelScaffold.escapeQuotes`. Ensure no slf4j dependencies are required.
2. Generated tests (ADR-058): verify generated test sources land in `src/test/generated/java`, import only JUnit 5 and AssertJ, use emitted doubles rather than mock frameworks, and are guarded by the `exeris.tests` flag.
3. Cap-tier Wall (ADR-055): verify `CapTierWall` uses the JDK Class-File API without third-party dependencies, preserving the full five-source extraction union.
4. Constructor contracts: verify emitted service constructors remain public, non-final, and assignment-only for test subclassing.

Changed scope:
$ARGUMENTS

Please produce:
- Affected generator / verification components
- ADR-055 / ADR-058 / ADR-060 compliance check
- Consumer build impact analysis
- Final verdict: APPROVE / CONDITIONAL / REJECT
