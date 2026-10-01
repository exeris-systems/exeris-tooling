/**
 * Generate a representative sample Angular app from fixture metadata, for the full
 * FE build gate (CI `ng build`). Unlike `verify-generated-frontend.mjs` (the fast,
 * Angular-free data-layer `tsc` check), this writes the COMPLETE app so CI can
 * `npm install` + `ng build` it — catching component/service/template breakage
 * (the layer that needs `@angular/*`).
 *
 * Usage: node scripts/gen-sample-app.mjs <output-dir> [--view-only]
 *
 * `--view-only` generates the backend-less shape instead: no entity, only `@View` pages with
 * authored (STATIC / NONE) content, which is the scaffold without HTTP wiring. It is built
 * separately because nothing in the full sample can show that the scaffold compiles without it.
 *
 * Preserves an existing node_modules (only rewrites src/ + config files) so local
 * re-runs don't force a reinstall.
 */

import { mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, '..', 'dist');

const args = process.argv.slice(2);
const viewOnly = args.includes('--view-only');
const out = resolve(args.find((a) => !a.startsWith('--')) ?? '.fe-sample');

// pathToFileURL: a bare Windows path (D:\…) is an unsupported ESM import scheme.
const { buildGeneratedFiles } = await import(pathToFileURL(join(dist, 'orchestrator.js')).href);
const { DomainMetadataSchema, ViewMetadataSchema } = await import(pathToFileURL(join(dist, 'models/domain-model.js')).href);
const { DEFAULT_CONFIG } = await import(pathToFileURL(join(dist, 'config.js')).href);
const { sampleInputs } = await import(pathToFileURL(join(here, 'sample-fixtures.mjs')).href);

const { domains, enums, peers, config, views } = sampleInputs(
  { DomainMetadataSchema, ViewMetadataSchema, DEFAULT_CONFIG },
  { generateTests: !process.env.EXERIS_SAMPLE_NO_TESTS },
);

// It has no entity, so no spec to run: generated with the default (tests off) shape.
const files = viewOnly
  ? buildGeneratedFiles([], [], DEFAULT_CONFIG, views, [])
  : buildGeneratedFiles(domains, enums, config, [], peers);

// Rewrite src/ (preserve node_modules); overwrite root config files in place.
rmSync(join(out, 'src'), { recursive: true, force: true });
for (const f of files) {
  const full = join(out, f.path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, f.content);
}
console.log(`gen-sample-app — wrote ${files.length} files to ${out}`);

// The emitted package.json pins `@exeris/ui-kit@^0.2.0`, the coordinate on the public npm
// registry, which installs without a token. OPTIONAL local escape hatch: set
// EXERIS_UI_KIT_PATH to an exeris-sdk-ui-kit directory or to a tarball `npm pack` made from
// it, and only that one dependency is repointed at it (file:), so the sample builds against a
// kit that is not published. Leaving it unset uses the registry. Only the throwaway sample is
// rewritten; the real generator output keeps the registry coordinate.
const uiKitPath = process.env.EXERIS_UI_KIT_PATH;
if (uiKitPath) {
  const pkgPath = join(out, 'package.json');
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf-8'));
  if (pkg.dependencies?.['@exeris/ui-kit']) {
    const linked = `file:${resolve(uiKitPath)}`;
    pkg.dependencies['@exeris/ui-kit'] = linked;
    writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
    console.log(`gen-sample-app — linked @exeris/ui-kit -> ${linked}`);
  }
}
