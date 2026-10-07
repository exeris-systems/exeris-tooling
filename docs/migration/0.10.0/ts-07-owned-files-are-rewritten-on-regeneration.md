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
- a seed file, written once for you to edit, is written when absent and kept otherwise. The seed
  files are `package.json`, `angular.json`, `tsconfig.json`, `tsconfig.app.json`,
  `tsconfig.spec.json`, `.postcssrc.json`, `proxy.conf.json`, `src/main.ts`, `src/index.html`,
  `src/styles.css`, `src/environments/environment.ts`, `src/environments/environment.development.ts`,
  `src/app/app.config.ts`, `src/app/app.component.ts`, `src/app/app.routes.ts` and the auth service
  template `core/auth.service.ts`. A seed file the run no longer generates is kept and dropped from
  the manifest, never deleted;
- an existing file the manifest does not record, a symbolic link, or a path reached through a link
  below the output root is never replaced or deleted without `--overwrite`, and is not recorded.
  `--overwrite` replaces a link at the path itself, never its target, and never writes through a
  linked directory;
- a missing or empty metadata directory generates and deletes nothing; a missing one fails the run.
  Up to 0.9.0 it deleted every file the previous run had generated.

`--overwrite` replaces every differing file, seeds and unrecorded files included, and records it as
owned. `--dry-run` lists what each file would get (create, rewrite, unchanged, keep, skip) and every
file the run would prune or release.

The manifest gains a second header line, `# ownership: written`. A manifest without it, written by
0.9.x or earlier, also recorded files those releases skipped because they existed, so on the first
0.10.0 run an entry of it is owned only when the file already holds what the run generates, or is a
seed file. An entry whose file differs is kept as it is and dropped from the manifest.

**What to do:** regenerate, then review the summary. Files reported as skipped are ones the
manifest did not prove to be the generator's; that includes, on the first 0.10.0 run, every
generated file whose 0.10.0 content differs from what is on disk. If they are all generated
output, commit, run once with `--overwrite`, and restore from version control any seed file or
hand-written file it replaced (`--overwrite` replaces seed files too). If you edited a generated file that is not a
seed, the next regeneration replaces your edit: move such a file out of the output directory and
stop generating it (remove its view or entity, or turn its generator off); a file the generator
still produces is created again at its old path. Commit the result, `.exeris-codegen-manifest`
included: it is what marks a file as the generator's on the next run.
