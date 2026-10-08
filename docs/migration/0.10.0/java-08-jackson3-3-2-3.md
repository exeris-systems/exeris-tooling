---
title: "0.10.0 migration step: `exeris-app-bom` manages Jackson 3 at 3.2.3"
type: migration-guide
visibility: public
owning-repo: exeris-tooling
status: active
last-verified: 2026-10-08
---

### `exeris-app-bom` manages Jackson 3 at 3.2.3

`exeris-app-bom`, and through it `exeris-app-parent` and `exeris-app-starter`, now manage
`tools.jackson.core:jackson-databind` at 3.2.3, the version `exeris-kernel-bom` and `exeris-sdk-bom`
0.12.0 manage. 0.9.0 managed 3.2.2, which takes precedence over the kernel's and SDK's own version and
keeps an application on a release affected by GHSA-cxp5-3px4-pw24 and GHSA-wv8q-qhhj-9h54. Generated
code is unchanged.

**What to do:** on 0.10.0, nothing. On 0.9.0, set `<jackson3.version>3.2.3</jackson3.version>` in an
application whose parent is `exeris-app-parent`; an application that imports `exeris-app-bom` declares
`tools.jackson.core:jackson-databind` at 3.2.3 in its own `<dependencyManagement>`, which takes
precedence over the import.
