/**
 * The metadata the sample apps are generated from, shared by `gen-sample-app.mjs` (which writes
 * the apps the CI `ng build` compiles) and the unit specs that scan the same output, so both look
 * at one fixture.
 *
 * The schemas and default config are passed in rather than imported, because the script loads
 * them from `dist/` and the specs from `src/`.
 *
 * @param {{ DomainMetadataSchema: any, ViewMetadataSchema: any, DEFAULT_CONFIG: any }} api
 * @param {{ generateTests?: boolean }} [options]
 */
export function sampleInputs({ DomainMetadataSchema, ViewMetadataSchema, DEFAULT_CONFIG }, { generateTests = true } = {}) {
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
      // The foreign key renders as a routerLink to the target's detail page in the list cell and
      // the detail row. The target is qualified, as the processor's fallback can record it, so the
      // build also covers the simple-name resolution.
      relationships: [{ name: 'productId', targetEntity: 'com.shop.Product', type: 'MANY_TO_ONE' }],
      // Domain events drive the per-entity handler AND the shared event bus. Without one in the
      // fixture, neither half of the event generator is ever built.
      events: [
        { name: 'OrderPlaced', payloadFields: ['id', 'total'] },
        { name: 'OrderCancelled', payloadFields: ['id'], sensitiveFields: ['total'] },
      ],
      actions: [
        { name: 'cancel', methodName: 'cancel' },
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
    // It is also the second entity with both stream routes: its live view has no event to listen
    // for, and two action stream files must share one StreamFrame rather than each declare their own.
    d({
      entityName: 'Product',
      fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
      realTimeApi: true,
      actions: [{ name: 'watchStock', methodName: 'watchStock', streaming: true }],
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
    // Named for the overrides, not for the shop: the only fixture entity declaring a systemFields
    // block at all, which is why four of that block's ten keys could be spelled wrong on the TS
    // side for two trains without any gate noticing.
    //
    // It declares `id` AND `primaryKeyField: 'invoiceNo'` on purpose. That pins the real contract
    // in both directions: the soft-delete trio IS honoured — KernelFlywayGenerator.sysCol maps
    // deleted/deletedAt/deletedBy, so those columns are server-owned and must leave the create
    // DTO — while `primaryKeyField` is honoured by NOTHING (the PK column, the repository's
    // " WHERE id = ?" and every by-id handler are all the literal `id`). An emitted artefact that
    // starts requesting `invoiceNo` fails here, which is the regression this entity exists to
    // catch.
    d({
      entityName: 'Invoice',
      softDelete: true,
      fields: [
        { name: 'id', type: 'java.util.UUID' },
        { name: 'invoiceNo', type: 'String' },
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
  // generated with tests on. `generateTests: false` generates the default (opt-out) shape instead,
  // which is what proves the flag leaves output untouched when nobody asks for tests.
  const config = { ...DEFAULT_CONFIG, generateTests };

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

  return { domains, enums, peers, config, views };
}
