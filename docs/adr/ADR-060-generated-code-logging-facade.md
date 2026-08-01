# ADR-060 — Generated code logs through `System.Logger`

- **Status:** ACCEPTED (2026-08-01)
- **Repo:** `exeris-tooling`
- **Scope:** tooling / codegen emission
- **Visibility:** public
- **Milestone:** 0.7.0
- **Relates to:** ADR-058 (the same question, asked of the *test* surface)

## Context

Eight Java emitters — `KernelApplication`, `KernelEvent`, `KernelEventHandler`, `KernelGraphSync`,
`KernelHandler`, `KernelRepository`, `KernelSaga` and `KernelStreamScaffold` — put an
`org.slf4j.Logger` into generated **main** code.

That is a third-party compile dependency of every generated tree, and tooling emits no `pom.xml`, so
it is a requirement imposed on the consumer's build. Two things made it worse than a preference:

1. **`slf4j-api` is not a dependency of `exeris-kernel-spi` or `exeris-kernel-core`.** It reaches an
   application only through the driver tier — `exeris-kernel-community` pulls it. A consumer on a
   different driver set could have generated code that does not compile.
2. **Nothing stated or enforced it.** The generated application's own Javadoc mentioned
   `org.slf4j:slf4j-api` under "runtime classpath requirements", but no ADR carried it, MIGRATION
   never mentioned it, and every compile gate handed `javac` an inherited
   `System.getProperty("java.class.path")` — a classpath that *cannot* fail on an undeclared
   dependency, because it is the dependency set. It surfaced only when the ADR-058 gate's classpath
   was rewritten to name its entries (PR #148).

ADR-058 answered exactly this question for the generated *test* surface — JUnit 5 + AssertJ and
nothing else, doubles emitted rather than mocked — and left the main surface unexamined.

## Decision

**Generated code logs through `java.lang.System.Logger`. The emitters bind no logging facade.**

`System.Logger` is on every JDK, so the generated tree adds no logging dependency at all. A consumer
who wants SLF4J, Log4j or anything else still gets it, by putting a `System.LoggerFinder` provider on
the classpath — the routing decision moves to where it belongs, the application, and stops being
something tooling decides on the consumer's behalf.

Three consequences of the API difference are part of this decision, not incidental:

1. **Placeholders change from `{}` to `{0}`, `{1}`, …** — `System.Logger.log(Level, String, Object...)`
   formats with `MessageFormat`, not SLF4J's positional `{}`.
2. **Single quotes in a parameterised message must be doubled.** `MessageFormat` reads `'` as opening
   a quoted section, so `'x'` loses its quotes and — the sharp edge — `'{0}'` is emitted verbatim and
   the argument is silently dropped. `KernelScaffold.escapeQuotes` owns this so it cannot be
   rediscovered per emitter; the saga emitter, whose message quotes the step name, bakes the name
   into the literal rather than passing it as an argument, keeping the placeholder out of quotes.
3. **There is no trailing-`Throwable` convention.** SLF4J's `error(msg, arg, ex)` has no equivalent:
   `log(Level, String, Object...)` would format the exception as a parameter and drop the stack
   trace. Sites that logged both a value and an exception concatenate the value and use
   `log(Level, String, Throwable)`.

Messages with no parameters use the `log(Level, String)` overload, which does no formatting — so no
escaping question arises for them.

## Consequences

- The generated tree has **no** third-party compile dependency for logging. Consumers who declared
  `slf4j-api` only because generated code needed it can drop it.
- Log *output* is unchanged in content. Routing changes: without a `LoggerFinder` provider, records
  go to the JDK's default (`java.util.logging`) rather than to an SLF4J backend. A consumer who was
  relying on their SLF4J configuration to see these lines must add a provider — this is the one
  user-visible behaviour change and it is documented in MIGRATION.
- Enforcement is by construction: `GeneratedTestsE2ETest` compiles the generated trees against a
  named classpath with **no** slf4j entry, so reintroducing the facade fails the build. A
  strategy-level test names which artefact regressed, for a faster signal.
- Emitter parity (hard constraint #5) is unaffected: this is a Java-emission detail with no
  `DomainMetadata` surface, so `exeris-codegen-ts` has nothing to mirror.

## What is NOT in scope

- Whether generated code should log *at all*, and at which levels. The call sites are unchanged in
  number and severity; only the facade moved.
- Shipping a `LoggerFinder`. Tooling emits applications; picking their logging backend is theirs.
