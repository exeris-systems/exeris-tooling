import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, unlinkSync, rmdirSync, lstatSync, type Stats } from 'node:fs';
import { join, dirname, resolve, sep } from 'node:path';
import { carriesGeneratorMarker } from '../generators/file-header.js';

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
 * The second header line of a manifest whose entries are exactly the files the tool wrote or
 * found byte-identical to what it would write. A manifest without it may also record files a run
 * skipped because they already existed, so its entries do not prove ownership (see
 * {@link ManifestState.legacy}). Readers ignore `#` lines, so a reader that does not know this line
 * reads the manifest unchanged.
 */
export const MANIFEST_OWNERSHIP_LINE = '# ownership: written';

/** What the previous run's manifest says. */
export interface ManifestState {
  /** The recorded paths, in canonical form. Empty when there is no manifest. */
  readonly entries: ReadonlySet<string>;
  /**
   * The manifest exists and lacks {@link MANIFEST_OWNERSHIP_LINE}: an entry may name a file the
   * tool skipped because it existed, so an entry alone does not make a file the tool's.
   */
  readonly legacy: boolean;
}

/** Reads the manifest in `outputPath`; an absent manifest has no entries and is not legacy. */
export function readManifestState(outputPath: string): ManifestState {
  const manifestPath = join(outputPath, MANIFEST_NAME);
  if (!existsSync(manifestPath)) return { entries: new Set<string>(), legacy: false };
  const lines = readFileSync(manifestPath, 'utf-8').split('\n').map((l) => l.trim());
  return {
    entries: canonicalSet(lines.filter((l) => l.length > 0 && !l.startsWith('#'))),
    legacy: !lines.includes(MANIFEST_OWNERSHIP_LINE),
  };
}

/**
 * The paths the previous run's manifest records, in canonical form. Empty when there is no
 * manifest.
 */
export function readManifest(outputPath: string): Set<string> {
  return new Set(readManifestState(outputPath).entries);
}

/**
 * Whether reaching `rel` from `outputPath` passes through a symbolic link below the output root.
 * The output root itself may be a link; a link inside the tree may point anywhere, so nothing is
 * written or deleted through one.
 */
export function throughSymlink(outputPath: string, rel: string): boolean {
  const segments = rel.replace(/\\/g, '/').split('/').filter((s) => s.length > 0 && s !== '.');
  let current = outputPath;
  for (const segment of segments.slice(0, -1)) {
    current = join(current, segment);
    const stat = lstatOrNull(current);
    if (stat === null) return false;
    if (stat.isSymbolicLink()) return true;
  }
  return false;
}

/** `lstatSync`, or null when nothing is at `path`. A dangling link is something. */
export function lstatOrNull(path: string): Stats | null {
  try {
    return lstatSync(path);
  } catch {
    return null;
  }
}

/** What happens to the previous run's entries this run does not produce. */
export interface PrunePlan {
  /** Regular files the tool owned, deleted. Sorted, canonical. */
  readonly prune: string[];
  /**
   * Entries dropped from the manifest and left on disk: a seed file (written once for the
   * consumer to edit), a path that is a link or is reached through one, or an entry of a manifest
   * without the ownership line whose file lacks the generator header. Sorted, canonical.
   */
  readonly release: string[];
}

/**
 * The orphans of this run: entries of the previous manifest that are not among `producedPaths`.
 * Reads the disk and the manifest only.
 *
 * @param producedPaths - every path this run produced, owned or not; an entry among them is no orphan
 * @param seedPaths - the paths of seed files, in canonical form; an orphaned seed is released, never
 *   deleted
 */
export function planPrune(
  outputPath: string,
  producedPaths: readonly string[],
  seedPaths: ReadonlySet<string> = new Set<string>(),
): PrunePlan {
  const produced = canonicalSet(producedPaths);
  const root = resolve(outputPath);
  const prune: string[] = [];
  const release: string[] = [];

  const manifest = readManifestState(outputPath);
  for (const rel of [...manifest.entries].sort()) {
    if (produced.has(rel) || rel === MANIFEST_NAME) continue;
    const full = resolve(join(outputPath, rel));
    // Defence in depth (mirrors the Java OutputWriter): a tampered/corrupted
    // manifest with `..` segments must never delete outside the output tree.
    if (full !== root && !full.startsWith(root + sep)) continue;
    const stat = lstatOrNull(full);
    if (stat === null) continue;
    if (seedPaths.has(rel) || stat.isSymbolicLink() || throughSymlink(outputPath, rel)) {
      release.push(rel);
    } else if (stat.isFile()) {
      // An entry of a manifest without the ownership line does not prove ownership: its file is
      // deleted only when it starts with the header the generator writes.
      const proven = !manifest.legacy || carriesGeneratorMarker(readFileSync(full, 'utf-8'));
      (proven ? prune : release).push(rel);
    }
  }
  return { prune, release };
}

/** Options of {@link pruneOrphansAndWriteManifest}. */
export interface PruneOptions {
  /**
   * The paths the next manifest records. Defaults to `producedPaths`; the writer passes the subset
   * it owns, leaving out a file it skipped because it was not the tool's.
   */
  readonly ownedPaths?: readonly string[];
  /** Seed paths in canonical form: released, never deleted, when no longer produced. */
  readonly seedPaths?: ReadonlySet<string>;
}

/**
 * Generation owns its output tree (T13). Deletes the files {@link planPrune} names, prunes
 * directories left empty by that removal, and writes a sorted (deterministic) manifest of the
 * paths this run owns.
 *
 * Safe by construction: only paths from the previous manifest are eligible for
 * deletion, so user-authored files (never in the manifest) are never removed, and neither is a
 * seed file, a link, or anything reached through a link.
 *
 * @param outputPath - the generated-output root
 * @param producedPaths - every relative path this run produced; recorded in canonical form (see
 *   {@link canonicalManifestPath}) unless `options.ownedPaths` narrows the record
 * @returns the number of orphaned files deleted
 */
export function pruneOrphansAndWriteManifest(
  outputPath: string,
  producedPaths: readonly string[],
  options: PruneOptions = {},
): number {
  const root = resolve(outputPath);
  const { prune } = planPrune(outputPath, producedPaths, options.seedPaths);

  const touchedDirs = new Set<string>();
  for (const rel of prune) {
    const full = resolve(join(outputPath, rel));
    unlinkSync(full);
    touchedDirs.add(dirname(full));
  }

  // Prune now-empty directories upward, stopping at the output root.
  for (const start of touchedDirs) {
    let dir = resolve(start);
    while (dir.startsWith(root) && dir !== root && existsSync(dir) && readdirSync(dir).length === 0) {
      rmdirSync(dir);
      dir = dirname(dir);
    }
  }

  writeManifest(outputPath, options.ownedPaths ?? producedPaths);
  return prune.length;
}

/** Writes the manifest recording `ownedPaths`, sorted, in the ownership format. */
export function writeManifest(outputPath: string, ownedPaths: Iterable<string>): void {
  const lines = [MANIFEST_HEADER, MANIFEST_OWNERSHIP_LINE, ...[...canonicalSet(ownedPaths)].sort()];
  if (!existsSync(outputPath)) mkdirSync(outputPath, { recursive: true });
  writeFileSync(join(outputPath, MANIFEST_NAME), lines.join('\n') + '\n');
}
