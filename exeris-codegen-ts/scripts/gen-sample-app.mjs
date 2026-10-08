/**
 * Generate a representative sample Angular app from fixture metadata, for the full
 * FE build gate (CI `ng build`). Unlike `verify-generated-frontend.mjs` (the fast,
 * Angular-free data-layer `tsc` check), this writes the COMPLETE app so CI can
 * `npm install` + `ng build` it — catching component/service/template breakage
 * (the layer that needs `@angular/*`).
 *
 * Usage: node scripts/gen-sample-app.mjs <output-dir> [--view-only] [--ssg]
 *
 * `--view-only` generates the backend-less shape instead: no entity, only `@View` pages with
 * authored (STATIC / NONE) content, which is the scaffold without HTTP wiring. It is built
 * separately because nothing in the full sample can show that the scaffold compiles without it.
 * It also carries a CUSTOM block mapped through `customBlocks` to a hand-written component the
 * sample writes beside the generated files.
 *
 * `--ssg` generates the view-only shape with `render: 'ssg'`, plus a parameterised page, so the
 * build prerenders the param-less pages to HTML and leaves the parameterised one to the browser.
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
const ssg = args.includes('--ssg');
const viewOnly = ssg || args.includes('--view-only');
const out = resolve(args.find((a) => !a.startsWith('--')) ?? '.fe-sample');

// pathToFileURL: a bare Windows path (D:\…) is an unsupported ESM import scheme.
const { buildGeneratedFiles } = await import(pathToFileURL(join(dist, 'orchestrator.js')).href);
const { DomainMetadataSchema, ViewMetadataSchema } = await import(pathToFileURL(join(dist, 'models/domain-model.js')).href);
const { DEFAULT_CONFIG } = await import(pathToFileURL(join(dist, 'config.js')).href);

const d = (o) => DomainMetadataSchema.parse({ packageName: 'com.shop', ...o });

// Exercises the full surface: an enum-typed field (types/schemas/form select),
// @Action endpoints incl. one with an enum param (service action method imports),
// a second plain entity, and a MANY_TO_ONE UUID foreign key.
const domains = [
  d({
    entityName: 'Order',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      // dataType exercises the detail view's currency branch, and the audit stamps exercise
      // its DatePipe branch — both are emitted per-entity, so a fixture without them builds
      // only half of what the generator can produce.
      // The list route's sort and equality filters (ADR-096): every filter kind is on this entity — a
      // boolean, an enum, a string, a number, a BigDecimal, a LocalDate and the productId foreign key —
      // and a sortable column, so the list's server-side sort, filter and paging state all compile.
      { name: 'total', type: 'java.math.BigDecimal', dataType: 'currency', sortable: true, filterable: true },
      { name: 'createdAt', type: 'java.time.Instant' },
      { name: 'updatedAt', type: 'java.time.Instant' },
      // Filterable, so the store's and the service's filter both name the enum and must import it.
      { name: 'status', type: 'com.shop.OrderStatus', enumType: 'com.shop.OrderStatus', required: true, filterable: true, sortable: true },
      // Bounded boxed number: its Zod schema and its Signal Forms validators both carry min and max.
      { name: 'quantity', type: 'java.lang.Integer', required: true, min: 1, max: 99, sortable: true, filterable: true },
      // An offset date-time and two collections, typed as the processor writes them. A collection
      // renders as text in the list and detail and has no form control.
      { name: 'placedAt', type: 'java.time.OffsetDateTime' },
      // The smaller numeric types: filterable as numbers and sortable, as the list route serves them.
      { name: 'priority', type: 'java.lang.Short', sortable: true, filterable: true },
      { name: 'rank', type: 'byte', sortable: true, filterable: true },
      { name: 'weight', type: 'java.lang.Float', sortable: true, filterable: true },
      // Sortable and filterable by its flags, neither on the list route: a List is a JSON column.
      { name: 'labels', type: 'java.util.List<java.lang.String>', sortable: true, filterable: true },
      { name: 'attributes', type: 'java.util.Map<java.lang.String,java.lang.String>' },
      { name: 'productId', type: 'java.util.UUID' },
      // T20d: a *primitive* boolean. The sample carried no boolean of either kind, which
      // is why a text-input-and-'' -seeded checkbox field type-checked here for two trains.
      // The wrapper was always handled; the primitive is the one that fell through.
      { name: 'expedited', type: 'boolean', filterable: true },
      // A calendar date: the detail view renders it through DatePipe with mediumDate, an arm no
      // other fixture field reaches.
      { name: 'deliveryDate', type: 'java.time.LocalDate', sortable: true, filterable: true },
      // An instant sorts on the list route and is no filter: equality on an instant matches nothing typed.
      { name: 'shippedAt', type: 'java.time.Instant', sortable: true, filterable: true },
      // The form's type rules, one control kind each: a primitive number is a number input
      // holding number | null; a type naming the emitted enum, with no explicit enumType, is a
      // select; a zone-free date-time is a datetime-local input; a zoned one and an integer
      // string are text inputs.
      { name: 'units', type: 'long', min: 1 },
      { name: 'previousStatus', type: 'com.shop.OrderStatus' },
      { name: 'pickupAt', type: 'java.time.LocalDateTime' },
      { name: 'promisedAt', type: 'java.time.ZonedDateTime' },
      { name: 'loyaltyPoints', type: 'java.math.BigInteger' },
      // inUpdate = false: the edit form disables the control through a Signal Forms rule bound to
      // edit mode, and the field is required, so the rule sits beside a validator.
      { name: 'orderNumber', type: 'String', required: true, maxLength: 20, inUpdate: false, sortable: true, filterable: true },
      // inCreate = false: the control renders in edit mode only, a Signal Forms rule disables it
      // while the form creates, and the create payload leaves it out.
      { name: 'trackingCode', type: 'String', required: true, inCreate: false },
      // A computed field: the form shows the loaded entity's value in a read-only input, outside
      // the form model.
      { name: 'lineTotal', type: 'java.math.BigDecimal', computed: true, computedFrom: ['quantity', 'total'] },
    ],
    // The foreign key renders as a routerLink to the target's detail page in the list cell and
    // the detail row, and the form picks it from Product's records labelled by their name. The
    // target is qualified, as the processor's fallback can record it, so the build also covers the
    // simple-name resolution.
    relationships: [{ name: 'productId', targetEntity: 'com.shop.Product', type: 'MANY_TO_ONE', displayField: 'name' }],
    // Domain events drive the per-entity handler AND the shared event bus. Without one in the
    // fixture, neither half of the event generator is ever built.
    events: [
      { name: 'OrderPlaced', topic: 'shop.orders', aggregateType: 'Order', payloadFields: ['id', 'total'] },
      { name: 'OrderCancelled', payloadFields: ['id'], sensitiveFields: ['total'] },
    ],
    actions: [
      { name: 'cancel', methodName: 'cancel', resultType: 'void' },
      { name: 'setStatus', methodName: 'setStatus', params: [{ name: 'status', type: 'com.shop.OrderStatus' }] },
      // A streaming action is served as a stream only: it gets an action stream client and no
      // service method. The enum param pins that the service then imports nothing for it.
      { name: 'trackDelivery', methodName: 'trackDelivery', streaming: true, streamEventType: 'DeliveryProgress',
        params: [{ name: 'status', type: 'com.shop.OrderStatus' }] },
    ],
    // The live-view stream client listens for each declared event by name.
    realTimeApi: true,
    // The saga state machine is emitted only for an entity that declares one, so without this
    // the `generateSagas` flag has nothing to build and the FE gate never compiles saga-gen's
    // output. Two of the three steps carry a compensation and one does not, which is the only
    // branch the emitted step table actually has. (`order` is set because the SDK's @SagaStep
    // requires it, not because anything reads it — no generator on either side sorts by it; see
    // ROADMAP.)
    sagaMetadata: {
      name: 'OrderFulfilment',
      steps: [
        { name: 'reserveStock', service: 'stock', command: 'reserve', compensation: 'releaseStock', order: 0 },
        { name: 'chargeCard', service: 'billing', command: 'charge', compensation: 'refundCard', order: 1 },
        { name: 'notifyCustomer', service: 'mail', command: 'notify', order: 2 },
      ],
      compensationStrategy: 'ALL_OR_NOTHING',
      compensationOrder: 'REVERSE',
    },
  }),
  // A SECOND saga, so the barrel is built with two machines. Each saga file declares its own
  // SagaState/SagaStep/SagaStatusSnapshot, so a barrel that starred both would make every one of
  // those names ambiguous. One saga in the fixture could never show that.
  // It is also the second entity with both stream routes: its live view has no event to listen
  // for, and two action stream files must share one StreamFrame rather than each declare their own.
  d({
    entityName: 'Product',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
    // The other side of Order's foreign key: the detail view's related-records panel lists this
    // product's orders through the order list route's productId filter, labelled by orderNumber,
    // beside the link to the whole order list.
    relationships: [{ name: 'orders', targetEntity: 'com.shop.Order', type: 'ONE_TO_MANY', mappedBy: 'productId', displayField: 'orderNumber' }],
    realTimeApi: true,
    actions: [{ name: 'watchStock', methodName: 'watchStock', streaming: true }],
    sagaMetadata: {
      name: 'ProductRestock',
      steps: [
        { name: 'requestQuote', order: 0 },
        { name: 'placePurchaseOrder', compensation: 'cancelPurchaseOrder', order: 1 },
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
  // Its required foreign key to `Component` is picked from that entity's records: the form imports
  // the target's service beside the framework's `Component`, and a required picker offers no empty
  // choice.
  d({
    entityName: 'Address',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'city', type: 'String' },
      { name: 'componentId', type: 'java.util.UUID', required: true },
    ],
    relationships: [{ name: 'componentId', targetEntity: 'Component', type: 'MANY_TO_ONE', displayField: 'name' }],
  }),
  // Named for the overrides, not for the shop: the fixture entity whose systemFields block renames
  // the soft-delete trio and the primary key.
  //
  // Its key is the UUID `invoiceNo` and it has no field `id` (ADR-104), so every site that reads a
  // row's key compiles against `invoiceNo`: the list's track and links, the detail's system panel,
  // the form's load and self-exclusion, the store's lookups and the schema's omissions. The route
  // variable stays `:id`. Its two foreign keys are picked from targets with different keys, itself
  // and the `id`-keyed Account, so the form's picker helper takes each target's key per call.
  d({
    entityName: 'Invoice',
    softDelete: true,
    fields: [
      { name: 'invoiceNo', type: 'java.util.UUID' },
      { name: 'amount', type: 'java.math.BigDecimal', dataType: 'currency' },
      { name: 'archived', type: 'boolean' },
      { name: 'archivedAt', type: 'java.time.Instant' },
      { name: 'archivedBy', type: 'String' },
      { name: 'accountId', type: 'java.util.UUID' },
      { name: 'correctsId', type: 'java.util.UUID' },
    ],
    relationships: [
      { name: 'accountId', targetEntity: 'Account', type: 'MANY_TO_ONE', displayField: 'name' },
      { name: 'correctsId', targetEntity: 'Invoice', type: 'MANY_TO_ONE', displayField: 'amount' },
    ],
    systemFields: {
      primaryKeyField: 'invoiceNo',
      softDeleteField: 'archived',
      softDeleteTimestampField: 'archivedAt',
      softDeletedByField: 'archivedBy',
    },
  }),
  // Named for the optimistic lock: the only versioned fixture entity. Its edit form holds the
  // loaded version, sends it with the update and turns a 409 into a reload — code emitted for no
  // unversioned entity, so without this the build never compiles it. The version field is named
  // through systemFields to pin that the key comes from there, not from the literal `version`.
  // It is also the only audited fixture entity, with both stamps renamed and left undeclared: the
  // detail view's system panel then reads them through a narrowing cast, the one panel shape no
  // other entity produces.
  d({
    entityName: 'Ticket',
    versioned: true,
    audited: true,
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'title', type: 'String', required: true },
      { name: 'revision', type: 'java.lang.Long' },
    ],
    systemFields: { versionField: 'revision', createdAtField: 'openedAt', updatedAtField: 'touchedAt' },
  }),
  // Named for the @UI view switches: every other fixture entity gets every page, so the shapes an
  // entity takes with a switch off are compiled only here. Tag has a list and an edit form and
  // nothing else — no detail page, no create route, no search box, no filter control although a
  // field is filterable — and its foreign key to itself renders as text, the target having no
  // detail page. Its form serves the edit route alone and leaves to the list, and picks the key
  // from Tag's own records through its own service, labelled by id: the relationship names no
  // display field.
  d({
    entityName: 'Tag',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'label', type: 'String', searchable: true },
      { name: 'pinned', type: 'Boolean', filterable: true },
      { name: 'parentId', type: 'java.util.UUID' },
    ],
    relationships: [{ name: 'parentId', targetEntity: 'Tag', type: 'MANY_TO_ONE' }],
    uiMetadata: { listView: true, detailView: false, createForm: false, editForm: true, searchable: false, filterable: false },
  }),
  // Receipt has no list page and no edit form: a detail page with no Edit button, reached by id,
  // and a create-only form. Both leave to the app root, which no list of its own backs.
  d({
    entityName: 'Receipt',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'amount', type: 'java.math.BigDecimal', dataType: 'currency' }],
    uiMetadata: { listView: false, detailView: true, createForm: true, editForm: false },
  }),
  // Account is tenant-partitioned with no systemFields block: the server owns its required
  // tenantId, so the entity type carries it while AccountCreate, AccountUpdate and the create
  // schema omit it, and the form, store and service must compile against that split.
  d({
    entityName: 'Account',
    dataScope: 'TENANT',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'tenantId', type: 'java.util.UUID', required: true },
      { name: 'name', type: 'String', required: true },
    ],
    // A streaming action on a tenant-partitioned entity gets the same action stream client as a
    // GLOBAL entity; the live view is not emitted for it.
    actions: [{ name: 'auditTrail', methodName: 'auditTrail', streaming: true }],
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
// `eventBusEndpoint` is what puts the event bus and the entity event handlers into the sample.
const config = {
  ...DEFAULT_CONFIG,
  generateTests: !process.env.EXERIS_SAMPLE_NO_TESTS,
  eventBusEndpoint: '/shipments/events',
};

// The backend-less app: two pages and a section, all authored content. The landing page nests
// blocks and leaves one binding at the SDK's NONE default; the section is routed but gets no nav
// link. No entity, enum or peer, so nothing in it reaches an API.
const views = [
  ViewMetadataSchema.parse({
    name: 'Home',
    title: 'Welcome',
    regions: [
      {
        slot: 'main',
        components: [
          { type: 'HERO', binding: { source: 'STATIC' }, props: 'A front with no backend' },
          {
            type: 'GRID',
            children: [
              { type: 'CARD', binding: { source: 'STATIC' }, props: 'Authored content' },
              { type: 'CARD', props: 'Rendered at build time' },
            ],
          },
          // A CUSTOM block the generator cannot render itself: the page imports StatTileComponent
          // through customBlocks and binds the props JSON as its `props` input. One with props
          // and one without, so both the bound and the unbound element compile.
          { type: 'CUSTOM', customType: 'StatTile', props: '{"label":"Pages","value":3,"tags":["static"]}' },
          { type: 'CUSTOM', customType: 'StatTile' },
          // A LIST whose items are <li>.
          {
            type: 'LIST',
            children: [
              { type: 'CARD', props: 'First item' },
              { type: 'CARD', props: 'Second item' },
            ],
          },
        ],
      },
    ],
  }),
  ViewMetadataSchema.parse({
    name: 'About',
    route: 'about-us',
    regions: [{ components: [{ type: 'RICH_TEXT', binding: { source: 'STATIC' }, props: 'About this site' }] }],
  }),
  ViewMetadataSchema.parse({
    name: 'Footer',
    kind: 'SECTION',
    regions: [{ components: [{ type: 'NAV', binding: { source: 'NONE' }, props: 'Links' }] }],
  }),
];

// The hand-written component behind the StatTile CUSTOM block, which the consumer owns. The
// specifier is relative to the emitted page, src/app/pages/.
const statTile = {
  path: 'src/app/blocks/stat-tile.component.ts',
  content: `import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export interface StatTileProps {
  label?: string;
  value?: number;
  tags?: string[];
}

@Component({
  selector: 'stat-tile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: \`<p class="exeris-stat-tile">{{ props().label }}: {{ props().value }}</p>\`,
})
export class StatTileComponent {
  readonly props = input<StatTileProps>({});
}
`,
};
const viewOnlyConfig = {
  ...DEFAULT_CONFIG,
  customBlocks: { StatTile: { import: '../blocks/stat-tile.component', symbol: 'StatTileComponent' } },
};

// A page per post: its path has a parameter, so the static build has no value to render it with
// and the server routes leave it to the browser.
if (ssg) {
  views.push(ViewMetadataSchema.parse({
    name: 'Post',
    route: 'posts/:slug',
    regions: [{ components: [{ type: 'RICH_TEXT', binding: { source: 'STATIC' }, props: 'A post' }] }],
  }));
}

// It has no entity, so no spec to run: generated with the default (tests off) shape.
const files = viewOnly
  ? [...buildGeneratedFiles([], [], ssg ? { ...viewOnlyConfig, render: 'ssg' } : viewOnlyConfig, views, []), statTile]
  : buildGeneratedFiles(domains, enums, config, [], peers);

// Rewrite src/ (preserve node_modules); overwrite root config files in place.
rmSync(join(out, 'src'), { recursive: true, force: true });
for (const f of files) {
  const full = join(out, f.path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, f.content);
}
console.log(`gen-sample-app — wrote ${files.length} files to ${out}`);

// The emitted package.json pins `@exeris/ui-kit@^0.2.1`, the coordinate on the public npm
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
