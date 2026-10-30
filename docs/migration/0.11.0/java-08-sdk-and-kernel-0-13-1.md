---
title: "0.11.0 migration step: exeris-app-bom pins SDK 0.13.1 and kernel 0.13.1"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-30
---

### `exeris-app-bom` pins SDK 0.13.1 and kernel 0.13.1

`exeris.sdk.version` and `exeris.kernel.version` in `exeris-app-bom` are `0.13.1`. Neither release
changes an SPI type or the shape of the metadata, so regenerating changes no generated file.

Kernel 0.13.1's Community security provider reads its verification keys from configuration:
`security.jwks.uri`, `security.jwt.issuer` and `security.jwt.audience`, all three or none. On kernel
0.13.0 the Community provider held no key, so every route the generated route policy marks as
requiring identity answered `401` for every token. An application on the Community driver whose
routes demand identity sets the three keys; with none set it keeps denying every token, and any
other combination fails the boot with `EX-BOOT-0002`. The kernel's `docs/subsystems/security.md`
describes the keys.
