/**
 * The TypeScript section of `docs/generators.md` names every TypeScript generator, and only
 * generators that exist.
 *
 * The section explains a path in the TypeScript tree's `.exeris-codegen-manifest` by naming its
 * producer. A generator missing from it leaves its paths unexplained, and a row naming a removed
 * file explains them wrongly. The source tree is the authority: every `src/generators/**\/*-gen.ts`
 * file is a row of the table, named by its path relative to `src/generators/`. The section is
 * descriptive (ADR-097 reserves machine-readable `ts` rows), so this guard checks names, not paths.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The repository root is two levels up from this file (exeris-codegen-ts/test/). A missing
// document fails the suite rather than skipping it.
const DOC = fileURLToPath(new URL('../../docs/generators.md', import.meta.url));
const GENERATORS = fileURLToPath(new URL('../src/generators/', import.meta.url));

const BEGIN = '<!-- catalogue:ts:begin -->';
const END = '<!-- catalogue:ts:end -->';

function generatorFiles(): string[] {
  return readdirSync(GENERATORS, { recursive: true, encoding: 'utf8' })
    .map((p) => p.replace(/\\/g, '/'))
    .filter((p) => p.endsWith('-gen.ts'))
    .sort();
}

function tableRows(): string[] {
  const doc = readFileSync(DOC, 'utf8');
  const begin = doc.indexOf(BEGIN);
  const end = doc.indexOf(END);
  expect(begin, `${BEGIN} in ${DOC}`).toBeGreaterThanOrEqual(0);
  expect(end, `${END} after ${BEGIN} in ${DOC}`).toBeGreaterThan(begin);
  return doc
    .slice(begin, end)
    .split('\n')
    .map((line) => /^\|\s*`([\w/.-]+-gen\.ts)`\s*\|/.exec(line.trim())?.[1])
    .filter((name): name is string => name !== undefined)
    .sort();
}

describe('generator catalogue: the TypeScript section names every generator', () => {
  it('finds the generators the orchestrator composes (wrong directory otherwise)', () => {
    expect(generatorFiles()).toEqual(expect.arrayContaining(['api/type-gen.ts', 'angular/app-structure-gen.ts']));
  });

  it('has one row per *-gen.ts file and no row without one', () => {
    expect(tableRows()).toEqual(generatorFiles());
  });
});
