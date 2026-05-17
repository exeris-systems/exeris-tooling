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

      // Per-file 85% gate, ratcheted. Each PR that brings a new
      // src/ area to >=85% adds the matching glob here. The global
      // floor stays at 0% while remaining areas (src/models,
      // src/generators) are still being built out — preventing
      // "fail forever" while the project bootstraps full coverage.
      // Once every src/ file has tests, the per-file entries can
      // collapse into a single global threshold of 85%.
      thresholds: {
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
      },
    },
    snapshotFormat: {
      escapeString: false,
      printBasicPrototype: false,
    },
  },
});

