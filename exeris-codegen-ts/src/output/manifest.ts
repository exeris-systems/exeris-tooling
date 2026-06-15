import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, statSync, unlinkSync, rmdirSync } from 'node:fs';
import { join, dirname, resolve, sep } from 'node:path';

/**
 * Name of the per-output-tree manifest file (mirrors the Java
 * `OutputWriter.MANIFEST_NAME`). Records the relative paths this tool emitted so
 * the next run can prune orphans (T13).
 */
export const MANIFEST_NAME = '.exeris-codegen-manifest';

const MANIFEST_HEADER = '# Exeris Tooling generated-output manifest - DO NOT EDIT MANUALLY';

/**
 * Generation owns its output tree (T13). Deletes files emitted by a previous run
 * that the current run no longer produces (orphans), prunes directories left
 * empty by that removal, and writes a sorted (deterministic) manifest of the
 * current run's intended output set.
 *
 * Safe by construction: only paths from the previous manifest are eligible for
 * deletion, so user-authored files (never in the manifest) are never removed.
 *
 * @param outputPath the generated-output root
 * @param producedPaths every relative path this run intends to own (written or
 *   skipped-because-unchanged), forward-slash normalised
 * @returns the number of orphaned files deleted
 */
export function pruneOrphansAndWriteManifest(outputPath: string, producedPaths: string[]): number {
  const manifestPath = join(outputPath, MANIFEST_NAME);
  const produced = new Set(producedPaths.map((p) => p.replace(/\\/g, '/')));
  const root = resolve(outputPath);

  let previous: string[] = [];
  if (existsSync(manifestPath)) {
    previous = readFileSync(manifestPath, 'utf-8')
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.length > 0 && !l.startsWith('#'));
  }

  let pruned = 0;
  const touchedDirs = new Set<string>();
  for (const rel of previous) {
    if (produced.has(rel) || rel === MANIFEST_NAME) continue;
    const full = resolve(join(outputPath, rel));
    // Defence in depth (mirrors the Java OutputWriter): a tampered/corrupted
    // manifest with `..` segments must never delete outside the output tree.
    if (full !== root && !full.startsWith(root + sep)) continue;
    if (existsSync(full) && statSync(full).isFile()) {
      unlinkSync(full);
      pruned++;
      touchedDirs.add(dirname(full));
    }
  }

  // Prune now-empty directories upward, stopping at the output root.
  for (const start of touchedDirs) {
    let dir = resolve(start);
    while (dir.startsWith(root) && dir !== root && existsSync(dir) && readdirSync(dir).length === 0) {
      rmdirSync(dir);
      dir = dirname(dir);
    }
  }

  const lines = [MANIFEST_HEADER, ...[...produced].sort()];
  if (!existsSync(outputPath)) mkdirSync(outputPath, { recursive: true });
  writeFileSync(manifestPath, lines.join('\n') + '\n');
  return pruned;
}
