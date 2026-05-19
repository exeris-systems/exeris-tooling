---
description: Check Java/TS emitter parity — shared DomainMetadata surfaces must be visible to both `exeris-codegen-java` and `exeris-codegen-ts`.
argument-hint: PR diff or DomainMetadata / generator surface changes
---

Audit this change for Java/TS emitter parity.

Parity rules:
- DomainMetadata is the only contract between processor and generators.
- A field/action/event/validation visible to `exeris-codegen-java` is also visible to `exeris-codegen-ts` — and vice versa, when the surface is shared.
- Adding a metadata field on one side without a matching emitter consideration on the other is a contract bug.
- Removing a metadata field requires migration on both sides.
- TS shapes (component / service / store / guard / form / list / detail / app structure / sagas) and Java shapes (handler / service / repository / saga / events / OpenAPI / Flyway / client / app) consume the same JSON.

Change:
$ARGUMENTS

Please review:
1. Does this widen or narrow DomainMetadata?
2. If yes — is the TS side updated in the same PR, or is there an explicit follow-up issue named?
3. Is the Maven reactor build + TS `npm test` both green?
4. Does the migration story (downstream user app regenerates) appear in `docs/MIGRATION-0.x-to-1.0.md`?
5. Minimal correction if parity is at risk.

The TS package is intentionally outside the Maven reactor — coordination is manual, by design. Don't propose merging the build to "solve" parity.
