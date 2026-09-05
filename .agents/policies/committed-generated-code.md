# Policy: Committed Generated Code and Detachment Discipline

Generated code is committed into downstream user repositories to enable developer inspection, ownership, and detachment.

## Hard Rules

1. **Commit into `src/main/generated/`.**
   Emitters target `src/main/generated/` (Level 1 emission) in consumer projects. Generated source files are intended to be checked into the user app's version control.
2. **Never assume "always regenerate".**
   Never architect generator pipelines under the assumption that generated code is ephemeral or purely disposable. The user app must compile and operate cleanly from committed sources.
3. **Detachment lifecycle (ADR-015, Level 2).**
   Downstream projects may run `exeris:detach` (planned for the 0.3.0 Maven plugin), breaking the generator umbilical cord and allowing the user to take full, permanent ownership of the emitted code. The pipeline must never emit code that depends on private or internal codegen runtime scaffolding.
4. **Separation of generated test sources.**
   Per ADR-058, generated tests are placed in a distinct output directory (`src/test/generated/java`) behind the opt-in `exeris.tests` flag, ensuring test suites are cleanly decoupled from main sources.

## Verification

- Review with `exeris-tooling-detach-output-discipline` skill.
- Verification tests asserting output directory structure and self-contained emission.
