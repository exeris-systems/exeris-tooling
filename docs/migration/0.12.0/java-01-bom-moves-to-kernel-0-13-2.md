---
title: "0.12.0 migration step: The BOMs move to kernel 0.13.2 and Jackson 3.3.0"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-11-22
---

### The BOMs move to kernel 0.13.2 and Jackson 3.3.0

`exeris-app-bom` and `exeris-tooling-bom` pin `exeris.kernel.version` `0.13.2` and
`jackson3.version` `3.3.0`, the Jackson 3 line `exeris-kernel-bom` 0.13.2 manages. Kernel 0.13.2
changes no SPI type, so regenerated code is unchanged. A build that imports `exeris-app-bom` and
does not override either property resolves both; nothing else is required. A build that overrides
`jackson3.version` below `3.3.0` runs the kernel on a Jackson 3 older than the one it was released
on. A build that declares the PostgreSQL driver itself should use `42.7.14`, the version the
kernel BOM manages.
