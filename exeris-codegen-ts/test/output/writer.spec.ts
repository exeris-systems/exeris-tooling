/**
 * The write rule: the previous run's manifest decides what the generator may replace.
 *
 * Pinned: an owned file is rewritten when it differs, without `--overwrite`; an owned seed file
 * (`overwritable: false`) is kept; an existing file the manifest does not record is neither
 * replaced nor recorded, unless `--overwrite`; an owned file the run no longer produces is pruned.
 * The last describe block runs the rule over the orchestrator's real output for `@View` pages.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { planWrites, writeGeneratedFiles, type FileToWrite } from '../../src/output/writer.js';
import { MANIFEST_NAME, readManifest } from '../../src/output/manifest.js';
import { buildGeneratedFiles } from '../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../src/config.js';
import { ViewMetadataSchema, type ViewMetadata } from '../../src/models/domain-model.js';

let out: string;

beforeEach(() => {
  out = mkdtempSync(join(tmpdir(), 'exeris-writer-'));
});

afterEach(() => {
  rmSync(out, { recursive: true, force: true });
});

const read = (rel: string): string => readFileSync(join(out, rel), 'utf-8');

function onDisk(rel: string, content: string): void {
  const full = join(out, rel);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, content);
}

const keep = { overwrite: false };

describe('writeGeneratedFiles', () => {
  it('creates absent files and records them as owned', () => {
    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    expect(plan).toEqual([{ path: 'pages/a.ts', action: 'create', owned: true }]);
    expect(read('pages/a.ts')).toBe('A');
    expect([...readManifest(out)]).toEqual(['pages/a.ts']);
  });

  it('rewrites an owned file whose content differs, without overwrite', () => {
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A1' }], keep);
    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A2' }], keep);

    expect(plan[0].action).toBe('rewrite');
    expect(read('pages/a.ts')).toBe('A2');
  });

  it('leaves an owned file with the same content unwritten', () => {
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);
    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    expect(plan[0]).toEqual({ path: 'pages/a.ts', action: 'unchanged', owned: true });
  });

  it('keeps an owned seed file the consumer edited, and keeps owning it', () => {
    const seed: FileToWrite = { path: 'src/app/app.routes.ts', content: 'generated', overwritable: false };
    writeGeneratedFiles(out, [seed], keep);
    onDisk('src/app/app.routes.ts', 'edited');

    const { plan } = writeGeneratedFiles(out, [seed], keep);

    expect(plan[0]).toEqual({ path: 'src/app/app.routes.ts', action: 'keep-seed', owned: true });
    expect(read('src/app/app.routes.ts')).toBe('edited');
    expect(readManifest(out).has('src/app/app.routes.ts')).toBe(true);
  });

  it('replaces an owned seed file under overwrite', () => {
    const seed: FileToWrite = { path: 'package.json', content: 'generated', overwritable: false };
    writeGeneratedFiles(out, [seed], keep);
    onDisk('package.json', 'edited');

    writeGeneratedFiles(out, [seed], { overwrite: true });

    expect(read('package.json')).toBe('generated');
  });

  it('never replaces or records an existing file no previous run generated', () => {
    onDisk('pages/a.ts', 'hand-written');

    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    expect(plan[0]).toEqual({ path: 'pages/a.ts', action: 'skip-unowned', owned: false });
    expect(read('pages/a.ts')).toBe('hand-written');
    expect(readManifest(out).has('pages/a.ts')).toBe(false);
  });

  it('does not take over an existing file whose content matches, without overwrite', () => {
    onDisk('pages/a.ts', 'A');

    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    expect(plan[0].action).toBe('skip-unowned');
    expect(readManifest(out).has('pages/a.ts')).toBe(false);
  });

  it('replaces an unowned file under overwrite, and owns it from then on', () => {
    onDisk('pages/a.ts', 'hand-written');

    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A1' }], { overwrite: true });
    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A2' }], keep);

    expect(plan[0].action).toBe('rewrite');
    expect(read('pages/a.ts')).toBe('A2');
  });

  it('prunes an owned file the run no longer produces, and never an unowned one', () => {
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }, { path: 'pages/b.ts', content: 'B' }], keep);
    onDisk('pages/mine.ts', 'hand-written');

    const { pruned } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    expect(pruned).toBe(1);
    expect(existsSync(join(out, 'pages/b.ts'))).toBe(false);
    expect(read('pages/mine.ts')).toBe('hand-written');
  });

  it('does not touch a file the run skipped as unowned once it stops being produced', () => {
    onDisk('pages/a.ts', 'hand-written');
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    const { pruned } = writeGeneratedFiles(out, [], keep);

    expect(pruned).toBe(0);
    expect(read('pages/a.ts')).toBe('hand-written');
  });

  it('reads an owned path recorded with a ./ prefix as the same file', () => {
    onDisk('package.json', 'old');
    writeFileSync(join(out, MANIFEST_NAME), '# header\n./package.json\n');

    const { plan } = writeGeneratedFiles(out, [{ path: 'package.json', content: 'new' }], keep);

    expect(plan[0].action).toBe('rewrite');
  });
});

describe('planWrites', () => {
  it('writes nothing to disk', () => {
    onDisk('pages/a.ts', 'hand-written');

    const plan = planWrites(out, [{ path: 'pages/a.ts', content: 'A' }, { path: 'pages/b.ts', content: 'B' }], keep);

    expect(plan.map((entry) => entry.action)).toEqual(['skip-unowned', 'create']);
    expect(existsSync(join(out, 'pages/b.ts'))).toBe(false);
    expect(existsSync(join(out, MANIFEST_NAME))).toBe(false);
  });
});

describe('regenerating @View pages', () => {
  const config: GeneratorConfig = { ...DEFAULT_CONFIG, scaffold: false };

  function page(name: string, title: string): ViewMetadata {
    return ViewMetadataSchema.parse({
      name,
      title,
      route: name.toLowerCase(),
      regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: title }] }],
    });
  }

  it('rewrites a changed page and prunes a deleted one, without overwrite', () => {
    writeGeneratedFiles(out, buildGeneratedFiles([], [], config, [page('About', 'About us'), page('Pricing', 'Pricing')]), keep);
    expect(read('pages/about.component.ts')).toContain('About us');
    expect(read('view.routes.ts')).toContain('pricingRoutes');

    const { plan, pruned } = writeGeneratedFiles(out, buildGeneratedFiles([], [], config, [page('About', 'Who we are')]), keep);

    expect(plan.find((entry) => entry.path === 'pages/about.component.ts')?.action).toBe('rewrite');
    expect(read('pages/about.component.ts')).toContain('Who we are');
    expect(read('pages/about.component.ts')).not.toContain('About us');
    expect(pruned).toBe(2);
    expect(existsSync(join(out, 'pages/pricing.component.ts'))).toBe(false);
    expect(existsSync(join(out, 'pages/pricing.route.ts'))).toBe(false);
    expect(read('view.routes.ts')).not.toContain('pricingRoutes');
  });

  it('keeps an edited app shell file and rewrites a changed page, with the scaffold on', () => {
    writeGeneratedFiles(out, buildGeneratedFiles([], [], DEFAULT_CONFIG, [page('About', 'About us')]), keep);
    onDisk('src/app/app.routes.ts', 'edited');
    onDisk('package.json', 'edited');

    writeGeneratedFiles(out, buildGeneratedFiles([], [], DEFAULT_CONFIG, [page('About', 'Who we are')]), keep);

    expect(read('src/app/app.routes.ts')).toBe('edited');
    expect(read('package.json')).toBe('edited');
    expect(read('src/app/pages/about.component.ts')).toContain('Who we are');
  });

  it('leaves a hand-written file beside the generated pages alone', () => {
    onDisk('pages/custom.component.ts', 'hand-written');
    writeGeneratedFiles(out, buildGeneratedFiles([], [], config, [page('About', 'About us')]), keep);
    writeGeneratedFiles(out, buildGeneratedFiles([], [], config, []), keep);

    expect(read('pages/custom.component.ts')).toBe('hand-written');
    expect(existsSync(join(out, 'pages/about.component.ts'))).toBe(false);
  });
});
