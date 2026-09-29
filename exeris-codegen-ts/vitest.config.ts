import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    globals: true,
    environment: 'node',
    include: ['test/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html', 'json-summary'],
      include: ['src/**/*.ts'],
      // src/index.ts is the CLI shell (covered by integration runs,
      // not unit tests); .d.ts files are pure type declarations with
      // no executable code.
      exclude: ['src/index.ts', 'src/**/*.d.ts'],

      // Two-layer gate on coverage thresholds:
      //
      // 1. GLOBAL 85% threshold (lines/functions/branches/statements)
      //    on the whole instrumented surface. Catches REGRESSIONS at
      //    the aggregate level — if anyone adds a new src/ file
      //    without tests, or removes coverage from an existing one
      //    big enough to drag the aggregate below 85%, the build
      //    fails.
      //
      // 2. PER-FILE 85% thresholds on every file that has tests.
      //    Protects specific paths from LOCAL regressions even when
      //    the aggregate is high enough to absorb them (e.g. dropping
      //    store-gen from 100% to 60% wouldn't dent the aggregate
      //    below 85% but absolutely should fail the build). The per-file
      //    entries prevent silent erosion of well-tested files against
      //    the looser global floor.
      //
      // Every file under src/ carries both a global floor (the four
      // top-level entries) AND a per-file gate below. Adding a new
      // src/ file without a matching gate entry is the intentional
      // next-action signal, not the test runner silently picking up
      // the looser global.
      thresholds: {
        lines: 85,
        functions: 85,
        branches: 85,
        statements: 85,
        'src/core/**/*.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/models/**/*.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // T42: the peer-contract loader. Almost all of it is refusals, and each refusal
        // is the contract — a peer whose artifact cannot be verified is rejected, never
        // degraded — so it is gated like every other src/ directory.
        'src/peers/**/*.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/api/**/*.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // T2: the spec emitters. Their real proof is the CI `ng test` on the generated
        // sample; this pins the generator itself against silent erosion like every other.
        'src/generators/angular/spec-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/guard-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/detail-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/form-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/service-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/list-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/store-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // event-gen threshold: 80% branch (relaxed from 85%). The residual
        // ~17% branch gap is composed entirely of `||` defensive fallbacks
        // against shapes the Zod DomainEventMetadataSchema can't produce.
        // The schema auto-defaults `fields: []` and `tags: []`, so
        // `event.fields || []` at the generateEventInterfaces seam and
        // `events.map(...).join('\n') || '  | never'` at the union-type
        // fallback are structurally unreachable through the public
        // Zod-validated API. Documented inline rather than papered over
        // with synthetic fixtures.
        'src/generators/angular/event-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 80,
          statements: 85,
        },
        'src/generators/angular/saga-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // Marketing landing-page generator (hardcoded ExerisPitchDeck entity emit).
        'src/generators/angular/landing-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // config loader + angular barrel + app-structure orchestrator.
        // config.ts and app-structure-gen.ts are covered directly;
        // angular/index.ts is a re-export barrel with no runtime
        // branches — the barrel spec pins the public-export contract only.
        'src/config.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        'src/generators/angular/index.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // app-structure-gen branches relaxed to 80%, matching the
        // event-gen precedent. The residual ~20% branch gap is
        // composed entirely of structurally unreachable defensive
        // fallbacks at the orchestrator seams:
        //   - `Array.isArray(typesFiles)` else — generateTypes is
        //     typed to always return an array (file ? [file] : []).
        //   - `if (schemaFile)` / `if (enumsFile)` else — the two
        //     local placeholder generators always return a truthy
        //     {content: string}.
        //   - `config.apiBasePath || clientConfig.baseUrl || '/api'`
        //     final '/api' fallback — every registered strategy in
        //     backend-strategy.ts sets baseUrl='/api', so the second
        //     `||` never trips.
        // Each of those would need either a TS-bypassing fake or a
        // strategy mutation to exercise; both would test the shim,
        // not the orchestrator. The test file documents this in the
        // hidden-domain block.
        'src/generators/angular/app-structure-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 80,
          statements: 85,
        },
      },
    },
    snapshotFormat: {
      escapeString: false,
      printBasicPrototype: false,
    },
  },
});

