---
title: Add the tooling to a build
type: howto
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-08
---

# Add the tooling to a build

**Audience:** you have, or are about to write, `@ExerisDomain` entities in a Maven module and want the tooling to generate a kernel application from them.

**Goal:** put the processor and the codegen goals into the build, know which kernel and SDK versions come with them, and lay the entities out the way the processor requires.

> **Verified against** exeris-tooling `main` at commit `b70edc67` (`0.11.0-SNAPSHOT`) and `exeris-sdk-examples` at tag `v0.13.3`, 2026-11-08. Snippets are quoted or adapted from the cited file. If a snippet and its source disagree, **the source wins and this guide is the defect**.

## Prerequisites

- JDK 25 or newer, running Maven 3.9 or newer. The plugin loads into Maven's own JVM, so the JDK Maven runs on is the one that counts ([Maven plugin](../modules/06-codegen-maven-plugin.md#coordinates-and-dependencies)).
- A Maven module of your own. Tooling writes no `pom.xml`; the build is yours.

## Steps

### 1. Choose how the build gets the tooling

Two routes, both written out in full in the repository [README](../../README.md#quick-start-an-application-on-the-starter):

- **Route 1, the parent.** Inherit `eu.exeris:exeris-app-parent`. It runs the processor, binds `exeris:generate` at `generate-sources` and `exeris:verify-capabilities` plus `exeris:verify-runtime` at `process-classes`, and puts the generated migrations and OpenAPI documents on the classpath. Depend on `eu.exeris:exeris-app-starter` (type `pom`) for what the generated code compiles against.
- **Route 2, a build that already has a parent.** Import `eu.exeris:exeris-app-bom` and copy the build block the README gives. Nothing checks that copy against the parent by building it; review does.

What each of the three POMs holds is on the [application BOM, parent and starter](../modules/09-app-starter.md) page. Neither carries a JDBC driver or a logging backend: those stay your choice.

### 2. Know what the version pins

`exeris-app-bom` names the kernel and SDK versions one tooling release was built and tested against.

Source: `exeris-app-bom/pom.xml`, the `<properties>` block (adapted, the Jackson property and its comment removed).

```xml
<properties>
    <exeris.tooling.version>0.11.0-SNAPSHOT</exeris.tooling.version>
    <exeris.kernel.version>0.13.1</exeris.kernel.version>
    <exeris.sdk.version>0.13.3</exeris.sdk.version>
</properties>
```

These are the pins on `main`, which builds 0.11.0. The published 0.10.0 BOM pins kernel and SDK `0.12.0`, so the README's snippets, which name 0.10.0, give you the older pair until 0.11.0 is released. Overriding `exeris.kernel.version` or `exeris.sdk.version` leaves the combination the tooling's suites ran against ([ADR-091](../adr/ADR-091-opt-in-application-starter.md)).

### 3. Put each entity in a `.domain` package

The generated application places the repository, service, handler and event types of an entity in sibling packages of its own, found by replacing the `.domain` suffix. Every scenario of the corpus is laid out that way.

Source: [`exeris-sdk-examples/.../ordering/domain/Order.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/ordering/domain/Order.java), the package declaration (quoted).

```java
package eu.exeris.sdk.examples.ordering.domain;
```

From it the run writes `eu.exeris.sdk.examples.ordering.handler.OrderHandler`, `...ordering.service.OrderService`, `...ordering.repository.OrderRepository` and the rest. An entity in a package that does not end in `.domain` is refused with `EXT-PROC-1033` before anything is written; the fix is in the [diagnostics reference](../diagnostics.md).

### 4. Give every column a public getter and setter

The generated repository binds each column through its getter and reads it back through its setter, and the generated handlers and repository set the system properties, such as audit times and the owning tenant, through setters. A missing accessor is refused with `EXT-PROC-1034`, which names the methods to add. The exact rule, `is<Name>()` for a primitive `boolean` and how Lombok counts, is in the same reference.

Source: [`exeris-sdk-examples/.../ordering/domain/Order.java`](https://github.com/exeris-systems/exeris-sdk/blob/v0.13.3/exeris-sdk-examples/src/main/java/eu/exeris/sdk/examples/ordering/domain/Order.java), the `getNumber` and `setNumber` methods (quoted).

```java
public String getNumber() {
    return number;
}

public void setNumber(String number) {
    this.number = number;
}
```

### 5. Build twice the first time

The processor writes the metadata during `compile`, which runs after `exeris:generate`. On a tree with no metadata yet, the first build seeds it and the second generates:

```bash
mvn compile -Dexeris.codegen.skip=true
mvn verify
```

Do not `clean` between the two. The reason, and when every build becomes a single pass, are in the README's [two-pass section](../../README.md#the-first-build-takes-two-passes).

## Verify

After the first pass, the processor has written one JSON file per entity and per view under `target/classes/exeris-metadata/`. For the four domain scenarios of the corpus the run's listing is:

Generated: `target/classes/exeris-metadata/` (quoted, `ls -1`).

```text
Category.json
Customer.json
Employee.json
Invoice.json
InvoiceLine.json
Order.json
Product.json
view_EmployeeDirectory.json
```

`SdkExamplesE2ETest.processorAcceptsTheCorpus` asserts exactly this list, with no processor error or warning. After the second pass, `src/main/generated/java` holds the generated tree and its `.exeris-codegen-manifest`, which [guide 02](02-generate-an-application.md) walks through.

## Where this does not apply

- **Gradle.** Only Maven POMs are published. The [codegen command line](../modules/07-codegen-cli.md) runs the same pipeline from `java -jar` for a tool that is not Maven, and the processor is an ordinary `javac` annotation processor.
- **Starting from nothing.** `exeris-initializr` writes a whole project, POM included; see [its module page](../modules/08-initializr.md).
- **The frontend.** None of these POMs runs the TypeScript generator; [guide 05](05-generate-the-frontend.md) does.
- **Trade-off you accept.** The parent binds `exeris:generate` before `compile`, so generation reads the previous build's metadata. That buys generated sources compiled in the same build, at the price of the two-pass first build and a capability verdict deferred to `process-classes` ([guide 06](06-read-a-capability-graph-refusal.md)).
