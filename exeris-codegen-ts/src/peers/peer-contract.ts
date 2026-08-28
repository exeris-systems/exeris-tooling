/**
 * A peer's contract artifact — the mesh's one input shape (ADR-048 §1).
 *
 * A peer contract is **not** "a second metadata directory". It is a published artifact:
 * the peer's `cap-manifest.json` together with the full `DomainMetadata` of the entities
 * it provides. Peers in one build are the *degenerate same-build case* — the same files
 * on a local path — never a second input model, which is why the metadata here is read by
 * the same loader the local path uses.
 *
 * The manifest is what makes a directory a contract rather than a pile of JSON, and it is
 * required: it carries the `schemaVersion` floor this module enforces, and it is the input
 * the client + registry slice (T12 / T17) resolves `@Provides` against. Requiring it now,
 * while nothing reads its body, is deliberate — accepting a manifest-less directory would
 * ship exactly the input model ADR-048 rejects, and taking it back later would break every
 * consumer that had adopted it.
 *
 * @author Exeris Team
 * @since 0.8.0
 */

import { existsSync, readFileSync, statSync } from 'node:fs';
import { basename, join } from 'node:path';
import { findMetadataFiles, loadMetadataFamilies } from '../models/metadata-files.js';
import type { DomainMetadata } from '../models/domain-model.js';
import type { EnumMetadataForGen } from '../generators/api/enum-module-gen.js';

/** The manifest that makes a directory a contract artifact. */
export const CAP_MANIFEST_NAME = 'cap-manifest.json';

/**
 * The lowest `cap-manifest.json` schema this pipeline reads (ADR-048 §1).
 *
 * v2 is the version that carries the ADR-024 `CompositionStamp`; a v1 manifest is
 * stampless, so there is nothing to check a peer's composition against. A v1 peer is
 * rejected with an actionable error rather than accepted in a degraded mode — a peer
 * contract whose trust fields cannot be verified is not a weaker contract, it is an
 * unverified one, and generating types from it would say otherwise.
 */
export const MIN_MANIFEST_SCHEMA_VERSION = 2;

/**
 * A peer the consumer has declared: the name it will be known by in *this* app, and the
 * path to its contract artifact.
 *
 * The name is the consumer's (ADR-048 §2). Nothing in the emitted artefacts carries an
 * application identity to read one from — `CapabilityModuleDescriptor` names a *module*,
 * `CompositionStamp` carries a verdict, a version and a content binding — and the name
 * lands in this app's own import paths, where it has to stay stable across whatever the
 * producer later renames itself to.
 */
export interface PeerContractRef {
  name: string;
  path: string;
}

/** A loaded peer contract: the entities and enums this app may generate types from. */
export interface PeerContract {
  name: string;
  domains: DomainMetadata[];
  enums: EnumMetadataForGen[];
}

/**
 * A peer name is a path segment in the emitted tree and a segment of every import that
 * reaches it, so it is constrained rather than free text: an ASCII letter, then letters,
 * digits and single hyphens. Rejecting the rest here keeps a consumer-supplied string out
 * of `join(...)` and out of emitted module specifiers.
 */
const PEER_NAME = /^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$/;

/** Raised for every malformed reference or untrustworthy artifact; the message names the peer. */
export class PeerContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PeerContractError';
  }
}

/**
 * Parses one `--peer <name>=<path>` reference. Splits on the FIRST `=` so a Windows path
 * or a query-ish suffix cannot swallow the separator.
 */
export function parsePeerRef(spec: string): PeerContractRef {
  const eq = spec.indexOf('=');
  if (eq <= 0) {
    throw new PeerContractError(
      `peer '${spec}' is not of the form <name>=<path> — the name is declared by you, the ` +
        `consumer, and becomes the directory and import segment the peer's types are reached by`,
    );
  }

  const name = spec.slice(0, eq).trim();
  const path = spec.slice(eq + 1).trim();

  if (!PEER_NAME.test(name)) {
    throw new PeerContractError(
      `peer name '${name}' is not usable: it becomes a directory name and part of every import ` +
        `path that reaches the peer's types, so it must start with a letter and contain only ` +
        `letters, digits and single hyphens`,
    );
  }
  if (path.length === 0) {
    throw new PeerContractError(`peer '${name}' has no path — expected <name>=<path>`);
  }

  return { name, path };
}

/**
 * Reads a peer's contract artifact from disk.
 *
 * Entities are sorted by name. The local generation path uses `readdirSync` order, which is
 * stable for one tree on one machine; a *published* artifact is unpacked by different tools
 * onto different filesystems, so peer order is made explicit rather than inherited.
 *
 * @throws PeerContractError when the directory, the manifest, or the manifest's
 *         `schemaVersion` does not hold up — always naming the peer, since a consumer with
 *         several peers otherwise has to guess which one the build is complaining about.
 */
export function loadPeerContract(ref: PeerContractRef): PeerContract {
  if (!existsSync(ref.path) || !statSync(ref.path).isDirectory()) {
    throw new PeerContractError(
      `peer '${ref.name}': ${ref.path} is not a directory — expected a contract artifact ` +
        `(its ${CAP_MANIFEST_NAME} plus the metadata of the entities it provides)`,
    );
  }

  const manifestPath = join(ref.path, CAP_MANIFEST_NAME);
  if (!existsSync(manifestPath)) {
    throw new PeerContractError(
      `peer '${ref.name}': no ${CAP_MANIFEST_NAME} in ${ref.path} — a peer's contract is its ` +
        `${CAP_MANIFEST_NAME} plus the metadata of the entities it provides; a directory of ` +
        `metadata alone is not a contract, and its trust fields cannot be checked`,
    );
  }

  const schemaVersion = readManifestSchemaVersion(ref.name, manifestPath);
  if (schemaVersion < MIN_MANIFEST_SCHEMA_VERSION) {
    throw new PeerContractError(
      `peer '${ref.name}': ${CAP_MANIFEST_NAME} declares schemaVersion ${schemaVersion}, below ` +
        `the floor of ${MIN_MANIFEST_SCHEMA_VERSION} — v1 manifests carry no composition stamp, ` +
        `so there is nothing to verify the peer's contract against. Rebuild the peer with a ` +
        `current exeris-tooling`,
    );
  }

  // The manifest sits in the same directory as the metadata; it is not one of the families.
  const files = findMetadataFiles(ref.path)
    .filter((f) => basename(f) !== CAP_MANIFEST_NAME)
    .sort();
  const { domains, enums } = loadMetadataFamilies(files);

  return {
    name: ref.name,
    domains: [...domains].sort((a, b) => a.entityName.localeCompare(b.entityName, 'en')),
    enums: [...enums].sort((a, b) => a.name.localeCompare(b.name, 'en')),
  };
}

/** Loads every declared peer, sorted by declared name so emission order is deterministic. */
export function loadPeerContracts(refs: PeerContractRef[]): PeerContract[] {
  return [...refs]
    .sort((a, b) => a.name.localeCompare(b.name, 'en'))
    .map(loadPeerContract);
}

function readManifestSchemaVersion(peerName: string, manifestPath: string): number {
  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(manifestPath, 'utf-8'));
  } catch (error) {
    throw new PeerContractError(
      `peer '${peerName}': ${CAP_MANIFEST_NAME} is not readable JSON — ` +
        (error instanceof Error ? error.message : String(error)),
    );
  }

  const version = (parsed as Record<string, unknown> | null)?.schemaVersion;
  if (typeof version !== 'number' || !Number.isInteger(version)) {
    throw new PeerContractError(
      `peer '${peerName}': ${CAP_MANIFEST_NAME} declares no integer schemaVersion — it is not a ` +
        `capability manifest this pipeline wrote`,
    );
  }
  return version;
}
