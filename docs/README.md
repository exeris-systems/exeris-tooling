---
title: Documentation index
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Documentation index

`exeris-tooling` is the build-time pipeline of the Exeris ecosystem. An annotation processor reads an application's `@ExerisDomain` sources inside `javac` and writes `DomainMetadata` JSON; Java generators turn that JSON into a kernel application, and the TypeScript generator turns it into an Angular application. The generated code is committed into the application (L1) until the application detaches it and owns it (L2). Find the page that owns an answer below. Each fact has one home; the other pages link to it.

## Reading paths

**Building an application with the tooling.** Start at the repository [README](../README.md) for the quick start on the starter, then [architecture](architecture.md) for what is generated and why. Look up a generated file in the [generator catalogue](generators.md), a build message in the [diagnostics reference](diagnostics.md), and what a tooling upgrade changes in [MIGRATION-0.x-to-1.0.md](MIGRATION-0.x-to-1.0.md). The [stability matrix](stability-matrix.md) says what each surface promises.

**Contributing to the tooling.** Read [CONTRIBUTING](../CONTRIBUTING.md) for the build and the gates, [architecture](architecture.md) for the boundaries, then the [module page](modules/README.md) of the module you change. The decisions are in [`adr/`](adr), and the [review rules](repo-review-rules.md) are what review checks. A change a consumer sees after regenerating adds a fragment under [`migration/`](migration/README.md).

**Working here as an agent.** Start at [`AGENTS.md`](../AGENTS.md), which indexes the policies, references, skills and workflows under `.agents/`. The policies are binding and these pages link to them rather than restating them.

## Where each answer lives

| Question | Page |
|---|---|
| How do the modules fit together, and what does the tooling never do? | [architecture.md](architecture.md) |
| What does one module guarantee, and how does it fail? | [modules/README.md](modules/README.md) |
| What does a term such as abstain, inert or L2 mean? | [glossary.md](glossary.md) |
| Which surface is promised to stay, and under which ruling? | [stability-matrix.md](stability-matrix.md) |
| Which generator wrote a given generated file, and when is it written? | [generators.md](generators.md) |
| What does an `EXT-` identifier mean, and what is the fix? | [diagnostics.md](diagnostics.md) |
| What changes on regeneration after an upgrade? | [MIGRATION-0.x-to-1.0.md](MIGRATION-0.x-to-1.0.md), and the open train's fragments in [migration/](migration/README.md) |
| Why was a decision made? | The records in [`adr/`](adr), for example [ADR-015](adr/ADR-015-codegen-emission-strategy.md) |
| Which options were weighed before a decision? | The records in [`rfc/`](rfc), for example [RFC-2026-10-08](rfc/RFC-2026-10-08-route-authorization-policy-emission.md) |
| What is in the release being built now? | [0.11.0-release-plan.md](0.11.0-release-plan.md) |
| What is planned, and in what order? | [ROADMAP.md](../ROADMAP.md) |
| What does review check in this repository? | [repo-review-rules.md](repo-review-rules.md) |
| How is the repository built, tested and contributed to? | [CONTRIBUTING.md](../CONTRIBUTING.md) |
| How is the TypeScript generator used on its own? | [exeris-codegen-ts/README.md](../exeris-codegen-ts/README.md) |
| What contract do agents working here follow? | [AGENTS.md](../AGENTS.md) |

The closed plans in this directory, `0.10.0-release-plan.md`, `codegen-ts-track-plan.md` and `generation-expansion-kernel-free-menu.md`, are kept as superseded records and describe no current work.

## Maintaining these docs

The rules for these pages, including frontmatter, link form, one home per fact and when to bump `last-verified`, are owned by the [documentation style guide](https://github.com/exeris-systems/exeris-docs/blob/main/standards/docs-style-guide.md). A change that adds or moves an emitted file adds its row to [generators.md](generators.md) in the same change, and a new diagnostic its row to [diagnostics.md](diagnostics.md); tests fail otherwise.

These pages describe the code on `main`. A page is only as current as its `last-verified` date, and a date means the whole page was checked against the code that day; a partial edit does not move it.

## Where this does not apply

This index covers documentation in this repository. The SDK records the processor writes, the kernel the generated code runs on, and the ecosystem-wide ADR registry are documented in [exeris-sdk](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/README.md), [exeris-kernel](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/architecture.md) and [exeris-docs](https://github.com/exeris-systems/exeris-docs/blob/main/adr-index.md).

The cost: the question-to-page table is a second index beside the pages it points at, so a new or renamed page must be added here too, or a reader following the table misses it.
