/**
 * The `exeris-gen generate` command, run as a process: the guards that live in the CLI shell.
 *
 * Pinned: a missing or empty metadata directory generates and deletes nothing (the output tree and
 * its manifest are left as they are, a missing directory fails the run); `--dry-run` names the files
 * it would prune and release, and changes nothing.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { MANIFEST_NAME } from '../src/output/manifest.js';

const pkgRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const cli = join(pkgRoot, 'src', 'index.ts');
const tsx = pathToFileURL(join(pkgRoot, 'node_modules', 'tsx', 'dist', 'loader.mjs')).href;

let work: string;

beforeEach(() => {
  work = mkdtempSync(join(tmpdir(), 'exeris-cli-'));
  // A config file in the working directory, so no file above it is picked up.
  writeFileSync(join(work, 'exeris-codegen.json'), '{}');
});

afterEach(() => {
  rmSync(work, { recursive: true, force: true });
});

function generate(...args: string[]): { status: number | null; output: string } {
  const run = spawnSync(process.execPath, ['--import', tsx, cli, 'generate', ...args], {
    cwd: work,
    encoding: 'utf-8',
    env: { ...process.env, NO_COLOR: '1' },
  });
  return { status: run.status, output: `${run.stdout}${run.stderr}` };
}

function view(dir: string, name: string): void {
  mkdirSync(dir, { recursive: true });
  writeFileSync(
    join(dir, `view_${name}.json`),
    JSON.stringify({
      name,
      packageName: 'com.site',
      qualifiedName: `com.site.${name}`,
      view: { name, route: name.toLowerCase(), regions: [] },
    }),
  );
}

describe('exeris-gen generate', () => {
  it('fails on a missing metadata directory and deletes nothing', () => {
    view(join(work, 'meta'), 'About');
    expect(generate('-i', 'meta', '-o', 'out').status).toBe(0);
    const manifest = readFileSync(join(work, 'out', MANIFEST_NAME), 'utf-8');

    const run = generate('-i', 'missing', '-o', 'out');

    expect(run.status).toBe(1);
    expect(run.output).toContain('Input path does not exist');
    expect(existsSync(join(work, 'out', 'package.json'))).toBe(true);
    expect(existsSync(join(work, 'out', 'src/app/pages/about.component.ts'))).toBe(true);
    expect(readFileSync(join(work, 'out', MANIFEST_NAME), 'utf-8')).toBe(manifest);
  });

  it('generates and deletes nothing for an empty metadata directory', () => {
    view(join(work, 'meta'), 'About');
    generate('-i', 'meta', '-o', 'out');
    mkdirSync(join(work, 'empty'));

    const run = generate('-i', 'empty', '-o', 'out');

    expect(run.status).toBe(0);
    expect(run.output).toContain('Nothing was generated or deleted');
    expect(existsSync(join(work, 'out', 'src/app/pages/about.component.ts'))).toBe(true);
  });

  it('names what --dry-run would prune and release, and changes nothing', () => {
    view(join(work, 'meta'), 'About');
    view(join(work, 'meta'), 'Pricing');
    generate('-i', 'meta', '-o', 'out');
    rmSync(join(work, 'meta', 'view_Pricing.json'));
    const manifest = readFileSync(join(work, 'out', MANIFEST_NAME), 'utf-8');

    const pruning = generate('-i', 'meta', '-o', 'out', '--dry-run');

    expect(pruning.status).toBe(0);
    expect(pruning.output).toContain('Would prune: src/app/pages/pricing.component.ts');
    expect(pruning.output).toContain('Would prune: src/app/pages/pricing.route.ts');
    expect(existsSync(join(work, 'out', 'src/app/pages/pricing.component.ts'))).toBe(true);
    expect(readFileSync(join(work, 'out', MANIFEST_NAME), 'utf-8')).toBe(manifest);

    const releasing = generate('-i', 'meta', '-o', 'out', '--dry-run', '--no-scaffold');

    expect(releasing.output).toContain('Would release (kept, no longer generated): package.json');
    expect(releasing.output).toContain('Would prune: src/app/pages/about.component.ts');
    expect(existsSync(join(work, 'out', 'package.json'))).toBe(true);
  });

  it('shows a file a pre-ownership manifest lists but the run does not take over, under --dry-run', () => {
    view(join(work, 'meta'), 'About');
    mkdirSync(join(work, 'out', 'pages'), { recursive: true });
    writeFileSync(join(work, 'out', 'pages', 'about.component.ts'), 'hand-written');
    writeFileSync(
      join(work, 'out', MANIFEST_NAME),
      '# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\npages/about.component.ts\n',
    );

    const run = generate('-i', 'meta', '-o', 'out', '--no-scaffold', '--dry-run');

    expect(run.output).toContain('Would skip (not generated here): pages/about.component.ts');
    expect(run.output).toContain('Would create: pages/about.route.ts');
  });
});
