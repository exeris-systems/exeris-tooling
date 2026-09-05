# Policy: Java and TypeScript Emitter Parity

Every shared capability and metadata element must be reflected with equal fidelity across both the Java and TypeScript/Angular emitters.

## Hard Rules

1. **Shared surface parity.**
   Any field, action, validation constraint, event, or route access rule visible to `exeris-codegen-java` must also be handled by `exeris-codegen-ts` when the concept applies to both client and server.
2. **Contract bugs.**
   Adding a metadata field or capability to the Java emission path without a corresponding consideration in the TypeScript emission path is a contract bug, not a feature backlog item.
3. **Angular emission shapes.**
   `exeris-codegen-ts` generates complete, idiomatic Angular structures:
   - Components (list, detail, forms)
   - Services and stores
   - Route guards
   - Application scaffolding and sagas
4. **Explicit divergence documentation.**
   If a server-only concept (e.g. Flyway migrations, database constraints) has no client-side counterpart, the omission must be documented as intentional in the change.

## Verification

- Cross-emitter reviews using `exeris-tooling-emitter-parity-review` skill.
- End-to-end test verification across Java and TypeScript suites.
