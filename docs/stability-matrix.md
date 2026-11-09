---
title: Tooling stability matrix
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-08
---

# Tooling stability matrix

This page is the lookup index for what `exeris-tooling` promises about each of its public surfaces, and which ruling makes the promise. It states the commitment the ruling makes and links it; it does not restate the reasoning, which lives in the ADRs named in each row.

If this page and a ruling disagree, the ruling wins and this page is the defect.

## How to read the rows

The tooling has no tier vocabulary of its own, such as the kernel's `stable` and `preview` or the SDK's freeze tiers. Each row states the commitment in the words of its ruling. Two rules apply to every row unless the row says more:

- **The versioning policy** in [`ROADMAP.md`](../ROADMAP.md#versioning-policy): in **0.x** the generated code shape may change in any release, and consumers regenerate after every tooling bump; in **1.x** it changes only through additive minors, with a deprecation cycle for breaking changes. Output compatibility is the headline contract; the Maven plugin API is secondary.
- **The migration rule**: a change that alters what a consumer sees after regenerating adds a fragment under `docs/migration/<version>/` ([format](migration/README.md)), and the release assembles it into [`MIGRATION-0.x-to-1.0.md`](MIGRATION-0.x-to-1.0.md).

A row with no ADR in its ruling column is governed by those two rules alone.

## Generated output

| Surface | Commitment | Ruling | Gate |
|---|---|---|---|
| Emitted Java, SQL and YAML: paths, types, members, routes, migrations, OpenAPI | Source-compatible across 1.x; the emission technique (JavaPoet, text blocks) is what makes byte-level stability defensible. A breaking change takes an entry in the root `accepted-api-changes.json` and a migration fragment | [ADR-015](adr/ADR-015-codegen-emission-strategy.md); versioning policy | `KernelCodegenCompileTest`, `KernelCodegenE2ETest`, `EmittedLintGateTest`, `KernelOpenApiGoldenDocumentTest`, the boot tests |
| Behavior-level contracts of the emitted Java | Each is fixed by its own ADR, cited alongside ADR-015 when it changes: composition seam, route policy, write rejections, list route, streams, OpenAPI authentication, primary key, `@Blob` routes, generated tests, logging facade | [ADR-070](adr/ADR-070-generated-composition-root-seam.md), [ADR-105](adr/ADR-105-generated-route-policy-emission.md), [ADR-076](adr/ADR-076-write-rejection-status.md), [ADR-090](adr/ADR-090-reject-mismatched-tenant.md), [ADR-096](adr/ADR-096-generated-list-route-query-and-page-envelope.md), [ADR-044](adr/ADR-044-tooling-sse-stream-emitter-shape.md), [ADR-079](adr/ADR-079-emitted-openapi-authentication-claim.md), [ADR-104](adr/ADR-104-declared-primary-key-uuid-only.md), [ADR-108](adr/ADR-108-blob-field-routes.md), [ADR-058](adr/ADR-058-generated-test-emission-channel.md), [ADR-060](adr/ADR-060-generated-code-logging-facade.md) | The contract files and their tests ([e2e tests](modules/10-e2e-tests.md)) |
| Emitted TypeScript: file paths, exports, types and members, routes, service methods, component selectors, inputs and outputs, `data-testid` values, what a view renders for unchanged metadata, dependency majors in the emitted `package.json` | Every change is classified `none`, `additive` or `breaking (ADR-092)`. 0.x may break with a migration entry; 1.x minors are additive, and a break waits for a major after at least one minor of deprecation. A breaking change takes an entry in `exeris-codegen-ts/api/accepted-api-changes.json` | [ADR-092](adr/ADR-092-emitted-typescript-output-stability.md) obligations 2 to 8; [ADR-093](adr/ADR-093-emitted-forms-are-signal-forms.md) for forms | The TS specs and contract specs; view narrowing is judged by review, which ADR-092 names as a trade-off |
| Emitted file headers, both emitters | Carry no per-release value: no package version and no date | ADR-092 obligation 5; [codegen determinism](../.agents/policies/codegen-determinism.md) | `header-version-free.spec.ts`; `CodegenJavaArchitectureTest` (no clock) |
| Determinism of all output | Same metadata gives byte-identical output across runs, machines and locales | [Codegen determinism](../.agents/policies/codegen-determinism.md) | `LocaleIndependenceTest`, `CodegenCliJarIT`, `SdkExamplesE2ETest`, the CI regeneration of the SDK examples application |
| Generator catalogue rows | A row `id` names one path shape for as long as it exists and is never reused; moving a path retires its row and adds a new one. `catalogueFormat` increments only when a key is removed or changes meaning or type | [ADR-097](adr/ADR-097-generator-catalogue-and-launchable-generator.md) obligation 6 | `GeneratorCatalogueTest`, `GeneratorCatalogueConformanceE2ETest`, `generator-catalogue.spec.ts` |

## Build-time interfaces

| Surface | Commitment | Ruling | Gate |
|---|---|---|---|
| Diagnostic identifiers (`EXT-PROC`, `EXT-PLUG`, `EXT-GEN`, `EXT-INIT`) | An identifier names one meaning for as long as it exists, is never renumbered and never reused; a retired one stays unallocated; a severity change keeps it. The message text is not part of the contract | [ADR-095](adr/ADR-095-stable-diagnostic-identifiers.md) obligation 4; Amendment 2 for `INIT` | `DiagnosticIdTest` |
| Processor options `-Aexeris.verbose`, `-Aexeris.strict`, `-Aexeris.metadataOnly` | No ADR fixes the options. The identifiers they switch on are covered by ADR-095 | Versioning policy | The processor's unit tests, among them `ExerisDomainProcessorTest` and `KernelApplicationLayoutTest`; the TCK binding, which runs with `metadataOnly` |
| Metadata JSON: the `DomainMetadata` records and their wire rules | Owned by `exeris-sdk`, and frozen there under its record-growth rule | The [SDK stability matrix](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/stability-matrix.md) | The SDK's own guards; the processor's TCK binding |
| Metadata JSON: `schemaVersion` and `sourceDigest` beside the record fields | Written as siblings of the record's fields in each entity file | [ADR-042](adr/ADR-042.link.md) | The TCK parity suite |
| Metadata JSON: the file names (`<Entity>.json`, `enum_*.json`, `capability_*.json`, `view_*.json`) and the capability and view envelopes | No ADR fixes them. They are the processor's output layout, read by both generators | Versioning policy | Both generators' tests, which read processor-written fixtures |
| Maven plugin goals and parameters (`generate`, `verify-capabilities`, `verify-runtime`, `detach`) | No ADR fixes the goals or their parameters; the versioning policy calls the plugin API secondary to output compatibility, and ADR-092 names the plugin goals out of its scope | Versioning policy; [ADR-078](adr/ADR-078-runtime-driver-gate.md) for what `verify-runtime` checks | Plugin unit tests; `exeris-app-starter-it` |
| `exeris-codegen-cli` arguments and exit status | `--metadata-dir`, `--output-dir`, `--base-package`, `--tests`, `--test-output-dir`, `--integration-tests`, `--integration-test-output-dir`, `--allow-empty`, `--print-catalogue`; an unknown switch is an error; exit `0`, `1`, `2`; the version only in log output, never in a generated file | ADR-097 obligations 11 to 13; [ADR-058](adr/ADR-058-generated-test-emission-channel.md) Amendment 1 for the integration-test switches | `CliArgsTest`, `CodegenMainTest`, `CodegenCliJarIT` |
| `exeris-gen` flags and `exeris-codegen.json` keys | Part of ADR-092's surface: removing or renaming a flag or key, or changing its default so the same invocation emits less, is breaking | ADR-092 obligations 2 and 3 | `cli.spec.ts`, `config.spec.ts` |
| `@exeris/codegen-ts` version | Equals the reactor version at every commit and releases on the same tag | ADR-092 obligation 1 | `npm run check:version` |
| `exeris-initializr` command line | Follows ADR-097's command line: `--switch=value` only, exit `0`, `1`, `2`, an identifier on every non-zero exit; topologies `backend-only` and `e2e` | [ADR-091](adr/ADR-091-opt-in-application-starter.md) Amendment 5; ADR-095 Amendment 2 | The initializr unit tests; the `initializr-backend` and `initializr-e2e` invoker fixtures |

## Published Java and POM artifacts

| Surface | Commitment | Ruling | Gate |
|---|---|---|---|
| `exeris-app-bom`, `exeris-app-parent`, `exeris-app-starter` | The BOM names the tested tooling, kernel and SDK triple as literal versions, and manages H2 for the generated integration tests; the parent binds the processor and the codegen goals, and Failsafe where the integration-test root exists; the starter carries the dependencies emitted code imports and no JDBC driver, logging backend or test library | ADR-091 obligations 1 to 4 and Amendments 2, 4 and 7 | `exeris-app-starter-it`; `tools/release-readiness/release-readiness.sh` |
| Public types of `exeris-codegen-core` and `exeris-codegen-java`, for example `KernelArtifactGenerator` and `CodegenPipeline` | ADR-097 records that `KernelArtifactGenerator` is public and carries a 1.0 stability commitment. No ADR enumerates the rest | Versioning policy; ADR-097 obligation 9 | None: the reactor runs no binary-compatibility check |
| Maven coordinates | Every module is published as `eu.exeris:<artifactId>` from 0.10.0; releases up to 0.9.0 used `eu.exeris.tooling`, with no relocation POM | ADR-091 Amendment 4; ADR-095 Amendment 1 | None |

Trade-off: most rows are held by review and by tests of behavior, not by a compatibility tool. The Java public API has no japicmp gate, and ADR-092 leaves a narrowed view to the reviewer, so a breaking change classified `additive` reaches consumers unannounced unless a reviewer catches it.

## Owning ADRs

- [ADR-015](adr/ADR-015-codegen-emission-strategy.md): the emitted Java, SQL and YAML.
- [ADR-092](adr/ADR-092-emitted-typescript-output-stability.md): the emitted TypeScript and the npm package.
- [ADR-095](adr/ADR-095-stable-diagnostic-identifiers.md): diagnostic identifiers.
- [ADR-097](adr/ADR-097-generator-catalogue-and-launchable-generator.md): the generator catalogue and the launchable generator.
- [ADR-091](adr/ADR-091-opt-in-application-starter.md): the application POMs and the initializr.

## Where this does not apply

This page covers the tooling's own surfaces. The SDK records the processor writes are ruled by the SDK's [stability matrix](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/stability-matrix.md), and the kernel SPI the generated code compiles against by the kernel's [stability matrix](https://github.com/exeris-systems/exeris-kernel/blob/main/docs/stability-matrix.md). It makes no promise about a detached tree, which belongs to the application from the moment it is detached.
