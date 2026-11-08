---
title: Glossary
type: reference
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-03
---

# Glossary

Terms as `exeris-tooling` uses them, in alphabetical order. Each entry names the owning type, decision or page; follow the link for the full rule. SDK terms such as baseline trust and record growth are defined in the [SDK glossary](https://github.com/exeris-systems/exeris-sdk/blob/main/docs/glossary.md).

**Abstain.** What `GeneratedRoutePolicy` answers for a route whose entity and action declare no access: no requirement, so the next policy in `HttpRoutePolicy.firstDeclared` decides, and in the end `Application.unmatchedRoutes()`, which is `permitAll()` unless overridden ([ADR-105](adr/ADR-105-generated-route-policy-emission.md)).

**Accepted-API-changes ledger.** A JSON list of breaking changes to generated output, each with its signature, release, ADR and justification. The root `accepted-api-changes.json` covers the processor and the Java pipeline; `exeris-codegen-ts/api/accepted-api-changes.json` covers the emitted TypeScript ([stability matrix](stability-matrix.md)).

**Base package.** The package of `Application`, `RuntimeComponents`, `RuntimeLifecycle`, `GeneratedRoutePolicy` and the generated `testsupport` package: `exeris.basePackage` or `--base-package`, else the first domain's package by qualified name with its trailing `.domain` removed. `EXT-GEN-3104` warns when it was inferred across several packages.

**Capability manifest.** `cap-manifest.json`, written by `CodegenPipeline` at the root of the main tree when the build declares a `@CapabilityModule`: the resolved capability graph and its composition stamp, read at boot by the SDK composition runtime ([ADR-024](https://github.com/exeris-systems/exeris-docs/blob/main/adr/ADR-024-capability-composition-model.md)).

**Capability-tier Wall.** The bytecode scan `CapTierWall` runs over a capability module's compiled classes, refusing references to host-runtime packages, kernel private packages and sibling capabilities' `internal` packages. `exeris:verify-capabilities` runs it ([ADR-055](adr/ADR-055-cap-tier-wall-guard.md)).

**Contract coverage.** The classification in `exeris-codegen-ts/src/models/contract-coverage.ts` of every top-level `DomainMetadataSchema` field as `READ`, `JAVA_ONLY`, `RESERVED` or `GAP`. The TS line's 1.0 criterion is no `GAP` ([codegen-ts](modules/05-codegen-ts.md)).

**Contract file.** A JSON file under `exeris-e2e-tests/src/test/resources/contract/` that a Java test and a TypeScript spec both read, such as `crud-routes.json` or `route-policy.json`. It is where the two emitters, which never run together, are held to the same rule ([e2e tests](modules/10-e2e-tests.md)).

**Detach.** `exeris:detach`: moving the generated Java from `src/main/generated/java` into `src/main/java`, after which the application owns it and stops regenerating. The step from L1 to L2 ([Maven plugin](modules/06-codegen-maven-plugin.md)).

**Determinism.** The rule that the same metadata yields byte-identical output on any run, machine and locale ([codegen determinism](../.agents/policies/codegen-determinism.md)).

**Diagnostic identifier.** A stable code `EXT-<AREA>-<NNNN>` on every message the tooling prints, from the registry `DiagnosticId`. Never renumbered or reused; the message text after it is not part of the contract ([ADR-095](adr/ADR-095-stable-diagnostic-identifiers.md), [reference](diagnostics.md)).

**DomainMetadata.** The SDK record, in `exeris-sdk-source-model`, that describes one entity after extraction, and the JSON form the processor writes as `exeris-metadata/<Entity>.json`. The only input the generators read ([DomainMetadata contract](../.agents/policies/domain-metadata-contract.md)).

**`DomainMetadataSchema`.** The Zod schema in `exeris-codegen-ts` that mirrors `DomainMetadata` and validates each entity file the TypeScript generator reads.

**Facet.** A slice of the metadata the SDK conformance kit names in its `Facet` enum, such as relationships or sagas. The processor's TCK binding declares no facet unsupported, and `TckFacetRatchetTest` keeps that set from growing ([processor](modules/02-processor.md)).

**Generator catalogue.** The committed map from every path the Java pipeline writes to the generator that wrote it: `META-INF/exeris/generator-catalogue.json` in `exeris-codegen-java`, and [generators.md](generators.md) for a person ([ADR-097](adr/ADR-097-generator-catalogue-and-launchable-generator.md)).

**Inert.** Said of an annotation or attribute the processor accepts but no generator consumes. The strict audit reports it from the `INERT_ATTRIBUTES` and `INERT_ANNOTATIONS` registries (`EXT-PROC-1201`, `1202`).

**L1.** Generated code committed under `src/main/generated/` and rewritten by each regeneration ([committed generated code](../.agents/policies/committed-generated-code.md)).

**L2.** Generated code after `exeris:detach`: owned by the application under `src/main/java` and no longer regenerated.

**Manifest.** `.exeris-codegen-manifest` at the root of a generated tree: the paths the last run owned. A run deletes only paths the previous manifest lists and it did not write, so a file the tool never wrote is never touched. The Java and TypeScript generators each keep their own.

**Metadata-only.** `-Aexeris.metadataOnly=true`: the processor writes the same metadata but skips `EXT-PROC-1033` and `EXT-PROC-1034`, the refusals only a generated kernel application needs. For a consumer that generates no application, such as the SDK conformance kit.

**Migration fragment.** One file under `docs/migration/<version>/` stating what a consumer sees after regenerating and what to change. The release assembles the fragments into `MIGRATION-0.x-to-1.0.md` ([format](migration/README.md)).

**Refusal.** A `javac` error from the processor, or a run failure from the pipeline, on a declaration the generated application cannot serve, carrying its own identifier. A refused pipeline run writes nothing.

**Route requirement.** What a generated route demands of its caller: `PERMIT_ALL`, `AUTHENTICATED`, `ANY_SCOPE` over the declared permissions, or none. Derived by `RouteRequirements`; the nearest declaration wins ([ADR-105](adr/ADR-105-generated-route-policy-emission.md)).

**Seed file.** A file the TypeScript generator writes once for the application to edit, such as `package.json` or `app.routes.ts`, and keeps afterwards unless `--overwrite`.

**Server-owned field.** A field whose value the server sets or keeps rather than taking it from a request body. The primary key, the owning tenant, the audit and soft-delete fields and every `@Blob` field are in neither the create nor the update body; the version and a `UNIVERSE` entity's shared scope are left out of the create body and stamped by the server, and an update body carries the version only as the version the edit was loaded at. `ServerOwnedFields` derives the Java sets, and `contract/update-parity/` holds the TypeScript side to them ([ADR-090](adr/ADR-090-reject-mismatched-tenant.md), [ADR-108](adr/ADR-108-blob-field-routes.md)).

**Strict mode.** `-Aexeris.strict=true`: the processor's completeness audit, warning on what is set but changes no output (`EXT-PROC-1201` to `1203`).

**Tested triple.** The tooling, kernel and SDK versions one tooling release was built and tested against, named by `exeris-app-bom` ([ADR-091](adr/ADR-091-opt-in-application-starter.md)).

**Two-pass build.** The first build of a project: `mvn compile -Dexeris.codegen.skip=true` to let the processor write metadata, then an ordinary build, because `exeris:generate` runs before the `compile` that writes what it reads.

## Where this does not apply

This page defines tooling terms. It does not define SDK or kernel vocabulary, and it does not say which annotation reaches which output; the [generator catalogue](generators.md) and the strict audit say that.

The cost: each definition restates, in one line, a term its owning page defines, so a change to that page can leave this one behind; every entry links its owner, which stays authoritative.
