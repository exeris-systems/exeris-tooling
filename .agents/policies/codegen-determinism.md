# Policy: Codegen Determinism

Emitted code must be deterministic: identical `DomainMetadata` input must yield byte-identical output across runs, machines, and operating system locales.

## Hard Rules

1. **No timestamps.**
   Never emit timestamps (`Instant.now()`, `System.currentTimeMillis()`, `LocalDate.now()`, `new Date()`) into generated files. The 0.1.0 `OutputWriter` timestamp drop is canonical.
2. **No random identifiers.**
   Never call `UUID.randomUUID()` or `Random` in code-generation emission paths.
3. **Deterministic collection iteration.**
   Never iterate over standard `HashMap`, `HashSet`, or `ConcurrentHashMap` when emitting ordered code or text artefacts. Sort keys/elements or enforce deterministic collections (`LinkedHashMap`, `TreeMap`, sorted lists) upstream.
4. **Locale neutrality.**
   Always use `Locale.ROOT` for `String.format`, `toLowerCase`, or `toUpperCase` when producing emitted syntax or identifiers.
5. **Path separator neutrality.**
   Always use forward slash (`/`) for source path references, never platform-dependent `File.separator`.

## Verification

- Run determinism check: regenerate twice, diff bytes (`diff -r`), assert zero differences.
- Review with `exeris-tooling-codegen-determinism-review` skill.
- End-to-end assertions via `KernelCodegenE2ETest`.
