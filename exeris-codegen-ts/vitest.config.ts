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

      // Two-layer gate, as of stages 4c-2 + this PR:
      //
      // 1. GLOBAL 85% threshold (lines/functions/branches/statements)
      //    on the whole instrumented surface. Catches REGRESSIONS at
      //    the aggregate level — if anyone adds a new src/ file
      //    without tests, or removes coverage from an existing one
      //    big enough to drag the aggregate below 85%, the build
      //    fails. As of stage 4c-2 the actual aggregate sits at
      //    ~92 L / ~90 B / ~85 F / ~92 S, so all four floors are
      //    above 85% with healthy margin.
      //
      // 2. PER-FILE 85% thresholds on every file that has tests
      //    today. Protects specific paths from LOCAL regressions
      //    even when the aggregate is high enough to absorb them
      //    (e.g. dropping store-gen from 100% to 60% wouldn't dent
      //    the aggregate below 85% but absolutely should fail the
      //    build). The per-file entries are kept rather than
      //    collapsed because the global gate is a floor, not a
      //    contract — per-file thresholds pin the actual
      //    well-tested files against silent erosion.
      //
      // Stage 4d added: src/config.ts + src/generators/angular/
      // {app-structure-gen, index}.ts now have per-file gates below.
      // src/generators/angular/landing-gen.ts is still NOT in the
      // per-file map (stage 4e pending — it has pre-existing tsc
      // errors + a FieldMetadata-model misalignment that need a
      // fix-then-cover PR before it can join the gate). It still
      // shows up in the global denominator and DOES contribute to
      // the aggregate falling below 85% if its uncovered LOC grows
      // — the global gate disciplines it.
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
        'src/generators/api/**/*.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // Stage 4a: foundational angular generators covered per-file
        // (not directory-wide yet — landing-gen.ts has pre-existing
        // tsc errors + model-misalignment bugs that need a fix-then-
        // test PR before it can join the gate).
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
        // Stage 4b: CRUD-core pair.
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
        // Stage 4c-1: state surface — Signal Store.
        'src/generators/angular/store-gen.ts': {
          lines: 85,
          functions: 85,
          branches: 85,
          statements: 85,
        },
        // Stage 4c-2: side-effects surface — Event Handler + Saga
        // State Machine. event-gen gets a relaxed 80% branch
        // threshold: the residual ~17% branch gap is composed
        // entirely of `||` defensive fallbacks against shapes the
        // Zod DomainEventMetadataSchema can't produce. The schema
        // auto-defaults `fields: []` and `tags: []`, so
        // `event.fields || []` at the generateEventInterfaces seam
        // and `events.map(...).join('\n') || '  | never'` at the
        // union-type fallback are structurally unreachable through
        // the public Zod-validated API. Documented inline rather
        // than papered over with synthetic fixtures.
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
        // Stage 4d: config loader + angular barrel + app-structure
        // orchestrator. config.ts and app-structure-gen.ts are
        // covered directly; angular/index.ts is a re-export barrel
        // with no runtime branches — the barrel spec pins the
        // public-export contract only.
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
        //   - `clientConfig.apiVersion ?? 'v1'` fallback — every
        //     strategy sets apiVersion='v1'.
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

