/**
 * The write rule: the previous run's manifest decides what the generator may replace.
 *
 * Pinned: an owned file is rewritten when it differs, without `--overwrite`; an owned seed file
 * (`overwritable: false`) is kept; an existing file the manifest does not record is neither
 * replaced nor recorded, unless `--overwrite`; an owned file the run no longer produces is pruned.
 * A manifest without the ownership line proves ownership only for a file that already matches or
 * starts with the generator header;
 * seed files are released, never deleted; nothing is written or deleted through a symbolic link.
 * The orchestrator-backed blocks run the rule over real output for `@View` pages and the scaffold.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { planOrphans, planWrites, writeGeneratedFiles, type FileToWrite } from '../../src/output/writer.js';
import { MANIFEST_NAME, MANIFEST_OWNERSHIP_LINE, readManifest, readManifestState } from '../../src/output/manifest.js';
import { buildGeneratedFiles, SEED_PATHS } from '../../src/orchestrator.js';
import { DEFAULT_CONFIG, type GeneratorConfig } from '../../src/config.js';
import { fileHeader } from '../../src/generators/file-header.js';
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
    writeFileSync(join(out, MANIFEST_NAME), '# header\n# ownership: written\n./package.json\n');

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

/** A manifest in the format that recorded every produced path, skipped ones included. */
function legacyManifest(...entries: string[]): void {
  writeFileSync(
    join(out, MANIFEST_NAME),
    '# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n' + entries.map((e) => `${e}\n`).join(''),
  );
}

describe('a manifest without the ownership line', () => {
  it('does not take over a listed file whose content differs, and neither records nor prunes it', () => {
    onDisk('pages/about.component.ts', 'hand-written');
    legacyManifest('pages/about.component.ts');

    const plan = planWrites(out, [{ path: 'pages/about.component.ts', content: 'generated' }], keep);
    expect(plan[0]).toEqual({ path: 'pages/about.component.ts', action: 'skip-unowned', owned: false });

    const { pruned } = writeGeneratedFiles(out, [{ path: 'pages/about.component.ts', content: 'generated' }], keep);

    expect(pruned).toBe(0);
    expect(read('pages/about.component.ts')).toBe('hand-written');
    expect(readManifest(out).has('pages/about.component.ts')).toBe(false);
  });

  it('rewrites and owns a listed file that differs but starts with the generator header', () => {
    const generated09 = `${fileHeader({ title: 'Order Service' })}\n\nrestore(id: string) {}\n`;
    const generated010 = `${fileHeader({ title: 'Order Service' })}\n\narchive(id: string) {}\n`;
    onDisk('services/order.service.ts', generated09);
    legacyManifest('services/order.service.ts');

    const { plan } = writeGeneratedFiles(out, [{ path: 'services/order.service.ts', content: generated010 }], keep);

    expect(plan[0]).toEqual({ path: 'services/order.service.ts', action: 'rewrite', owned: true });
    expect(read('services/order.service.ts')).toBe(generated010);
    expect(readManifest(out).has('services/order.service.ts')).toBe(true);
  });

  it('does not accept the header as proof for a file the manifest does not list', () => {
    onDisk('services/order.service.ts', `${fileHeader({ title: 'Order Service' })}\n`);
    legacyManifest();

    const { plan } = writeGeneratedFiles(out, [{ path: 'services/order.service.ts', content: 'generated' }], keep);

    expect(plan[0].action).toBe('skip-unowned');
  });

  it('releases, not deletes, a listed file without the header that the run no longer produces', () => {
    onDisk('pages/about.component.ts', 'hand-written');
    legacyManifest('pages/about.component.ts');

    expect(planOrphans(out, [])).toEqual({ prune: [], release: ['pages/about.component.ts'] });
    const { pruned, released } = writeGeneratedFiles(out, [], keep);

    expect(pruned).toBe(0);
    expect(released).toEqual(['pages/about.component.ts']);
    expect(read('pages/about.component.ts')).toBe('hand-written');
    expect(readManifest(out).has('pages/about.component.ts')).toBe(false);
  });

  it('prunes a listed file with the header that the run no longer produces', () => {
    onDisk('pages/about.component.ts', `${fileHeader({ title: 'About Page Component' })}\n`);
    legacyManifest('pages/about.component.ts');

    const { pruned } = writeGeneratedFiles(out, [], keep);

    expect(pruned).toBe(1);
    expect(existsSync(join(out, 'pages/about.component.ts'))).toBe(false);
  });

  it('adopts a listed file whose content is what the run produces', () => {
    onDisk('pages/about.component.ts', 'generated');
    legacyManifest('pages/about.component.ts');

    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/about.component.ts', content: 'generated' }], keep);

    expect(plan[0]).toEqual({ path: 'pages/about.component.ts', action: 'unchanged', owned: true });
    expect(readManifest(out).has('pages/about.component.ts')).toBe(true);
  });

  it('keeps a listed seed file and keeps it owned', () => {
    onDisk('src/styles.css', 'edited');
    legacyManifest('src/styles.css');

    const { plan } = writeGeneratedFiles(out, [{ path: 'src/styles.css', content: 'generated', overwritable: false }], keep);

    expect(plan[0].action).toBe('keep-seed');
    expect(read('src/styles.css')).toBe('edited');
    expect(readManifest(out).has('src/styles.css')).toBe(true);
  });

  it('is rewritten in the ownership format', () => {
    legacyManifest('pages/a.ts');
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A' }], keep);

    expect(readManifestState(out).legacy).toBe(false);
    expect(read(MANIFEST_NAME).split('\n')[1]).toBe(MANIFEST_OWNERSHIP_LINE);
  });

  it('makes an adopted file owned on the next run', () => {
    onDisk('pages/a.ts', 'A1');
    legacyManifest('pages/a.ts');
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A1' }], keep);

    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A2' }], keep);

    expect(plan[0].action).toBe('rewrite');
    expect(read('pages/a.ts')).toBe('A2');
  });
});

describe('seed files no longer produced', () => {
  it('releases an orphaned seed: kept on disk, dropped from the manifest', () => {
    const seed: FileToWrite = { path: 'package.json', content: 'generated', overwritable: false };
    writeGeneratedFiles(out, [seed, { path: 'pages/a.ts', content: 'A' }], keep);

    expect(planOrphans(out, [], SEED_PATHS)).toEqual({ prune: ['pages/a.ts'], release: ['package.json'] });
    const { pruned } = writeGeneratedFiles(out, [], { ...keep, seedPaths: SEED_PATHS });

    expect(pruned).toBe(1);
    expect(read('package.json')).toBe('generated');
    expect(readManifest(out).size).toBe(0);
  });

  it('keeps every scaffold seed, edited or not, when the scaffold is switched off', () => {
    const page = ViewMetadataSchema.parse({
      name: 'About',
      route: 'about',
      regions: [{ slot: 'main', components: [{ type: 'HERO', binding: { source: 'STATIC' }, props: 'Hi' }] }],
    });
    const scaffolded = buildGeneratedFiles([], [], DEFAULT_CONFIG, [page]);
    writeGeneratedFiles(out, scaffolded, { ...keep, seedPaths: SEED_PATHS });
    onDisk('package.json', 'edited');
    onDisk('src/app/app.routes.ts', 'edited');
    const seeds = scaffolded.filter((f) => f.overwritable === false).map((f) => f.path);
    const before = Object.fromEntries(seeds.map((p) => [p, read(p)]));

    const { pruned } = writeGeneratedFiles(
      out,
      buildGeneratedFiles([], [], { ...DEFAULT_CONFIG, scaffold: false }, [page]),
      { ...keep, seedPaths: SEED_PATHS },
    );

    for (const path of seeds) expect(read(path), path).toBe(before[path]);
    expect(read('package.json')).toBe('edited');
    expect(read('src/app/app.routes.ts')).toBe('edited');
    expect(existsSync(join(out, 'src/app/pages/about.component.ts'))).toBe(false);
    expect(existsSync(join(out, 'pages/about.component.ts'))).toBe(true);
    expect(pruned).toBe(scaffolded.length - seeds.length);
    for (const path of seeds) expect(readManifest(out).has(path), path).toBe(false);
  });

  it('names every seed the scaffold emits', () => {
    const emittedSeeds = buildGeneratedFiles([], [], { ...DEFAULT_CONFIG, generateTests: true, render: 'ssg' }, [
      ViewMetadataSchema.parse({ name: 'About', regions: [] }),
    ])
      .filter((f) => f.overwritable === false)
      .map((f) => f.path);
    expect(emittedSeeds.length).toBeGreaterThan(0);
    for (const path of emittedSeeds) expect(SEED_PATHS.has(path), path).toBe(true);
  });
});

describe('symbolic links', () => {
  let outside: string;

  beforeEach(() => {
    outside = mkdtempSync(join(tmpdir(), 'exeris-writer-outside-'));
    writeFileSync(join(outside, 'target.txt'), 'precious');
  });

  afterEach(() => {
    rmSync(outside, { recursive: true, force: true });
  });

  function replaceWithLink(rel: string, target: string): void {
    rmSync(join(out, rel), { recursive: true, force: true });
    symlinkSync(target, join(out, rel));
  }

  it('never writes through a link at an owned path', () => {
    writeGeneratedFiles(out, [{ path: 'a.ts', content: 'A1' }], keep);
    replaceWithLink('a.ts', join(outside, 'target.txt'));

    const { plan } = writeGeneratedFiles(out, [{ path: 'a.ts', content: 'A2' }], keep);

    expect(plan[0]).toEqual({ path: 'a.ts', action: 'skip-unowned', owned: false });
    expect(readFileSync(join(outside, 'target.txt'), 'utf-8')).toBe('precious');
  });

  it('replaces the link itself under overwrite, never its target', () => {
    writeGeneratedFiles(out, [{ path: 'a.ts', content: 'A1' }], keep);
    replaceWithLink('a.ts', join(outside, 'target.txt'));

    writeGeneratedFiles(out, [{ path: 'a.ts', content: 'A2' }], { overwrite: true });

    expect(lstatSync(join(out, 'a.ts')).isSymbolicLink()).toBe(false);
    expect(read('a.ts')).toBe('A2');
    expect(readFileSync(join(outside, 'target.txt'), 'utf-8')).toBe('precious');
  });

  it('never writes through a linked directory, overwrite included', () => {
    writeGeneratedFiles(out, [{ path: 'pages/a.ts', content: 'A1' }], keep);
    replaceWithLink('pages', outside);

    const { plan } = writeGeneratedFiles(out, [{ path: 'pages/target.txt', content: 'clobbered' }], { overwrite: true });

    expect(plan[0].action).toBe('skip-unowned');
    expect(readFileSync(join(outside, 'target.txt'), 'utf-8')).toBe('precious');
  });

  it('releases, never deletes, an orphan that is a link or lies under one', () => {
    writeGeneratedFiles(out, [{ path: 'a.ts', content: 'A' }, { path: 'pages/target.txt', content: 'T' }], keep);
    replaceWithLink('a.ts', join(outside, 'target.txt'));
    replaceWithLink('pages', outside);

    const { pruned } = writeGeneratedFiles(out, [], keep);

    expect(pruned).toBe(0);
    expect(readFileSync(join(outside, 'target.txt'), 'utf-8')).toBe('precious');
    expect(lstatSync(join(out, 'a.ts')).isSymbolicLink()).toBe(true);
    expect(readManifest(out).size).toBe(0);
  });
});

describe('a write that fails', () => {
  // `a.ts` is written first, so `a.ts/b.ts` cannot get its directory.
  const failing: FileToWrite[] = [{ path: 'a.ts', content: 'A' }, { path: 'a.ts/b.ts', content: 'B' }];

  it('keeps the files written before it owned, and prunes nothing', () => {
    writeGeneratedFiles(out, [{ path: 'old.ts', content: 'O' }], keep);

    expect(() => writeGeneratedFiles(out, failing, keep)).toThrow();

    expect(existsSync(join(out, 'old.ts'))).toBe(true);
    expect([...readManifest(out)].sort()).toEqual(['a.ts', 'old.ts']);
  });

  it('leaves a manifest without the ownership line as it is', () => {
    legacyManifest('old.ts');
    const before = read(MANIFEST_NAME);

    expect(() => writeGeneratedFiles(out, failing, keep)).toThrow();

    expect(read(MANIFEST_NAME)).toBe(before);
  });
});
