/**
 * Finding and parsing the processor's `exeris-metadata/*.json` corpus.
 *
 * Lifted out of the CLI when the peer-types slice (T42, ADR-048) needed to read a
 * *peer's* metadata. ADR-048 §1 decides there is exactly one input model — a published
 * contract artifact, with peers-in-one-build as its degenerate same-build case — so the
 * peer path reads its metadata through this function rather than through a second
 * parallel one. That is the difference between the slice and the shortcut ROADMAP's T42
 * originally proposed: not "a second metadata directory", the same directory shape read
 * by the same loader.
 *
 * @author Exeris Team
 * @since 0.8.0
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';
import {
  parseDomainMetadata,
  parseExerisMetadata,
  parseViewJson,
  type DomainMetadata,
  type ViewMetadata,
} from './domain-model.js';
import type { EnumMetadataForGen } from '../generators/api/enum-module-gen.js';

/** The three parallel JSON families the processor writes into one directory. */
export interface MetadataFamilies {
  domains: DomainMetadata[];
  enums: EnumMetadataForGen[];
  views: ViewMetadata[];
}

/** Called once per file as it is read, for the CLI's `--verbose` chatter. */
export type OnMetadataFile = (family: 'domain' | 'enum' | 'view', file: string) => void;

/**
 * Every `*.json` under `inputPath`, recursively. A file path is returned as-is when it
 * is itself a `.json`; a non-JSON file and a missing path both yield none.
 *
 * Order is `readdirSync` order — the order the local generation path has always used.
 * Callers that need a stable order across machines (a *published* artifact is unpacked
 * by different tools onto different filesystems) sort the result themselves.
 */
export function findMetadataFiles(inputPath: string): string[] {
  if (!existsSync(inputPath)) {
    return [];
  }

  const files: string[] = [];

  const stat = statSync(inputPath);
  if (stat.isFile()) {
    return inputPath.endsWith('.json') ? [inputPath] : [];
  }

  for (const entry of readdirSync(inputPath, { withFileTypes: true })) {
    const fullPath = join(inputPath, entry.name);
    if (entry.isFile() && entry.name.endsWith('.json')) {
      files.push(fullPath);
    } else if (entry.isDirectory()) {
      files.push(...findMetadataFiles(fullPath));
    }
  }

  return files;
}

/**
 * Splits the corpus by basename prefix and parses each family: `enum_*` are enum
 * modules, `view_*` the presentation IR (RFC-2026-06-28), and everything else a domain —
 * either a single entity (`entityName`) or a multi-domain wrapper (`domains: []`). A JSON
 * file that is neither is ignored, which is what lets a directory hold a peer's
 * `cap-manifest.json` next to its entities.
 */
export function loadMetadataFamilies(files: string[], onFile?: OnMetadataFile): MetadataFamilies {
  const domains: DomainMetadata[] = [];
  const enums: EnumMetadataForGen[] = [];
  const views: ViewMetadata[] = [];

  for (const file of files) {
    const name = basename(file);
    const content = readFileSync(file, 'utf-8');

    if (name.startsWith('enum_')) {
      onFile?.('enum', file);
      enums.push(JSON.parse(content) as EnumMetadataForGen);
      continue;
    }
    if (name.startsWith('view_')) {
      onFile?.('view', file);
      views.push(parseViewJson(JSON.parse(content)));
      continue;
    }

    onFile?.('domain', file);
    const json = JSON.parse(content) as unknown;
    if (typeof json === 'object' && json !== null) {
      const obj = json as Record<string, unknown>;
      if (Array.isArray(obj.domains)) {
        domains.push(...parseExerisMetadata(json).domains);
      } else if (typeof obj.entityName === 'string') {
        domains.push(parseDomainMetadata(json));
      }
    }
  }

  return { domains, enums, views };
}
