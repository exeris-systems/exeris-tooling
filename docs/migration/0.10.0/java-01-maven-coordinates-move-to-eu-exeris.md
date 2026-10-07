---
title: "0.10.0 migration step: Maven coordinates move to the `eu.exeris` group"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### Maven coordinates move to the `eu.exeris` group

From 0.10.0 every tooling artefact is published under the groupId **`eu.exeris`**, the group the
kernel (`eu.exeris:exeris-kernel-*`) and the SDK (`eu.exeris:exeris-sdk-*`) already publish under.
The artifactIds do not change, and none of them collides with a kernel or SDK artifactId.

| 0.9.0 and earlier | 0.10.0 and later |
|---|---|
| `eu.exeris.tooling:exeris-tooling-root` | `eu.exeris:exeris-tooling-root` |
| `eu.exeris.tooling:exeris-tooling-parent` | `eu.exeris:exeris-tooling-parent` |
| `eu.exeris.tooling:exeris-tooling-bom` | `eu.exeris:exeris-tooling-bom` |
| `eu.exeris.tooling:exeris-diagnostics` | `eu.exeris:exeris-diagnostics` |
| `eu.exeris.tooling:exeris-codegen-core` | `eu.exeris:exeris-codegen-core` |
| `eu.exeris.tooling:exeris-processor` | `eu.exeris:exeris-processor` |
| `eu.exeris.tooling:exeris-codegen-java` | `eu.exeris:exeris-codegen-java` |
| `eu.exeris.tooling:exeris-codegen-maven-plugin` | `eu.exeris:exeris-codegen-maven-plugin` |
| `eu.exeris.tooling:exeris-app-bom` | `eu.exeris:exeris-app-bom` |
| `eu.exeris.tooling:exeris-app-parent` | `eu.exeris:exeris-app-parent` |
| `eu.exeris.tooling:exeris-app-starter` | `eu.exeris:exeris-app-starter` |

**0.9.0 and every earlier release stay where they are**, under `eu.exeris.tooling`, and no
relocation POM points from the old group to the new one. A build that keeps the old groupId and
raises the version to 0.10.0 fails to resolve: the coordinate does not exist.

**What to do:** when you move to 0.10.0, replace `eu.exeris.tooling` with `eu.exeris` in every
`<groupId>` that names a tooling artefact:

- the `<parent>` of an application on `exeris-app-parent`;
- the `exeris-app-bom` import in `<dependencyManagement>`;
- the `exeris-app-starter` dependency;
- the `exeris-processor` entry in `maven-compiler-plugin`'s `<annotationProcessorPaths>`;
- the `exeris-codegen-maven-plugin` declaration in `<build><plugins>` or `<pluginManagement>`;
- any other dependency on a tooling artefact, such as `exeris-codegen-java` or `exeris-diagnostics`.

`mvn exeris:generate` and the other `exeris:*` goals resolve as before when the plugin is declared in
the POM, under either route of the README's quick start. A command line that names the plugin in
full (`mvn eu.exeris.tooling:exeris-codegen-maven-plugin:<version>:generate`) and a `<pluginGroup>`
entry for `eu.exeris.tooling` in `settings.xml` name the old group and change with it. A cache, a
repository-manager proxy rule or a dependency-update tool that matches on `eu.exeris.tooling` matches
nothing from 0.10.0.

**Java packages do not change.** The classes stay in `eu.exeris.tooling.*`
(`eu.exeris.tooling.diagnostics.DiagnosticId`, `eu.exeris.tooling.codegen.*`, …): an import, a
`-Aexeris.*` processor option or a diagnostic identifier is unaffected. Generated code changes in one
Javadoc line: the generated `Application` names the starter as `eu.exeris:exeris-app-starter`.
`@exeris/codegen-ts` keeps its npm name and still versions in lockstep with the Maven artefacts.
