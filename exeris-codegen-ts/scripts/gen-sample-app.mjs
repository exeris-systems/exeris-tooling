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
      // dataType exercises the detail view's currency branch, and the audit stamps exercise
      // its DatePipe branch — both are emitted per-entity, so a fixture without them builds
      // only half of what the generator can produce.
      { name: 'total', type: 'java.math.BigDecimal', dataType: 'currency' },
      { name: 'createdAt', type: 'java.time.Instant' },
      { name: 'updatedAt', type: 'java.time.Instant' },
      { name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus', required: true },
      { name: 'productId', type: 'java.util.UUID' },
      // T20d: a *primitive* boolean. The sample carried no boolean of either kind, which
      // is why a text-input-and-'' -seeded checkbox field type-checked here for two trains.
      // The wrapper was always handled; the primitive is the one that fell through.
      { name: 'expedited', type: 'boolean' },
    ],
    // Domain events drive the per-entity handler AND the shared event bus. Without one in the
    // fixture, neither half of the event generator is ever built.
    events: [
      { name: 'OrderPlaced', payloadFields: ['id', 'total'] },
      { name: 'OrderCancelled', payloadFields: ['id'], sensitiveFields: ['total'] },
    ],
    actions: [
      { name: 'cancel', methodName: 'cancel' },
      { name: 'setStatus', methodName: 'setStatus', params: [{ name: 'status', type: 'com.shop.OrderStatus' }] },
    ],
    // The saga state machine is emitted only for an entity that declares one, so without this
    // the `generateSagas` flag has nothing to build and the FE gate never compiles saga-gen's
    // output. Two of the three steps carry a compensation and one does not, which is the only
    // branch the emitted step table actually has. (`order` is set because the SDK's @SagaStep
    // requires it, not because anything reads it — no generator on either side sorts by it; see
    // ROADMAP.)
    sagaMetadata: {
      name: 'OrderFulfilment',
      steps: [
        { name: 'reserveStock', action: 'reserve', compensatingAction: 'releaseStock', order: 0 },
        { name: 'chargeCard', action: 'charge', compensatingAction: 'refundCard', order: 1 },
        { name: 'notifyCustomer', action: 'notify', order: 2 },
      ],
      compensationStrategy: 'ALL_OR_NOTHING',
      compensationOrder: 'REVERSE',
    },
  }),
  // A SECOND saga, so the barrel is built with two machines. Each saga file declares its own
  // SagaState/SagaStep/SagaStatusSnapshot, so a barrel that starred both would make every one of
  // those names ambiguous. One saga in the fixture could never show that.
  d({
    entityName: 'Product',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
    sagaMetadata: {
      name: 'ProductRestock',
      steps: [
        { name: 'requestQuote', order: 0 },
        { name: 'placePurchaseOrder', compensatingAction: 'cancelPurchaseOrder', order: 1 },
      ],
    },
  }),
  // Named for the collision, not for the shop: `Component` is what an emitted module already
  // imports from '@angular/core', so before T40 this entity's form and list components imported
  // the identifier twice and `ng build` failed here. It stays in the fixture because a unit test
  // asserting on emitted strings cannot prove that the emitted app compiles.
  d({ entityName: 'Component', fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }] }),
  // Named for the plural, not for the shop: an entity already ending in 's' routes to
  // '/address', while detail-gen used to navigate to '/addresss' after a delete — a URL the
  // route table never declares. No fixture entity ended in 's', which is why nothing caught it.
  d({ entityName: 'Address', fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'city', type: 'String' }] }),
  // Named for the rename, not for the shop: the only fixture entity whose primary key is not
  // `id`, and the only one declaring a systemFields block at all. Every emitted Angular artefact
  // interpolates that key — the store's ~10 identity comparisons, the list's @for track and
  // routerLinks, the form's update dispatch — so reading it from the wrong metadata key emitted
  // `e.id` against a DTO that declares no `id`. Unit tests over emitted strings passed anyway,
  // because their fixtures were written from the same wrong key; only a build catches it.
  d({
    entityName: 'Invoice',
    softDelete: true,
    fields: [
      { name: 'invoiceNo', type: 'java.util.UUID' },
      { name: 'amount', type: 'java.math.BigDecimal', dataType: 'currency' },
      { name: 'archived', type: 'boolean' },
      { name: 'archivedAt', type: 'java.time.Instant' },
      { name: 'archivedBy', type: 'String' },
    ],
    systemFields: {
      primaryKeyField: 'invoiceNo',
      softDeleteField: 'archived',
      softDeleteTimestampField: 'archivedAt',
      softDeletedByField: 'archivedBy',
    },
  }),
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

// T2 (ADR-058): the gate must RUN the emitted specs, not merely type-check them, so the sample is
// generated with tests on. EXERIS_SAMPLE_NO_TESTS generates the default (opt-out) shape instead,
// which is what proves the flag leaves output untouched when nobody asks for tests.
const config = { ...DEFAULT_CONFIG, generateTests: !process.env.EXERIS_SAMPLE_NO_TESTS };
const files = buildGeneratedFiles(domains, enums, config, [], peers);

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
