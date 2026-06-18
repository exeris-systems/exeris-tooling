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

import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, '..', 'dist');

const out = resolve(process.argv[2] ?? '.fe-sample');

const { buildGeneratedFiles } = await import(join(dist, 'orchestrator.js'));
const { DomainMetadataSchema } = await import(join(dist, 'models/domain-model.js'));
const { DEFAULT_CONFIG } = await import(join(dist, 'config.js'));

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
