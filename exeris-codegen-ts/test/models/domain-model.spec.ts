/**
 * Coverage for src/models/domain-model.ts — every Zod schema plus the
 * two parse helpers. Schemas are validated by round-tripping minimal +
 * fully-populated fixtures and by asserting documented defaults fire
 * when optional fields are omitted. Bad-input cases assert that the
 * underlying Zod machinery throws.
 *
 * The schemas themselves contain very little executable code; coverage
 * comes from exercising every default-fill branch and every parse
 * helper.
 */

import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import {
  FieldMetadataSchema,
  ActionParamMetadataSchema,
  ActionMetadataSchema,
  DomainEventMetadataSchema,
  RelationshipMetadataSchema,
  ProjectionMetadataSchema,
  UIMetadataSchema,
  GraphEdgeMetadataSchema,
  GraphMetadataSchema,
  SagaStepMetadataSchema,
  SagaMetadataSchema,
  SystemFieldsMetadataSchema,
  EventSourcedMetadataSchema,
  InternalApiMetadataSchema,
  DomainMetadataSchema,
  ExerisMetadataSchema,
  parseDomainMetadata,
  parseExerisMetadata,
  effectiveDataScope,
} from '../../src/models/domain-model.js';

describe('FieldMetadataSchema', () => {
  it('fills every documented default when only name+type are supplied', () => {
    const result = FieldMetadataSchema.parse({ name: 'firstName', type: 'String' });

    expect(result.required).toBe(false);
    expect(result.unique).toBe(false);
    expect(result.indexed).toBe(false);
    expect(result.searchable).toBe(false);
    expect(result.sortable).toBe(false);
    expect(result.filterable).toBe(false);
    expect(result.audited).toBe(false);
    expect(result.readOnly).toBe(false);
    expect(result.hidden).toBe(false);
    expect(result.inList).toBe(true);
    expect(result.inDetail).toBe(true);
    expect(result.inCreate).toBe(true);
    expect(result.inUpdate).toBe(true);
    expect(result.computed).toBe(false);
  });

  it('round-trips computed-field arrays (computedFrom + dependencies aliases)', () => {
    const input = {
      name: 'fullName',
      type: 'String',
      computed: true,
      computedFrom: ['firstName', 'lastName'],
      dependencies: ['firstName', 'lastName'],
    };
    expect(FieldMetadataSchema.parse(input)).toMatchObject(input);
  });

  it('rejects when required name field is missing', () => {
    expect(() => FieldMetadataSchema.parse({ type: 'String' })).toThrow(z.ZodError);
  });

  it('carries the optional @Field.dataType presentation hint when present (Wave 1A)', () => {
    expect(FieldMetadataSchema.parse({ name: 'amount', type: 'BigDecimal', dataType: 'currency' }).dataType)
      .toBe('currency');
    // additive + optional — absent dataType is undefined, never forced
    expect(FieldMetadataSchema.parse({ name: 'note', type: 'String' }).dataType).toBeUndefined();
  });
});

describe('ActionParamMetadataSchema', () => {
  it('defaults required=false when omitted', () => {
    const result = ActionParamMetadataSchema.parse({ name: 'limit', type: 'int' });
    expect(result.required).toBe(false);
  });

  it('preserves description + defaultValue when supplied', () => {
    const result = ActionParamMetadataSchema.parse({
      name: 'limit',
      type: 'int',
      required: true,
      description: 'maximum results',
      defaultValue: '50',
    });
    expect(result).toMatchObject({ description: 'maximum results', defaultValue: '50', required: true });
  });
});

describe('ActionMetadataSchema', () => {
  it('defaults params=[], async=false, requiresAuth=true, permissions=[]', () => {
    const result = ActionMetadataSchema.parse({ name: 'approve' });
    expect(result.params).toEqual([]);
    expect(result.async).toBe(false);
    expect(result.requiresAuth).toBe(true);
    expect(result.permissions).toEqual([]);
  });

  it('accepts a populated params + permissions array', () => {
    const result = ActionMetadataSchema.parse({
      name: 'approve',
      params: [{ name: 'reason', type: 'String' }],
      permissions: ['order.approve', 'order.read'],
    });
    expect(result.params).toHaveLength(1);
    expect(result.permissions).toEqual(['order.approve', 'order.read']);
  });
});

describe('DomainEventMetadataSchema', () => {
  it('defaults fields=[] when omitted', () => {
    expect(DomainEventMetadataSchema.parse({ name: 'OrderCreated' }).fields).toEqual([]);
  });

  it('embeds FieldMetadata entries in the fields array', () => {
    const result = DomainEventMetadataSchema.parse({
      name: 'OrderCreated',
      fields: [{ name: 'orderId', type: 'UUID' }],
    });
    expect(result.fields).toHaveLength(1);
    expect(result.fields[0].name).toBe('orderId');
  });
});

describe('RelationshipMetadataSchema', () => {
  it('defaults fetch=LAZY, cascade=NONE, optional=true, lazy=true, orphanRemoval=false', () => {
    const result = RelationshipMetadataSchema.parse({
      name: 'owner',
      targetEntity: 'User',
      type: 'MANY_TO_ONE',
    });
    expect(result.fetch).toBe('LAZY');
    expect(result.cascade).toBe('NONE');
    expect(result.optional).toBe(true);
    expect(result.lazy).toBe(true);
    expect(result.orphanRemoval).toBe(false);
  });

  it('accepts cascade as either a single string or an array of strings', () => {
    const asString = RelationshipMetadataSchema.parse({
      name: 'lines', targetEntity: 'OrderLine', type: 'ONE_TO_MANY', cascade: 'ALL',
    });
    expect(asString.cascade).toBe('ALL');

    const asArray = RelationshipMetadataSchema.parse({
      name: 'lines', targetEntity: 'OrderLine', type: 'ONE_TO_MANY', cascade: ['PERSIST', 'MERGE'],
    });
    expect(asArray.cascade).toEqual(['PERSIST', 'MERGE']);
  });

  it('rejects an unknown type value (only ONE_TO_ONE / ONE_TO_MANY / MANY_TO_ONE / MANY_TO_MANY)', () => {
    expect(() => RelationshipMetadataSchema.parse({
      name: 'x', targetEntity: 'Y', type: 'BOGUS',
    })).toThrow(z.ZodError);
  });

  it('accepts every documented relationship type', () => {
    for (const t of ['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY'] as const) {
      expect(RelationshipMetadataSchema.parse({
        name: 'r', targetEntity: 'X', type: t,
      }).type).toBe(t);
    }
  });
});

describe('ProjectionMetadataSchema', () => {
  it('defaults fields=[]', () => {
    expect(ProjectionMetadataSchema.parse({ name: 'OrderSummary' }).fields).toEqual([]);
  });
});

describe('UIMetadataSchema', () => {
  it('defaults listColumns / searchFields / filterFields to empty arrays', () => {
    const result = UIMetadataSchema.parse({});
    expect(result.listColumns).toEqual([]);
    expect(result.searchFields).toEqual([]);
    expect(result.filterFields).toEqual([]);
  });

  it('preserves icon + color + formLayout when supplied', () => {
    const result = UIMetadataSchema.parse({
      icon: 'shopping-cart', color: '#ff0000', formLayout: 'grid',
    });
    expect(result).toMatchObject({ icon: 'shopping-cart', color: '#ff0000', formLayout: 'grid' });
  });
});

describe('GraphEdgeMetadataSchema + GraphMetadataSchema', () => {
  // Fixtures below are the shapes eu.exeris.sdk.sourcemodel.ast.GraphMetadata and
  // GraphEdgeMetadata actually serialise to. Java is the producer and there is no
  // cross-build fixture, so these assertions are the only place the mirror is checked.

  it('keeps every component of a fully-populated GraphMetadata document', () => {
    const result = GraphMetadataSchema.parse({
      label: 'Order',
      properties: [{ name: 'total', type: 'BigDecimal', indexed: true }],
      edges: [{ name: 'placedBy', targetLabel: 'User', relationType: 'PLACED_BY' }],
      queries: [{ name: 'recent', cypher: 'MATCH (o:Order) RETURN o', description: 'Recent orders' }],
    });

    expect(result.label).toBe('Order');
    expect(result.properties).toEqual([{ name: 'total', type: 'BigDecimal', indexed: true }]);
    expect(result.edges).toEqual([
      { name: 'placedBy', targetLabel: 'User', relationType: 'PLACED_BY' },
    ]);
    expect(result.queries[0].cypher).toBe('MATCH (o:Order) RETURN o');
  });

  it('accepts an edge whose optional components are absent from the wire', () => {
    // @JsonInclude(NON_NULL): a @GraphEdge that named neither a target nor a type
    // serialises to the field name alone.
    expect(GraphEdgeMetadataSchema.parse({ name: 'placedBy' })).toEqual({ name: 'placedBy' });
  });

  it('does not manufacture a direction the pipeline never carried', () => {
    const result = GraphEdgeMetadataSchema.parse({ name: 'placedBy', targetLabel: 'User' });

    expect('direction' in result).toBe(false);
  });

  it('GraphMetadata defaults all three collections to []', () => {
    const result = GraphMetadataSchema.parse({});

    expect(result.properties).toEqual([]);
    expect(result.edges).toEqual([]);
    expect(result.queries).toEqual([]);
  });

  it('defaults GraphProperty.indexed to false when the producer omits it', () => {
    const result = GraphMetadataSchema.parse({ properties: [{ name: 'total' }] });

    expect(result.properties[0].indexed).toBe(false);
  });
});

describe('SagaStepMetadataSchema + SagaMetadataSchema', () => {
  it('SagaStepMetadata accepts the optional retry + parallel + condition + dependsOn fields', () => {
    const result = SagaStepMetadataSchema.parse({
      name: 'reserve',
      action: 'reserveInventory',
      retries: 3,
      parallel: true,
      condition: '${order.items.size > 0}',
      dependsOn: ['validate'],
    });
    expect(result.retries).toBe(3);
    expect(result.parallel).toBe(true);
    expect(result.dependsOn).toEqual(['validate']);
  });

  it('SagaMetadata defaults steps=[] when omitted', () => {
    expect(SagaMetadataSchema.parse({ name: 'OrderFulfillment' }).steps).toEqual([]);
  });

  it('SagaMetadata rejects unknown compensationStrategy values', () => {
    expect(() => SagaMetadataSchema.parse({
      name: 'X', compensationStrategy: 'INVALID',
    })).toThrow(z.ZodError);
  });

  it('SagaMetadata accepts each compensationStrategy + compensationOrder enum value', () => {
    for (const s of ['ALL_OR_NOTHING', 'BEST_EFFORT', 'CUSTOM'] as const) {
      expect(SagaMetadataSchema.parse({
        name: 'X', compensationStrategy: s,
      }).compensationStrategy).toBe(s);
    }
    for (const o of ['REVERSE', 'FORWARD', 'PARALLEL'] as const) {
      expect(SagaMetadataSchema.parse({
        name: 'X', compensationOrder: o,
      }).compensationOrder).toBe(o);
    }
  });
});

describe('SystemFieldsMetadataSchema', () => {
  it('defaults idField to "id" when omitted', () => {
    expect(SystemFieldsMetadataSchema.parse({}).idField).toBe('id');
  });

  it('preserves a custom idField value', () => {
    expect(SystemFieldsMetadataSchema.parse({ idField: 'uuid' }).idField).toBe('uuid');
  });
});

describe('EventSourcedMetadataSchema', () => {
  it('requires aggregateType, optional snapshotInterval + eventStore', () => {
    const result = EventSourcedMetadataSchema.parse({ aggregateType: 'Order' });
    expect(result.aggregateType).toBe('Order');
    expect(result.snapshotInterval).toBeUndefined();
    expect(result.eventStore).toBeUndefined();
  });

  it('rejects when aggregateType is missing', () => {
    expect(() => EventSourcedMetadataSchema.parse({})).toThrow(z.ZodError);
  });
});

describe('InternalApiMetadataSchema', () => {
  it('defaults hidden / readOnly / internal to false', () => {
    const result = InternalApiMetadataSchema.parse({});
    expect(result.hidden).toBe(false);
    expect(result.readOnly).toBe(false);
    expect(result.internal).toBe(false);
  });

  it('preserves disabledActions + allowedRoles arrays', () => {
    const result = InternalApiMetadataSchema.parse({
      disabledActions: ['delete', 'archive'],
      allowedRoles: ['admin'],
    });
    expect(result.disabledActions).toEqual(['delete', 'archive']);
    expect(result.allowedRoles).toEqual(['admin']);
  });
});

describe('DomainMetadataSchema — top-level entity record', () => {
  it('defaults restApi=true, every other capability flag=false', () => {
    const result = DomainMetadataSchema.parse({
      entityName: 'Order',
      packageName: 'com.shop',
    });
    expect(result.restApi).toBe(true);
    expect(result.graphqlApi).toBe(false);
    expect(result.realTimeApi).toBe(false);
    expect(result.internalClient).toBe(false);
    expect(result.tenantScoped).toBe(false);
    expect(result.versioned).toBe(false);
    expect(result.fullTextSearch).toBe(false);
    expect(result.audited).toBe(false);
    expect(result.softDelete).toBe(false);
    expect(result.multiTenant).toBe(false);
    expect(result.cacheable).toBe(false);
    expect(result.cacheSeconds).toBe(0);
  });

  it('defaults fields / actions / events / relationships / projections to empty arrays', () => {
    const result = DomainMetadataSchema.parse({
      entityName: 'Order',
      packageName: 'com.shop',
    });
    expect(result.fields).toEqual([]);
    expect(result.actions).toEqual([]);
    expect(result.events).toEqual([]);
    expect(result.relationships).toEqual([]);
    expect(result.projections).toEqual([]);
  });

  it('round-trips nested uiMetadata / graphMetadata / sagaMetadata / eventSourced sub-schemas', () => {
    const result = DomainMetadataSchema.parse({
      entityName: 'Order',
      packageName: 'com.shop',
      uiMetadata: { icon: 'cart' },
      graphMetadata: { label: 'Order', edges: [] },
      sagaMetadata: { name: 'OrderSaga' },
      eventSourced: { aggregateType: 'Order' },
      systemFields: { idField: 'uuid' },
      internalApi: { internal: true },
    });
    expect(result.uiMetadata?.icon).toBe('cart');
    expect(result.graphMetadata?.label).toBe('Order');
    expect(result.sagaMetadata?.name).toBe('OrderSaga');
    expect(result.eventSourced?.aggregateType).toBe('Order');
    expect(result.systemFields?.idField).toBe('uuid');
    expect(result.internalApi?.internal).toBe(true);
  });

  it('rejects when required entityName + packageName are missing', () => {
    expect(() => DomainMetadataSchema.parse({})).toThrow(z.ZodError);
    expect(() => DomainMetadataSchema.parse({ entityName: 'Order' })).toThrow(z.ZodError);
    expect(() => DomainMetadataSchema.parse({ packageName: 'com.shop' })).toThrow(z.ZodError);
  });
});

describe('ExerisMetadataSchema — multi-domain root', () => {
  it('defaults version="0.2.0" + empty domains array', () => {
    const result = ExerisMetadataSchema.parse({});
    expect(result.version).toBe('0.2.0');
    expect(result.domains).toEqual([]);
  });

  it('preserves a list of domain records', () => {
    const result = ExerisMetadataSchema.parse({
      version: '0.3.0',
      domains: [
        { entityName: 'Order', packageName: 'com.shop' },
        { entityName: 'Customer', packageName: 'com.shop' },
      ],
    });
    expect(result.domains).toHaveLength(2);
    expect(result.domains[0].entityName).toBe('Order');
  });
});

describe('parseDomainMetadata + parseExerisMetadata helpers', () => {
  it('parseDomainMetadata accepts a valid plain object', () => {
    const result = parseDomainMetadata({
      entityName: 'Order',
      packageName: 'com.shop',
    });
    expect(result.entityName).toBe('Order');
  });

  it('parseDomainMetadata throws a ZodError on invalid input', () => {
    expect(() => parseDomainMetadata({ entityName: 'Order' })).toThrow(z.ZodError);
  });

  it('parseExerisMetadata accepts a valid root object', () => {
    expect(parseExerisMetadata({}).version).toBe('0.2.0');
  });

  it('parseExerisMetadata throws on non-object input (e.g. an array at root)', () => {
    expect(() => parseExerisMetadata('not an object')).toThrow(z.ZodError);
  });

  // ADR-059 — the TS twin of DomainMetadata.effectiveDataScope().
  it('dataScope is optional and accepts every tier', () => {
    expect(parseDomainMetadata({ entityName: 'Order', packageName: 'com.shop' }).dataScope)
      .toBeUndefined();
    for (const tier of ['GLOBAL', 'TENANT', 'UNIVERSE'] as const) {
      expect(
        parseDomainMetadata({ entityName: 'Order', packageName: 'com.shop', dataScope: tier })
          .dataScope
      ).toBe(tier);
    }
  });

  it('dataScope rejects a tier the AST does not model (UNSPECIFIED is annotation-side only)', () => {
    expect(() =>
      parseDomainMetadata({ entityName: 'Order', packageName: 'com.shop', dataScope: 'UNSPECIFIED' })
    ).toThrow(z.ZodError);
  });

  it('effectiveDataScope prefers an explicit tier over the deprecated boolean', () => {
    // Not merely a preference: an entity can declare TENANT without ever
    // setting tenantScoped, and a consumer reading the raw boolean would treat
    // it as unpartitioned.
    expect(effectiveDataScope({ dataScope: 'TENANT', tenantScoped: false })).toBe('TENANT');
    expect(effectiveDataScope({ dataScope: 'UNIVERSE', tenantScoped: false })).toBe('UNIVERSE');
  });

  it('effectiveDataScope falls back to tenantScoped when no tier is declared', () => {
    expect(effectiveDataScope({ dataScope: undefined, tenantScoped: true })).toBe('TENANT');
    expect(effectiveDataScope({ dataScope: undefined, tenantScoped: false })).toBe('GLOBAL');
  });
});
