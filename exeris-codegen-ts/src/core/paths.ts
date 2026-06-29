import { posix } from 'node:path';

/**
 * Join segments into a POSIX-separated relative path for an emitted OutputFile.path.
 * Emitted paths must be deterministic and platform-independent ('/' on every OS) —
 * unlike node:path's join, which uses the OS separator ('\\' on Windows). Use this for
 * every emitted output path; keep node:path.join for real filesystem operations.
 */
export function outPath(...segments: string[]): string {
  return posix.join(...segments);
}
