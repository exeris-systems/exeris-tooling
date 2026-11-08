# Policy: Consumer-Build Contracts

`exeris-tooling` emits code into external consumer projects without emitting a `pom.xml`. Every import, logging call, and test assertion in emitted code creates a non-obvious requirement on the consumer's build environment.

## Hard Rules

1. **ADR-060: Emitted code logs through `System.Logger`.**
   - Emitters bind no external logging facade (`slf4j-api` is absent from kernel-spi and core).
   - Placeholders are `{0}` / `{1}` (`java.text.MessageFormat`), **never** `{}`.
   - Single quotes in parameterised messages must be doubled using `KernelScaffold.escapeQuotes`. Unescaped quotes cause placeholder arguments to be silently discarded.
   - No trailing-`Throwable` convention. Concatenate value and invoke `log(Level, String, Throwable)`.
   - Consumer build must compile cleanly with zero slf4j dependencies.
2. **ADR-058: Generated tests import only JUnit 5 and AssertJ; generated integration tests also the JDK.**
   - Emitted tests in `src/test/generated/java` may import ONLY `org.junit.jupiter.api.*` and `org.assertj.core.api.*`.
   - Never import mocking frameworks (Mockito, EasyMock, WireMock). Test doubles are emitted directly.
   - Service classes must provide `public`, non-final, assignment-only constructors to allow emitted test stubs to subclass them.
   - Emitted integration tests in `src/test/generated-it/java` (ADR-058 Amendment 1) may import ONLY `org.junit.jupiter.api.*`, `org.assertj.core.api.*`, the Java SE standard library (any `java.*` package) and the JDK's `com.sun.net.httpserver` and the generated main code. They need `com.h2database:h2` and a kernel driver at test runtime, and never import either; no kernel testkit and no mocking framework. Every test class name ends in `IT`, and no support class matches a Surefire or Failsafe default include.
   - Failsafe runs them only where the integration-test root exists: an application that leaves `exeris.integrationTests` off binds no Failsafe, so an `*IT` class of its own runs exactly as before.
3. **ADR-055: Cap-tier Wall guard uses dependency-free Class-File API.**
   - `CapTierWall` in `exeris:verify-capabilities` reads compiled bytecode from `target/classes` using the JDK-standard Class-File API (JEP 484) and must remain completely dependency-free.
   - Constant-pool-only walks are unsound. The five-source extraction union (bytecode, descriptors, signatures, interfaces, superclasses) is mandatory.

## Verification

- Preflight review with `exeris-tooling-consumer-build-contracts` skill.
- `GeneratedTestsE2ETest` executing emitted tests under clean classpath constraints.
- `GeneratedIntegrationTestsE2ETest` compiling the integration tests against JUnit, AssertJ and the kernel alone, and running them against the booted application.
