---
title: "0.10.0 migration step: `exeris-codegen-ts`: a file the manifest owns is rewritten on regeneration"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-07
---

### `exeris-codegen-ts`: a file the manifest owns is rewritten on regeneration

Up to 0.9.0 `exeris-gen generate` skipped every file that already existed unless `--overwrite` was
passed, so a page, component or service whose metadata changed kept its old content. From 0.10.0
the previous run's `.exeris-codegen-manifest` decides:

- a file the manifest records is rewritten when its content differs, without `--overwrite`;
- a file written once for you to edit (`package.json`, `angular.json`, the `tsconfig` files,
  `src/main.ts`, `src/environments/environment.ts`, `app.config.ts`, `app.component.ts`,
  `app.routes.ts`) is written when absent and kept otherwise, as before;
- an existing file the manifest does not record is never replaced, and is not recorded, unless
  `--overwrite` is passed.

`--overwrite` replaces every differing file, including the last two kinds, and records it as owned.
`--dry-run` lists what each file would get: create, rewrite, unchanged, keep, or skip.

**What to do:** if you edited a generated file other than the ones listed as yours to edit, the
next regeneration replaces your edit. Before regenerating, move such a file out of the output
directory and stop generating it (remove its view or entity, or turn its generator off); a file the
generator still produces is created again at its old path. Commit the result of the first
regeneration, and keep committing `.exeris-codegen-manifest`: it is what marks a file as the
generator's on the next run.
