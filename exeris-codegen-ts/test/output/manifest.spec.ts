import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { pruneAndWriteManifest, MANIFEST_NAME } from '../../src/output/manifest.js';

describe('pruneAndWriteManifest (T13 — output-tree ownership)', () => {
  let out: string;

  beforeEach(() => {
    out = mkdtempSync(join(tmpdir(), 'exeris-manifest-'));
  });

  afterEach(() => {
    rmSync(out, { recursive: true, force: true });
  });

  function emit(rel: string, content = 'x'): void {
    const full = join(out, rel);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
  }

  it('writes a sorted manifest of the produced files', () => {
    emit('z/last.ts');
    emit('a/first.ts');

    const pruned = pruneAndWriteManifest(out, ['z/last.ts', 'a/first.ts']);

    expect(pruned).toBe(0);
    const manifest = readFileSync(join(out, MANIFEST_NAME), 'utf-8');
    expect(manifest).toBe(
      '# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY\n' +
        'a/first.ts\n' +
        'z/last.ts\n',
    );
  });

  it('prunes a file emitted last run but not this run, and removes the empty dir', () => {
    // Run 1: two components.
    emit('components/order-list.component.ts');
    emit('components/colony-list.component.ts');
    pruneAndWriteManifest(out, [
      'components/order-list.component.ts',
      'components/colony-list.component.ts',
    ]);

    // Run 2: the Colony entity is gone — but its file is still on disk.
    const orphan = join(out, 'components/colony-list.component.ts');
    expect(existsSync(orphan)).toBe(true);
    const pruned = pruneAndWriteManifest(out, ['components/order-list.component.ts']);

    expect(pruned).toBe(1);
    expect(existsSync(orphan)).toBe(false);
    expect(existsSync(join(out, 'components/order-list.component.ts'))).toBe(true);
  });

  it('never prunes a user-authored file (absent from the manifest)', () => {
    emit('services/order.service.ts');
    pruneAndWriteManifest(out, ['services/order.service.ts']);

    // human adds a hand-written file in the same tree
    emit('services/my-helper.ts', 'export const x = 1;');

    const pruned = pruneAndWriteManifest(out, ['services/order.service.ts']);
    expect(pruned).toBe(0);
    expect(existsSync(join(out, 'services/my-helper.ts'))).toBe(true);
  });

  it('keeps a skipped-but-still-owned file (in producedPaths) across runs', () => {
    emit('a.ts');
    pruneAndWriteManifest(out, ['a.ts']);
    // run 2 still owns a.ts even if it was not rewritten this run
    const pruned = pruneAndWriteManifest(out, ['a.ts']);
    expect(pruned).toBe(0);
    expect(existsSync(join(out, 'a.ts'))).toBe(true);
  });

  it('produces a deterministic manifest regardless of input order', () => {
    const a = mkdtempSync(join(tmpdir(), 'exeris-det-a-'));
    const b = mkdtempSync(join(tmpdir(), 'exeris-det-b-'));
    try {
      pruneAndWriteManifest(a, ['z.ts', 'a.ts', 'm.ts']);
      pruneAndWriteManifest(b, ['a.ts', 'm.ts', 'z.ts']);
      expect(readFileSync(join(a, MANIFEST_NAME), 'utf-8')).toBe(
        readFileSync(join(b, MANIFEST_NAME), 'utf-8'),
      );
    } finally {
      rmSync(a, { recursive: true, force: true });
      rmSync(b, { recursive: true, force: true });
    }
  });
});
