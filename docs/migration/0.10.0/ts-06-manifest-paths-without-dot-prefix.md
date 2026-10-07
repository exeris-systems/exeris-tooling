---
title: "0.10.0 migration step: `exeris-codegen-ts`: the output manifest names project-root files without `./`"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: the output manifest names project-root files without `./`

`@exeris/codegen-ts` records every file it owns in `.exeris-codegen-manifest`, in the same form as
the Java manifest: relative, forward slashes, no `.` or `..` segment. Up to 0.9.0 the project-root
scaffold files were recorded as `./package.json`, `./angular.json`, `./tsconfig.json`,
`./tsconfig.app.json`, `./tsconfig.spec.json`, `./.postcssrc.json` and `./proxy.conf.json`; from
0.10.0 they are recorded as `package.json`, `angular.json`, and so on, and sort among the other
entries by that name.

**What to do:** nothing beyond committing the result. The first regeneration rewrites
`.exeris-codegen-manifest` without the `./` prefix. An entry from the previous manifest is read in
the same canonical form before orphans are computed, so `./package.json` and `package.json` name the
same file and no file is deleted. No emitted file changes.
