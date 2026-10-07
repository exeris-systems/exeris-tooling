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
 * The canonical form of a manifest path, the form the Java `OutputWriter` records:
 * relative, forward-slash separated, with no empty, `.` or `..` segments. A `..` segment
 * is resolved against the segment before it; a path that is absolute, empty, or climbs
 * above the output root has no canonical form and yields `null`, so it is never owned
 * and never pruned.
 *
 * Both sides of the orphan comparison pass through here: a previous manifest that
 * recorded `./package.json` and a run that produces `package.json` name the same file.
 */
export function canonicalManifestPath(path: string): string | null {
  const slashed = path.replace(/\\/g, '/');
  if (slashed.startsWith('/') || /^[A-Za-z]:/.test(slashed)) return null;
  const segments: string[] = [];
  for (const segment of slashed.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      if (segments.length === 0) return null;
      segments.pop();
      continue;
    }
    segments.push(segment);
  }
  return segments.length === 0 ? null : segments.join('/');
}

function canonicalSet(paths: Iterable<string>): Set<string> {
  const result = new Set<string>();
  for (const path of paths) {
    const canonical = canonicalManifestPath(path);
    if (canonical !== null) result.add(canonical);
  }
  return result;
}

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
 *   skipped-because-unchanged); recorded in canonical form (see {@link canonicalManifestPath})
 * @returns the number of orphaned files deleted
 */
export function pruneOrphansAndWriteManifest(outputPath: string, producedPaths: string[]): number {
  const manifestPath = join(outputPath, MANIFEST_NAME);
  const produced = canonicalSet(producedPaths);
  const root = resolve(outputPath);

  let previous = new Set<string>();
  if (existsSync(manifestPath)) {
    previous = canonicalSet(
      readFileSync(manifestPath, 'utf-8')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l.length > 0 && !l.startsWith('#')),
    );
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
