---
title: "0.12.0 migration step: `exeris-codegen-ts`: the emitted app depends on zod 4"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-22
---

### `exeris-codegen-ts`: the emitted app depends on zod 4

The emitted `package.json` declares `"zod": "^4.6.6"`, where it declared `"^3.24.0"`; the 3.x line has no release that fixes its published advisory. A `Map` field, bare or parameterised, is emitted with a key schema: `z.record(z.string(), z.unknown())` and `z.record(z.string(), z.number().int().nullable())`, where zod 3 took the value schema alone and zod 4 refuses that form. The other schemas are emitted with the same text and run zod 4's validators, which are not zod 3's for the string formats (`uuid`, `email`, `url`). A `UUID` field's `z.string().uuid()` rejects ids whose version or variant bits are not RFC 9562 (for example `11111111-1111-1111-1111-111111111111`), where zod 3 accepted any 8-4-4-4-12 hex string; `z.guid()` is the permissive form, for an app that parses seeded or non-RFC ids with the exported schemas.

Regenerate, then run `npm install` so the app resolves zod 4. An app that declares `zod` in its own `package.json` under a 3.x range must move it to `^4.6.6`, and any hand-written schema beside the generated ones must use the zod 4 API, for which `z.record` takes a key schema and a value schema. An app that generates with `--no-zod` has no zod dependency and is unchanged.
