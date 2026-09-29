---
title: "Review rules for exeris-tooling"
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-09-27
---

# Review rules for `exeris-tooling`

The `repo-routine` extension of `exeris-systems/.github`'s `docs-guardrails-review.md`, applied
**after** its parts and under its severity tags, output format and verdict schema. It adds checks
and raises severities; it lowers nothing and skips nothing. One review, one verdict, one publisher.

**The criteria are not authored here.** They are owned by the policies under `.agents/policies/`,
which `AGENTS.md` names, and each has a review skill under `.agents/skills/` that says how to apply
it. This file names the questions a reviewer must reach for and the severity each answer carries.
Read the policy a rule names before applying it: a rule copied here would be a second place to
author it, which `agents-md-schema.md` rule 2 forbids.

## What this repository is answerable for

The build-time pipeline from annotated source to emitted code: the annotation processor that reads
`javax.lang.model` and writes `DomainMetadata`, and the Java and TypeScript emitters that read it.
Its output is committed into other people's repositories and compiled by builds this repository
never sees, so a defect here ships as their code. The shared routine's `code` part judges whether a
change is correct. What it cannot know is what the emitted code owes the consumer that commits it,
and that is what follows.

## Step T — rules of this repository

T1. **Emitted output is deterministic** (`codegen-determinism.md`). A timestamp, a random value, an
    iteration over an unordered collection, a locale-dependent format or a platform path separator
    reaching emitted text → `[HARD BLOCK]`. The consumer commits the output, so a byte that changes
    between runs is a diff in their history that nobody made.

T2. **`DomainMetadata` is the only contract** (`domain-metadata-contract.md`). An emitter reading
    `Element`, `TypeMirror` or anything under `javax.lang.model`, or a record that shadows one of
    `exeris-sdk-source-model`'s → `[HARD BLOCK]`.

T3. **The processor runs at build time only** (`processor-build-time-only.md`, `scoped-bans.md`). A
    runtime library, a kernel runtime dependency or an IoC container on `exeris-processor`'s
    classpath, or a class loaded from the consumer's classpath → `[HARD BLOCK]`. A diagnostic that
    uses `e.getMessage()`, or per-entity output that is not behind `-Aexeris.verbose` →
    `[CONTRACT]`.

T4. **One target: the Exeris kernel** (`kernel-target-only.md`). A generator, emitter or abstraction
    for a second backend, Spring included → `[HARD BLOCK]`. Spring hosting is
    `exeris-spring-runtime`'s.

T5. **The Java and TypeScript emitters move together** (`emitter-parity.md`). A metadata field,
    action, constraint, event or route rule added to one emission path with no handling in the other
    and no stated reason it applies to one side only → `[CONTRACT]`.

T6. **Emitted code builds in a consumer project this repository does not see**
    (`consumer-build-contracts.md`, `committed-generated-code.md`). From the Java emitters: logging
    that is not `System.Logger` with `MessageFormat` placeholders and doubled quotes, a test
    importing anything beyond JUnit 5 and AssertJ, or output written outside `src/main/generated/`
    and `src/test/generated/java`. From `exeris-codegen-ts`: output written outside its configured
    `outputPath`, `src/app/generated` by default. Each → `[HARD BLOCK]`. The consumer's build
    breaks, and nothing here fails.

T7. **A published artefact is complete.** This raises the shared routine's rule 28. The reactor
    deploys to Maven Central, so a published module that stops attaching its sources or javadoc jar,
    or a POM that loses metadata Central requires → `[HARD BLOCK]`: a release Central refuses cannot
    be retried under the same version.

## Where this does not apply, and what it costs

Not to correctness, scope, or the pull request's body and commits. The shared `code` and `pr` parts
judge those, and they are not restated here: a rule in two places drifts in one of them. Not to the
generated sample application or the emitted code as a product of its own; the rules above judge the
emitters, and the output only as evidence of what an emitter does.

The cost is that T1 to T7 are prose a reviewer applies, not a program. The review is handed
`repo-checks` output for the two scripts `.agents/manifest.yaml` names: `hook-deny-check.sh`, which
runs the deny hook against the commands it must refuse and let through, and
`eval-consistency-check.sh`, which holds `scenarios.yaml`'s `negative` tags to its cases. The tests
the policies name — `KernelCodegenCompileTest`, `GeneratedTestsE2ETest`, the determinism
regeneration — run only in the build checks on the pull request, which the reviewer cannot read. A
rule the reviewer could only check by running one of those tests is reported as unchecked, not as
passing.
