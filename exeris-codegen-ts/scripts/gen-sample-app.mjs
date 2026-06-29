/**
 * Generate a representative sample Angular app from fixture metadata, for the full
 * FE build gate (CI `ng build`). Unlike `verify-generated-frontend.mjs` (the fast,
 * Angular-free data-layer `tsc` check), this writes the COMPLETE app so CI can
 * `npm install` + `ng build` it — catching component/service/template breakage
 * (the layer that needs `@angular/*`).
 *
 * Usage: node scripts/gen-sample-app.mjs <output-dir>
 *
 * Preserves an existing node_modules (only rewrites src/ + config files) so local
 * re-runs don't force a reinstall.
 */

import { mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, '..', 'dist');

const out = resolve(process.argv[2] ?? '.fe-sample');

// pathToFileURL: a bare Windows path (D:\…) is an unsupported ESM import scheme.
const { buildGeneratedFiles } = await import(pathToFileURL(join(dist, 'orchestrator.js')).href);
const { DomainMetadataSchema } = await import(pathToFileURL(join(dist, 'models/domain-model.js')).href);
const { DEFAULT_CONFIG } = await import(pathToFileURL(join(dist, 'config.js')).href);

const d = (o) => DomainMetadataSchema.parse({ packageName: 'com.shop', ...o });

// Exercises the full surface: an enum-typed field (types/schemas/form select),
// @Action endpoints incl. one with an enum param (service action method imports),
// a second plain entity, and a relationship-ish UUID FK.
const domains = [
  d({
    entityName: 'Order',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'total', type: 'java.math.BigDecimal' },
      { name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus' },
      { name: 'productId', type: 'java.util.UUID' },
    ],
    actions: [
      { name: 'cancel', methodName: 'cancel' },
      { name: 'setStatus', methodName: 'setStatus', params: [{ name: 'status', type: 'com.shop.OrderStatus' }] },
    ],
  }),
  d({ entityName: 'Product', fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }] }),
];
const enums = [{
  name: 'OrderStatus',
  qualifiedName: 'com.shop.OrderStatus',
  packageName: 'com.shop',
  values: [
    { name: 'NEW', displayName: 'New', ordinal: 0 },
    { name: 'PAID', displayName: 'Paid', ordinal: 1 },
    { name: 'CANCELLED', displayName: 'Cancelled', ordinal: 2 },
  ],
}];

const files = buildGeneratedFiles(domains, enums, DEFAULT_CONFIG);

// Rewrite src/ (preserve node_modules); overwrite root config files in place.
rmSync(join(out, 'src'), { recursive: true, force: true });
for (const f of files) {
  const full = join(out, f.path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, f.content);
}
console.log(`gen-sample-app — wrote ${files.length} files to ${out}`);

// The emitted package.json pins `@exeris/ui-kit@^0.1.0` — the published coordinate
// a real generated app installs from a registry. The package is not on the public
// npm registry yet (it lives in exeris-sdk/exeris-sdk-ui-kit), so for this throwaway
// CI/local sample we repoint that one dependency at a local checkout via EXERIS_UI_KIT_PATH
// so `npm install` resolves it. The real generator output is untouched — this only
// rewrites the sample. Drop this once @exeris/ui-kit publishes to a registry.
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
