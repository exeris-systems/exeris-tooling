---
title: Developer guides
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-04
---

# Developer guides

**Audience:** you build an application from `@ExerisDomain` entities with exeris-tooling and want to see, file by file, what the tooling generates from them and what is left to you.

The guides walk the SDK's example corpus, `exeris-sdk-examples` at tag [`v0.13.3`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/README.md), through this repository's pipeline. That tag is the SDK version `exeris-app-bom` pins. `SdkExamplesE2ETest` in `exeris-e2e-tests` runs the same corpus through the processor, the Java pipeline, `javac` and the emitted tests on every build, so each guide shows output that a gate produces, not output written by hand.

These guides are not normative. The contract of each module is in [`docs/modules/`](../modules/README.md), each generated file has its row in the [generator catalogue](../generators.md), each build message its row in the [diagnostics reference](../diagnostics.md), and the decisions are in [`docs/adr/`](../adr). A guide that touches one of those links to it instead of restating it.

## Pick your path

| Guide | Read it when |
|---|---|
| [01 Add the tooling to a build](01-add-the-tooling-to-a-build.md) | You need the processor and the codegen goals in a Maven build, the versions they pin, and the layout the processor requires of an entity. Start here. |
| [02 Generate an application from an entity](02-generate-an-application.md) | You want to see what one entity becomes: routes, handler, service, repository, migration, events, saga and tests, and where they are written. Uses `ordering`. |
| [03 Reference other entities](03-reference-other-entities.md) | Your entities point at each other, are tenant-owned and audited, and one action is public. Uses `invoicing`. |
| [04 Project into a graph](04-project-into-a-graph.md) | You declare `@Graph` and `@GraphEdge` and want to know what the generated graph sync writes and deletes. Uses `graph`. |
| [05 Generate the frontend](05-generate-the-frontend.md) | You generate the Angular application from the same metadata, with a `@View` page, tabs, groups and a navigation entry. Uses `presentation`. |
| [06 Read a capability-graph refusal](06-read-a-capability-graph-refusal.md) | You declare a `@CapabilityModule` and generation stops on `EXT-PLUG-2201`. Uses `capability`. |

The SDK side of the same corpus, how to write each annotation and how its reader reads it back, is in the [SDK guides](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/guides/README.md). Running the generated application on the kernel is in the [kernel guides](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/guides/README.md).

## What these guides promise

Every guide carries a verification header naming the tooling commit and the SDK tag it was checked against. A snippet of example source carries a `Source:` line with the file at the tag and the symbol. A snippet of generated output carries a `Generated:` line with its path relative to the run's workspace, `exeris-e2e-tests/target/sdk-examples/app/`. Both say whether the snippet is quoted verbatim or adapted. Commands you run are not citations.

If a snippet and its source disagree, the source wins and the guide is the defect. Where the generated output leaves something undone, the guide says so in a "Not available today" section. Nothing here describes a target state.

## Maintaining these guides

**Regenerate the quoted output before you edit a page.** From the repository root, with an `exeris-sdk` checkout of the pinned tag:

```bash
mvn -B install -DskipTests
mvn -B -pl exeris-e2e-tests test -Dtest=SdkExamplesE2ETest \
    -Dexeris.sdk.examples.dir=<exeris-sdk checkout at v0.13.3>
```

The generated Java, the emitted tests and the processor metadata are then under `exeris-e2e-tests/target/sdk-examples/app/`. The frontend of guide 05 is generated from that metadata as the `sdk-examples-app` job in `.github/workflows/build.yml` does it.

**Citations are a path plus a symbol, never a line number.** A line range rots on every unrelated edit to the same file.

**Bump the SDK tag in every page at once.** When `exeris-app-bom` moves to another SDK version, `SdkExamplesE2ETest` reads the corpus at that tag, and every `Source:` link and verification header here names the old one until it is edited.

**Bump `last-verified` only after rerunning the commands above and checking each quoted snippet against the output**, as the [documentation style guide](https://github.com/exeris-systems/exeris-docs/blob/main/standards/docs-style-guide.md) says.

## Where these guides stop

- **The normative pages.** [Architecture](../architecture.md), [modules](../modules/README.md), [generator catalogue](../generators.md), [diagnostics](../diagnostics.md), [stability matrix](../stability-matrix.md) and the [glossary](../glossary.md) own the facts these guides lean on.
- **Upgrades.** What regenerating after a tooling upgrade changes is in [MIGRATION-0.x-to-1.0.md](../MIGRATION-0.x-to-1.0.md).
- **Scenarios with no main sources.** The SDK corpus also names `studioedit` and `tckbinding`, which have no main sources at the tag, so the pipeline has nothing to read from them and no guide here covers them.
- **The cost of the approach.** Quoting generated output makes every guide depend on one run of one corpus. A generator change that moves a quoted line makes the guide wrong until someone reruns the commands above, and no test reports it.
