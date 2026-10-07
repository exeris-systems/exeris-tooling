---
title: "0.10.0 migration step: The auto-detected base package no longer depends on the filesystem (`EXT-GEN-3104`)"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### The auto-detected base package no longer depends on the filesystem (`EXT-GEN-3104`)

When `exeris.basePackage` (`--base-package` on the command line) is not set, generation picks the
package for `Application`, `RuntimeComponents`, `RuntimeLifecycle` and the generated `testsupport`
package itself. Up to 0.9.0 it took the package of whichever entity the metadata directory happened to
list first, with `.domain` removed. Directory listing order is the filesystem's: on ext4 it differs
between machines, so with entities in several packages a developer and CI could generate the bootstrap
into different packages from the same sources.

From 0.10.0 the metadata is read in order of fully-qualified entity name, and the base package is the
package of the first entity in that order, with `.domain` removed. With more than one entity package
and no `exeris.basePackage`, `exeris:generate` logs the `EXT-GEN-3104` warning naming the packages
and the one chosen.

**Single-package applications** generate into the same package as before. Their `RuntimeComponents`
and `RuntimeLifecycle` list the entities in name order now, where they followed the directory listing,
so the first regeneration may reorder their members and statements once. The reordering changes no
behaviour: every publisher is still built before any subscriber subscribes, and the kernel router
resolves an exact path before a template whatever the registration order.

**Applications with entities in several packages and no `exeris.basePackage`** get the first package
in qualified-name order (`com.shop.billing` before `com.shop.order`), which may differ from the one a
given filesystem produced before. If it does, the bootstrap and the generated `testsupport` package
move: the regeneration prunes the old files, which the previous run's manifest owns, but a subclass of
`Application` or `RuntimeComponents`, an import of a `testsupport` double, and a main class named in
the POM, a jar manifest or a container entry point keep the old package.

**What to do:** set the package explicitly, to the one your application already uses:

```xml
<properties>
  <exeris.basePackage>com.shop.order</exeris.basePackage>
</properties>
```

An explicit base package is used as given and silences the warning. A detached application that no
longer runs `exeris:generate` is unaffected.
