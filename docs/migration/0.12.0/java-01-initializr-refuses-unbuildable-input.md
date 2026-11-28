---
title: "0.12.0 migration step: exeris-initializr refuses input that scaffolds a project that does not build"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-22
---

### exeris-initializr refuses input that scaffolds a project that does not build

`ProjectScaffoldRequest` throws `IllegalArgumentException` on construction, and the command line
exits `2` with `EXT-INIT-5001`, for each of these values. All of them were accepted before and gave
a project that did not build, a file that cannot be extracted on Windows, or an archive entry outside
its root:

| Value | Refused when |
| --- | --- |
| `entityName` | it is one of `ProjectScaffoldRequest.RESERVED_ENTITY_NAMES` (`UUID`, `Field`, `ExerisDomain`, `String`), which the entity template imports or uses to type its fields, or it is a Windows device name |
| `packageName` | its first segment is `java`, it holds `$`, which a shell expands in the README's run command, or one of its segments is a Windows device name; a groupId that derives a package under `java` is refused the same way |
| `artifactId` | it does not start with a letter or a digit, ends in a dot, or is longer than `ProjectScaffoldRequest.MAX_ARTIFACT_ID_LENGTH` (100) characters |
| `groupId`, `artifactId` | it is a Windows device name, or its part before the first dot is |
| `name` | it holds a character XML 1.0 forbids (such as U+FFFE or U+FFFF), a line separator (U+2028, U+2029) or a bidirectional override (U+202A to U+202E, U+2066 to U+2069) |

A Windows device name is `CON`, `PRN`, `AUX`, `NUL`, `COM1` to `COM9` and `LPT1` to `LPT9`, compared
without regard to case. `console` or `my-con` is not one.

`ZipArchiveGenerator.write` and `ProjectWriter.writeArchive` also refuse a `root` that ends in a dot
or a space, holds `:`, or is, before its first dot, a Windows device name. A root of `..` gave entries such as `../pom.xml`, which extract outside the
target directory; it ends in a dot and is refused.

Two emitted files change. `docker-compose.yml` writes the database name as a string:

```yaml
      POSTGRES_DB: "order_service"
```

and the README title is escaped for XML and HTML, so `Orders & Co` is written
`# Orders &amp; Co`.

This does not apply to a caller that only passes values the list above does not name, and it does
not change the project that an earlier version wrote. A caller that scaffolds on untrusted input can
show the entity rule from `RESERVED_ENTITY_NAMES`. A request whose derived package holds a device
name, such as artifactId `my-con`, is given an explicit `packageName` instead.
