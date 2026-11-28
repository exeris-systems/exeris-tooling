---
title: "0.12.0 migration step: every backend publishes its contract in its jar"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-28
---

### Every backend publishes its contract in its jar, and `exeris:generate` takes `peers`

`exeris-app-parent` binds a fourth goal, `exeris:contract`, in `process-classes` after
`verify-capabilities` and `verify-runtime`. It writes `META-INF/exeris/contract/` under
`target/classes`, so the directory is in the backend's jar. A build gains these entries:

```
META-INF/exeris/contract/cap-manifest.json
META-INF/exeris/contract/Invoice.json              (one per @ExerisDomain entity)
META-INF/exeris/contract/enum_InvoiceStatus.json   (one per enum the processor wrote)
```

The entity and enum files are the processor's `exeris-metadata/` files, byte for byte. The
`view_*.json` and `capability_*.json` files are not published. `cap-manifest.json` is the resolved
capability graph of the build. A backend with no `@CapabilityModule` publishes the graph of the
empty composition, which is what a build with one entity and no capability module wrote:

```json
{
  "schemaVersion" : 2,
  "stamp" : {
    "validated" : true,
    "compositionVersion" : "0.0.0",
    "contentBinding" : "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  },
  "modules" : [ ],
  "resolutions" : [ ],
  "initOrder" : [ ],
  "warnings" : [ ]
}
```

`compositionVersion` is the composition version exactly as `exeris:generate` computes it: the value
of `-Dexeris.composition.version`, `0.0.0` when the build sets none, which is what a default build
publishes. A module with `pom` packaging has no jar and writes no contract. `exeris:generate` writes `cap-manifest.json` into the generated tree only for a build that
declares a capability module, as before, and a build without one emits byte-identically to 0.11.

A graph that does not resolve fails the goal with `EXT-PLUG-2401` and leaves the previous contract in
place. Skip the goal with `-Dexeris.contract.skip=true`, or all codegen goals with
`-Dexeris.codegen.skip=true`. The contract is as fresh as `target/classes/exeris-metadata/`, so a
release built with `clean` does not carry a deleted entity.

`exeris:generate` takes a `peers` parameter, the contracts of the backends this build consumes.
Each entry names the peer and gives a `path` (a contract directory, a directory holding
`META-INF/exeris/contract/`, or a `.jar`) or a `coordinate` (`groupId:artifactId:version`, resolved
through the Maven resolver and not added to the dependencies):

```xml
<peers>
  <peer><name>billing</name><coordinate>com.acme:billing-service:1.4.0</coordinate></peer>
</peers>
```

The goal reads each contract before it generates and refuses one that does not hold up, with
`EXT-PLUG-2006` naming the peer; a peer entity written against another metadata schema is the
warning `EXT-PLUG-2007`. Nothing is generated from a peer yet. A consumer in the same reactor as its
producer lists the producer under the plugin's `<dependencies>`, which orders the reactor; a `path`
inside the reactor is refused. The identifiers are in the [diagnostics reference](../../diagnostics.md).

This does not apply to an application that does not inherit `exeris-app-parent` and keeps its own
copy of the build block: it binds no `contract` execution and publishes no contract, until it adds
`<goal>contract</goal>` to its `process-classes` execution. It does not apply either to a build
that sets `exeris.contract.skip`, or to one that declares no `peers`, which sees no change in
`exeris:generate`. The jar of an application that inherits the parent gains the three kinds of entry
above, and a packaging step that lists the jar's content exactly sees them.

The TypeScript generator reads a directory that holds `META-INF/exeris/contract/` (a backend's
`target/classes`) as the contract it contains, through `peers[].path` in `exeris-codegen.json`. It
does not read a `.jar` or a Maven coordinate.
