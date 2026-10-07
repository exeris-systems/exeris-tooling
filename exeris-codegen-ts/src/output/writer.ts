/**
 * Writes a run's files into the output tree, deciding per file whether the tool may replace what is
 * on disk, and which files a previous run wrote it may delete.
 *
 * Ownership is the generated-output manifest (`.exeris-codegen-manifest`): a path the previous run
 * recorded is the tool's, and any other existing file is not. The rule:
 *
 * - a file that does not exist is written, and becomes owned;
 * - an owned file is rewritten when its content differs, so a regenerated file never goes stale;
 * - an owned seed file (`overwritable: false`: `package.json`, `app.routes.ts`, `environment.ts`, …,
 *   the files a consumer is expected to edit) is written only when absent and kept otherwise;
 * - an existing file the manifest does not record (hand-written, or in a directory generated into
 *   for the first time) is never replaced, and does not become owned;
 * - a manifest without the ownership line (the 0.9.x format) also recorded files skipped
 *   because they existed, so an entry of it is owned only when the file on disk already holds what
 *   this run produces, starts with the header the generator writes ({@link carriesGeneratorMarker}),
 *   or is a seed; any other entry is treated as not owned;
 * - a symbolic link at a generated path, or a path reached through a link below the output root, is
 *   never written through; it is treated as not owned, and `overwrite` replaces a link at the path
 *   itself, never its target.
 *
 * `overwrite` replaces every differing file, seed or unowned, and takes ownership of what it writes.
 *
 * An owned file this run no longer produces is deleted, except a seed file or a link, which is
 * dropped from the manifest and left in place ({@link planPrune}). Nothing outside the output tree is
 * written or deleted, so a file a consumer has moved out of the tree to own it is out of reach.
 */

import { mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { carriesGeneratorMarker } from '../generators/file-header.js';
import {
  canonicalManifestPath,
  lstatOrNull,
  planPrune,
  pruneOrphansAndWriteManifest,
  readManifestState,
  throughSymlink,
  writeManifest,
  type PrunePlan,
} from './manifest.js';

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

export interface WriteOptions {
  overwrite: boolean;
  /** Seed paths in canonical form: an orphaned seed is released, never deleted. */
  seedPaths?: ReadonlySet<string>;
}

export interface WriteResult {
  plan: PlannedWrite[];
  /** Owned files a previous run wrote and this run no longer produces, deleted. */
  pruned: number;
  /** Previous entries this run no longer produces, kept on disk and dropped from the manifest. */
  released: string[];
}

/** Decides what happens to each file, reading the disk and the previous manifest only. */
export function planWrites(
  outputPath: string,
  files: readonly FileToWrite[],
  options: { overwrite: boolean },
): PlannedWrite[] {
  const manifest = readManifestState(outputPath);
  return files.map((file): PlannedWrite => {
    const result = (action: WriteAction, owned: boolean): PlannedWrite => ({ path: file.path, action, owned });

    // Nothing is written through a link below the root, --overwrite included: the link may lead
    // anywhere.
    if (throughSymlink(outputPath, file.path)) return result('skip-unowned', false);

    const stat = lstatOrNull(join(outputPath, file.path));
    if (stat === null) return result('create', true);
    if (stat.isSymbolicLink()) return options.overwrite ? result('rewrite', true) : result('skip-unowned', false);
    if (!stat.isFile()) return result('skip-unowned', false);

    const onDisk = readFileSync(join(outputPath, file.path));
    const same = onDisk.equals(Buffer.from(file.content));
    const seed = file.overwritable === false;
    const canonical = canonicalManifestPath(file.path);
    const listed = canonical !== null && manifest.entries.has(canonical);
    // A legacy entry proves ownership only when the file is what this run produces, starts with the
    // header the generator writes, or is a seed (kept either way).
    const owned = listed
      && (!manifest.legacy || same || seed || carriesGeneratorMarker(onDisk.toString('utf-8')));

    if (options.overwrite) return same ? result('unchanged', true) : result('rewrite', true);
    if (!owned) return result('skip-unowned', false);
    if (same) return result('unchanged', true);
    if (seed) return result('keep-seed', true);
    return result('rewrite', true);
  });
}

/** The orphans of a run that would write `files`: what is deleted, and what is only released. */
export function planOrphans(
  outputPath: string,
  files: readonly FileToWrite[],
  seedPaths?: ReadonlySet<string>,
): PrunePlan {
  return planPrune(outputPath, files.map((file) => file.path), seedPaths);
}

/**
 * Writes `files` under `outputPath` by {@link planWrites}, prunes orphans and records the manifest
 * of the paths this run owns.
 *
 * If a write fails, nothing is pruned and the error is rethrown; the manifest records the previous
 * run's entries plus the files written so far, so a file the run wrote stays owned (a legacy
 * manifest is left unchanged).
 */
export function writeGeneratedFiles(
  outputPath: string,
  files: readonly FileToWrite[],
  options: WriteOptions,
): WriteResult {
  const plan = planWrites(outputPath, files, options);
  const previous = readManifestState(outputPath);
  const written: string[] = [];

  try {
    plan.forEach((entry, i) => {
      if (entry.action !== 'create' && entry.action !== 'rewrite') return;
      const full = join(outputPath, entry.path);
      // A link at the path is replaced, never written through (planWrites rewrites one only under
      // --overwrite).
      if (lstatOrNull(full)?.isSymbolicLink()) unlinkSync(full);
      mkdirSync(dirname(full), { recursive: true });
      writeFileSync(full, files[i].content);
      written.push(entry.path);
    });
  } catch (error) {
    // A legacy manifest is left as it is: rewriting it in the ownership format would turn its
    // unproven entries into owned ones.
    if (!previous.legacy) writeManifest(outputPath, [...previous.entries, ...written]);
    throw error;
  }

  const { release: released } = planOrphans(outputPath, files, options.seedPaths);
  const pruned = pruneOrphansAndWriteManifest(
    outputPath,
    files.map((file) => file.path),
    {
      ownedPaths: plan.filter((entry) => entry.owned).map((entry) => entry.path),
      seedPaths: options.seedPaths,
    },
  );
  return { plan, pruned, released };
}
