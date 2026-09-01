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
  // Named for the collision, not for the shop: `Component` is what an emitted module already
  // imports from '@angular/core', so before T40 this entity's form and list components imported
  // the identifier twice and `ng build` failed here. It stays in the fixture because a unit test
  // asserting on emitted strings cannot prove that the emitted app compiles.
  d({ entityName: 'Component', fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }] }),
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

// Two peers, both declaring an entity named `Order` — which this app also declares. Three
// `Order` types in one generated app is the mesh form of the T40 break: it compiles only
// because each peer owns its namespace and neither is re-exported from the app barrel.
// `billing` additionally declares an enum named `OrderStatus`, the same name the app's own
// enum module binds, so the enum modules are proven separate too.
const peers = [
  {
    name: 'billing',
    domains: [
      d({
        entityName: 'Order',
        packageName: 'com.billing',
        fields: [
          { name: 'id', type: 'java.util.UUID' },
          { name: 'invoiceNo', type: 'String', required: true },
          { name: 'status', type: 'com.billing.OrderStatus', enumType: 'com.billing.OrderStatus' },
        ],
      }),
    ],
    enums: [{
      name: 'OrderStatus',
      qualifiedName: 'com.billing.OrderStatus',
      packageName: 'com.billing',
      values: [
        { name: 'DRAFT', displayName: 'Draft', ordinal: 0 },
        { name: 'SETTLED', displayName: 'Settled', ordinal: 1 },
      ],
    }],
  },
  {
    name: 'shipping',
    domains: [
      d({
        entityName: 'Order',
        packageName: 'com.shipping',
        fields: [
          { name: 'id', type: 'java.util.UUID' },
          { name: 'trackingCode', type: 'String', required: true },
        ],
      }),
      // Named for the same collision T40 records, on the peer side: a peer's entity name is
      // outside this app's control, so a peer may legitimately be called `Component`.
      d({
        entityName: 'Component',
        packageName: 'com.shipping',
        fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'sku', type: 'String' }],
      }),
    ],
    enums: [],
  },
];

const files = buildGeneratedFiles(domains, enums, DEFAULT_CONFIG, [], peers);

// Rewrite src/ (preserve node_modules); overwrite root config files in place.
rmSync(join(out, 'src'), { recursive: true, force: true });
for (const f of files) {
  const full = join(out, f.path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, f.content);
}
console.log(`gen-sample-app — wrote ${files.length} files to ${out}`);

// The emitted package.json pins `@exeris-systems/ui-kit@^0.1.0` — the published
// coordinate (GitHub Packages), which CI installs with a read:packages token.
// OPTIONAL local-dev escape hatch: set EXERIS_UI_KIT_PATH to an exeris-sdk-ui-kit
// checkout to repoint just that one dependency at it (file:), so a dev without a
// GitHub Packages token can still build the sample. Leaving it unset uses the real
// registry. Only the throwaway sample is rewritten — the real generator output keeps
// the `^0.1.0` registry coordinate.
const uiKitPath = process.env.EXERIS_UI_KIT_PATH;
if (uiKitPath) {
  const pkgPath = join(out, 'package.json');
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf-8'));
  if (pkg.dependencies?.['@exeris-systems/ui-kit']) {
    const linked = `file:${resolve(uiKitPath)}`;
    pkg.dependencies['@exeris-systems/ui-kit'] = linked;
    writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n');
    console.log(`gen-sample-app — linked @exeris-systems/ui-kit -> ${linked}`);
  }
}
