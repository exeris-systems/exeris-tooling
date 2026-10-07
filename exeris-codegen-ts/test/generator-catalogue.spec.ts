/**
 * `docs/generator-catalogue.md` names every TypeScript generator, and only generators that exist.
 *
 * The catalogue explains a path observed in `.exeris-codegen-manifest` by naming its producer. A
 * generator missing from it leaves its paths unexplained, and a row naming a removed file explains
 * them wrongly. The source tree is the authority: every `src/generators/**\/*-gen.ts` file must be
 * a row of the TypeScript table, named by its path relative to `src/generators/`.
 */

import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// Repo root is two levels up from this file (exeris-codegen-ts/test/). A missing catalogue must
// fail the suite, not skip it.
const CATALOGUE = fileURLToPath(new URL('../../docs/generator-catalogue.md', import.meta.url));
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
  const doc = readFileSync(CATALOGUE, 'utf8');
  const begin = doc.indexOf(BEGIN);
  const end = doc.indexOf(END);
  expect(begin, `${BEGIN} in ${CATALOGUE}`).toBeGreaterThanOrEqual(0);
  expect(end, `${END} after ${BEGIN} in ${CATALOGUE}`).toBeGreaterThan(begin);
  return doc
    .slice(begin, end)
    .split('\n')
    .map((line) => /^\|\s*`([\w/.-]+-gen\.ts)`\s*\|/.exec(line.trim())?.[1])
    .filter((name): name is string => name !== undefined)
    .sort();
}

describe('generator catalogue: the TypeScript table names every generator', () => {
  it('finds the generators the orchestrator composes (wrong directory otherwise)', () => {
    expect(generatorFiles()).toEqual(expect.arrayContaining(['api/type-gen.ts', 'angular/app-structure-gen.ts']));
  });

  it('has one row per *-gen.ts file and no row without one', () => {
    expect(tableRows()).toEqual(generatorFiles());
  });
});
