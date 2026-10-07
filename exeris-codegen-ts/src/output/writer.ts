/**
 * Writes a run's files into the output tree, deciding per file whether the tool may replace what is
 * on disk.
 *
 * Ownership is the generated-output manifest (`.exeris-codegen-manifest`): a path the previous run
 * recorded is the tool's, and any other existing file is not. The rule:
 *
 * - a file that does not exist is written, and becomes owned;
 * - an owned file is rewritten when its content differs, so a regenerated file never goes stale;
 * - an owned seed file (`overwritable: false`: `package.json`, `app.routes.ts`, `environment.ts`, …,
 *   the files a consumer is expected to edit) is written only when absent and kept as it is
 *   otherwise;
 * - an existing file the manifest does not record (hand-written, or in a directory generated into
 *   for the first time) is never replaced, and does not become owned.
 *
 * `overwrite` replaces every differing file, seed or unowned, and takes ownership of what it writes.
 *
 * Nothing outside the output tree is read or written, so a file a consumer has moved out of the tree
 * to own it is out of reach; and a file the manifest no longer records is never touched again.
 * Orphans, the owned files this run no longer produces, are pruned by
 * {@link pruneOrphansAndWriteManifest}, which is called here with the paths this run owns.
 */

import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { canonicalManifestPath, pruneOrphansAndWriteManifest, readManifest } from './manifest.js';

/** A file to write, relative to the output root. */
export interface FileToWrite {
  path: string;
  content: string;
  /** `false` marks a seed file: written when absent, replaced only under `overwrite`. */
  overwritable?: boolean;
}

export type WriteAction =
  /** Absent; written. */
  | 'create'
  /** Present with different content, and replaceable; written. */
  | 'rewrite'
  /** Present with the same content; not written. */
  | 'unchanged'
  /** An owned seed file present with different content; kept. */
  | 'keep-seed'
  /** Present and not owned; kept, and not recorded as owned. */
  | 'skip-unowned';

export interface PlannedWrite {
  /** The path relative to the output root, as the run produced it. */
  path: string;
  action: WriteAction;
  /** Whether the next manifest records this path. */
  owned: boolean;
}

export interface WriteResult {
  plan: PlannedWrite[];
  /** Owned files a previous run wrote and this run no longer produces, deleted. */
  pruned: number;
}

/** Decides what happens to each file, reading the disk and the previous manifest only. */
export function planWrites(
  outputPath: string,
  files: readonly FileToWrite[],
  options: { overwrite: boolean },
): PlannedWrite[] {
  const owned = readManifest(outputPath);
  return files.map((file) => {
    const full = join(outputPath, file.path);
    if (!existsSync(full)) return { path: file.path, action: 'create', owned: true };

    const canonical = canonicalManifestPath(file.path);
    const isOwned = canonical !== null && owned.has(canonical);
    const same = statSync(full).isFile() && readFileSync(full).equals(Buffer.from(file.content));

    if (!isOwned && !options.overwrite) return { path: file.path, action: 'skip-unowned', owned: false };
    if (same) return { path: file.path, action: 'unchanged', owned: true };
    if (isOwned && file.overwritable === false && !options.overwrite) {
      return { path: file.path, action: 'keep-seed', owned: true };
    }
    return { path: file.path, action: 'rewrite', owned: true };
  });
}

/**
 * Writes `files` under `outputPath` by {@link planWrites}, prunes orphans and records the manifest
 * of the paths this run owns.
 */
export function writeGeneratedFiles(
  outputPath: string,
  files: readonly FileToWrite[],
  options: { overwrite: boolean },
): WriteResult {
  const plan = planWrites(outputPath, files, options);

  plan.forEach((entry, i) => {
    if (entry.action !== 'create' && entry.action !== 'rewrite') return;
    const full = join(outputPath, entry.path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, files[i].content);
  });

  const ownedPaths = plan.filter((entry) => entry.owned).map((entry) => entry.path);
  const pruned = pruneOrphansAndWriteManifest(outputPath, ownedPaths);
  return { plan, pruned };
}
