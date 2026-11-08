---
title: Read a capability-graph refusal
type: howto
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-08
---

# Read a capability-graph refusal

**Audience:** your build declares a `@CapabilityModule`, and generation stops on `EXT-PLUG-2201`.

**Goal:** run the `capability` scenario through the pipeline, read why it is refused, and know which change makes the graph resolve.

> **Verified against** exeris-tooling `main` at commit `b70edc67` (`0.11.0-SNAPSHOT`) and `exeris-sdk-examples` at tag `v0.13.3`, 2026-11-08. The output below comes from compiling the scenario's one source file with `exeris-processor` and running `CodegenMain` of the same commit, the class the `exeris-codegen-cli` jar launches, over the metadata. If the output and this page disagree, **the output wins and this guide is the defect**.

## Prerequisites

- [01 Add the tooling to a build](01-add-the-tooling-to-a-build.md) done.
- The `capability` example: [`LedgerCapLifecycle.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/capability/LedgerCapLifecycle.java). How a capability module, its lifecycle owner and its manifest are written is the SDK's [capabilities guide](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/docs/guides/08-capabilities-and-composition.md).

## Steps

### 1. Read what the module declares

Source: [`exeris-sdk-examples/.../capability/LedgerCapLifecycle.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/capability/LedgerCapLifecycle.java), the `LedgerCap` class (quoted).

```java
@CapabilityModule
@Provides(service = LedgerService.class, version = "1.0.0")
@Requires(service = AuditTrail.class, versionRange = "[1.0.0,2.0.0)")
@Requires(service = ExchangeRates.class, optional = true)
class LedgerCap {
}
```

The module provides `LedgerService` and requires two services. The file declares `AuditTrail` and `ExchangeRates` as interfaces and no module that provides either. The SDK states the reason in the `AuditTrail` Javadoc: the example declares one capability alone, so its requirement on another stays unsatisfied by design.

### 2. Run the pipeline over it

The processor accepts the file with no error or warning and writes one metadata file, `capability_LedgerCap.json`. The pipeline then resolves the capability graph before it writes anything, and refuses:

Generated: the output of `java -jar exeris-codegen-cli-0.11.0-SNAPSHOT.jar --metadata-dir=<metadata> --output-dir=<out> --base-package=eu.exeris.sdk.examples.app` (quoted, the `INFO` lines before it removed).

```text
[Exeris] EXT-PLUG-2201: Capability graph could not be resolved:
  - module eu.exeris.sdk.examples.capability.LedgerCap @Requires service eu.exeris.sdk.examples.capability.AuditTrail version [1.0.0,2.0.0) but no @CapabilityModule provides it
```

The command exits with status 1 and the output directory stays empty. `SdkExamplesE2ETest.capabilityScenarioIsRefusedOnItsUnprovidedRequirement` asserts the same problem text, from `CodegenPipeline.run`, on every build.

Each unresolved problem is one line after the identifier. Only the required `AuditTrail` is listed. `ExchangeRates` is `optional = true`, and an optional requirement with no provider is a warning, `EXT-GEN-3102`, never a refusal.

### 3. Know where a Maven build stops

Under `exeris-app-parent`, `exeris:verify-capabilities` is bound at `process-classes`, after `compile`. `exeris:generate` sees that later gate and does not fail on the graph: it warns with `EXT-GEN-3103`, keeps the previous `cap-manifest.json`, and leaves the verdict to `verify-capabilities`, which reads the metadata of the same build and fails with `EXT-PLUG-2201`. Without that later execution `generate` fails itself, as the command line does. The rule is on the [Maven plugin page](../modules/06-codegen-maven-plugin.md#generate).

### 4. Make the graph resolve

The [diagnostics reference](../diagnostics.md) lists three causes for `EXT-PLUG-2201`, and the fix for each. Here the cause is the missing provider, so one of these resolves it:

- Add, to the same build, a `@CapabilityModule` declaring `@Provides(service = AuditTrail.class, version = ...)` with a version inside `[1.0.0,2.0.0)`.
- Declare the requirement `optional = true`, if the ledger runs without an audit trail. It then warns with `EXT-GEN-3102` instead.

When the graph resolves, the pipeline writes `cap-manifest.json`, the resolved graph and its composition stamp, at the root of the generated tree ([generator catalogue](../generators.md#main-root-project-scope)). `verify-capabilities` then scans the capability module's compiled classes for types the Wall forbids, `EXT-PLUG-2203` ([ADR-055](../adr/ADR-055-cap-tier-wall-guard.md)).

## Verify

Rerun the build. The `EXT-PLUG-2201` line is gone, `src/main/generated/java/cap-manifest.json` is written, and `verify-capabilities` passes. The scenario at the tag never gets there: `SdkExamplesE2ETest` asserts the refusal, so a corpus that starts resolving fails that test until the guide and the test are updated together.

## Where this does not apply

- **Capabilities at runtime.** The tooling validates the graph and stamps the manifest at build time. Driving the modules' lifecycle owners is the SDK composition runtime's `CompositionConductor`, which a composed `Application` imports ([application BOM, parent and starter](../modules/09-app-starter.md)); the SDK guide above covers it.
- **Entities.** The scenario declares no `@ExerisDomain`, so there is no application to generate, and it is kept out of the application the other guides walk through.
- **Trade-off you accept.** Refusing an unsatisfied requirement at build time means a capability cannot be built on its own before its provider exists: the example is refused precisely because it is alone. Marking the requirement optional lets it build, and moves the check for the missing service to the code that calls it.
