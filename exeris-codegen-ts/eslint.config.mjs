// @ts-check
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// Lints the hand-written sources only: the CLI (src/), its tests (test/) and the build scripts
// (scripts/). Build output, coverage and the gate scratch directories hold generated code, which
// the gates check by compiling it, not by linting it.
export default tseslint.config(
  {
    ignores: ['node_modules/', 'dist/', 'coverage/', '.verify-tmp/', '.fe-*/'],
  },
  {
    files: ['src/**/*.ts', 'test/**/*.ts', 'vitest.config.ts'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      // A generator implements a fixed interface, so a parameter it does not read stays in the
      // signature; the leading underscore marks it as deliberately unused.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
    },
  },
  {
    files: ['scripts/**/*.mjs', 'eslint.config.mjs'],
    extends: [js.configs.recommended],
    languageOptions: {
      globals: globals.node,
    },
  },
);
