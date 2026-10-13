/**
 * The resolution of types outside the mapping table (src/core/type-references.ts): another entity,
 * an enum, or a type the metadata does not describe, and the imports a module needs for them.
 */

import { describe, expect, it } from 'vitest';
import {
  KNOWN_JAVA_TYPES,
  entityNamed,
  enumNameByConvention,
  fieldEnumImports,
  isUndescribedValue,
  mapScopedType,
  referencesNonScalar,
  resolveTypeReference,
  typeImports,
  typeScopeOf,
  type TypeScope,
} from '../../src/core/type-references.js';
import { createGeneratorContext } from '../../src/core/generator-registry.js';
import { DomainMetadataSchema, FieldMetadataSchema, type DomainMetadata } from '../../src/models/domain-model.js';
import { DslMapper } from '../../src/models/dsl-mapper.js';

const entity = (entityName: string, packageName = 'com.x'): DomainMetadata =>
  DomainMetadataSchema.parse({ packageName, entityName, fields: [{ name: 'id', type: 'java.util.UUID' }] });

const ORDER = entity('Order');
const CUSTOMER = entity('Customer');
const COMPONENT = entity('Component');
const PRIORITY = { name: 'Priority', qualifiedName: 'com.x.Priority' };

/** An app with three entities and one enum, whose enum list is complete. */
const SCOPE: TypeScope = { domains: [ORDER, CUSTOMER, COMPONENT], enums: [PRIORITY] };

describe('entityNamed', () => {
  it('matches a qualified type by package and name, a simple type by name', () => {
    expect(entityNamed('com.x.Customer', SCOPE.domains)).toBe(CUSTOMER);
    expect(entityNamed('Customer', SCOPE.domains)).toBe(CUSTOMER);
  });

  it('does not take a class of the same name in another package for the entity', () => {
    expect(entityNamed('com.other.Customer', SCOPE.domains)).toBeUndefined();
  });

  it('matches an entity with no package by its bare name', () => {
    const bare = { ...entity('Loose'), packageName: '' };
    expect(entityNamed('Loose', [bare])).toBe(bare);
  });
});

describe('enumNameByConvention — the enum rule without an enum list', () => {
  it('takes a PascalCase type with an enum suffix, or in a .domain. package, for an enum', () => {
    expect(enumNameByConvention('com.x.OrderStatus')).toBe('OrderStatus');
    expect(enumNameByConvention('com.x.domain.Plan')).toBe('Plan');
    for (const suffix of ['Role', 'Type', 'Plan', 'State', 'Level', 'Kind', 'Mode', 'Category']) {
      expect(enumNameByConvention(`com.x.Some${suffix}`)).toBe(`Some${suffix}`);
    }
  });

  it('takes no JDK type, parameterised type, array or other name for one', () => {
    expect(enumNameByConvention('java.lang.String')).toBeUndefined();
    expect(enumNameByConvention('java.util.List<com.x.OrderStatus>')).toBeUndefined();
    expect(enumNameByConvention('com.x.OrderStatus[]')).toBeUndefined();
    expect(enumNameByConvention('com.x.Money')).toBeUndefined();
    expect(enumNameByConvention('com.x.lowerStatus')).toBeUndefined();
  });

  it('takes no type whose simple name is a JDK type for one', () => {
    expect(KNOWN_JAVA_TYPES.has('Object')).toBe(true);
    expect(enumNameByConvention('com.x.domain.Object')).toBeUndefined();
  });
});

describe('resolveTypeReference', () => {
  it('resolves an entity, a declared enum, and anything else as opaque', () => {
    expect(resolveTypeReference('com.x.Customer', SCOPE)).toEqual({ kind: 'entity', domain: CUSTOMER });
    expect(resolveTypeReference('com.x.Priority', SCOPE)).toEqual({ kind: 'enum', name: 'Priority' });
    expect(resolveTypeReference('Priority', SCOPE)).toEqual({ kind: 'enum', name: 'Priority' });
    expect(resolveTypeReference('com.x.Money', SCOPE)).toEqual({ kind: 'opaque' });
  });

  it('with a complete enum list, takes no type for an enum by its name', () => {
    expect(resolveTypeReference('com.x.PaymentMode', SCOPE)).toEqual({ kind: 'opaque' });
    expect(resolveTypeReference('com.other.Priority', SCOPE)).toEqual({ kind: 'opaque' });
  });

  it('without an enum list, takes a type for an enum by its name, after the entities', () => {
    const legacy: TypeScope = { domains: [entity('OrderStatus')] };
    expect(resolveTypeReference('com.x.PaymentMode', legacy)).toEqual({ kind: 'enum', name: 'PaymentMode' });
    expect(resolveTypeReference('com.x.OrderStatus', legacy).kind).toBe('entity');
  });

  it('resolves a parameterised type the mapper has no rule for as opaque', () => {
    expect(resolveTypeReference('com.x.Page<com.x.Customer>', SCOPE)).toEqual({ kind: 'opaque' });
  });
});

describe('mapScopedType — the DTO type and the Zod expression', () => {
  it('maps an entity to its read type and a typed lazy reference to its schema', () => {
    expect(mapScopedType('com.x.Customer', SCOPE)).toMatchObject({
      tsType: 'Customer',
      zodType: 'z.lazy((): z.ZodType<Customer> => CustomerSchema)',
    });
  });

  it('names a renamed entity type as the module declares it', () => {
    expect(mapScopedType('com.x.Component', SCOPE)).toMatchObject({
      tsType: 'ComponentModel',
      zodType: 'z.lazy((): z.ZodType<ComponentModel> => ComponentModelSchema)',
    });
  });

  it('maps collections, arrays, map values and optionals of an entity through it', () => {
    expect(mapScopedType('java.util.List<com.x.Customer>', SCOPE).tsType).toBe('Customer[]');
    expect(mapScopedType('java.util.Set<com.x.Customer>', SCOPE).zodType)
      .toBe('z.array(z.lazy((): z.ZodType<Customer> => CustomerSchema))');
    expect(mapScopedType('com.x.Customer[]', SCOPE).tsType).toBe('Customer[]');
    expect(mapScopedType('java.util.Map<java.lang.String,com.x.Customer>', SCOPE).tsType).toBe('Record<string, Customer>');
    expect(mapScopedType('java.util.Optional<com.x.Customer>', SCOPE).tsType).toBe('Customer | null');
  });

  it('maps a type the metadata does not describe to unknown', () => {
    expect(mapScopedType('com.x.Money', SCOPE)).toMatchObject({ tsType: 'unknown', zodType: 'z.unknown()' });
    expect(mapScopedType('java.util.List<com.x.Money>', SCOPE).tsType).toBe('unknown[]');
    expect(mapScopedType('com.x.Page<com.x.Customer>', SCOPE).tsType).toBe('unknown');
  });

  it('keeps the mapper\'s own mapping for an enum and for every type the table names', () => {
    expect(mapScopedType('com.x.Priority', SCOPE)).toEqual(DslMapper.mapType('com.x.Priority'));
    for (const type of ['java.lang.String', 'java.lang.Integer', 'java.util.UUID', 'java.util.List<java.lang.String>',
      'java.util.Map<java.lang.String,java.lang.Integer>', 'java.time.OffsetDateTime', 'JsonNode']) {
      expect(mapScopedType(type, SCOPE)).toEqual(DslMapper.mapType(type));
    }
  });
});

describe('typeImports', () => {
  it('lists each referenced entity once, by name in code-unit order, and the enums in first-reached order', () => {
    const imports = typeImports(
      ['java.util.List<com.x.Order>', 'com.x.Customer', 'com.x.Priority', 'com.x.Order', 'com.x.Component', 'com.x.Money'],
      SCOPE,
    );
    expect(imports.entities.map((e) => e.entityName)).toEqual(['Component', 'Customer', 'Order']);
    expect(imports.enums).toEqual(['Priority']);
  });

  it('lists nothing for types the table maps', () => {
    expect(typeImports(['java.lang.String', 'java.util.List<java.util.UUID>'], SCOPE)).toEqual({ enums: [], entities: [] });
  });
});

describe('fieldEnumImports', () => {
  const field = (name: string, type: string, enumType?: string) =>
    FieldMetadataSchema.parse({ name, type, ...(enumType ? { enumType } : {}) });

  it('takes a field\'s enumType first, then every enum its type reaches, a collection\'s element included', () => {
    const fields = [
      field('a', 'com.x.Thing', 'com.x.Explicit'),
      field('b', 'java.util.List<com.x.Priority>'),
      field('c', 'com.x.Priority'),
    ];
    expect(fieldEnumImports(fields, SCOPE)).toEqual(['Explicit', 'Priority']);
  });

  it('without an enum list, takes the type of a field for an enum by its name', () => {
    expect(fieldEnumImports([field('s', 'com.x.OrderStatus'), field('m', 'com.x.Money')], { domains: [] })).toEqual(['OrderStatus']);
  });
});

describe('referencesNonScalar', () => {
  it('is true for an entity, an unknown value type, and a collection or optional of either', () => {
    for (const type of ['com.x.Customer', 'com.x.Money', 'java.util.List<com.x.Customer>', 'java.util.Optional<com.x.Money>']) {
      expect(referencesNonScalar(type, SCOPE)).toBe(true);
    }
  });

  it('is false for a JDK type, a declared enum and a collection of them', () => {
    for (const type of ['java.lang.String', 'java.util.UUID', 'com.x.Priority', 'java.util.List<com.x.Priority>', 'JsonNode']) {
      expect(referencesNonScalar(type, SCOPE)).toBe(false);
    }
  });
});

describe('isUndescribedValue', () => {
  it('is true for a type that maps to unknown through an undescribed type, optional included', () => {
    expect(isUndescribedValue('com.x.Money', SCOPE)).toBe(true);
    expect(isUndescribedValue('java.util.Optional<com.x.Money>', SCOPE)).toBe(true);
  });

  it('is false for JsonNode, which the table maps to unknown, and for a collection of undescribed values', () => {
    expect(isUndescribedValue('JsonNode', SCOPE)).toBe(false);
    expect(isUndescribedValue('java.util.List<com.x.Money>', SCOPE)).toBe(false);
    expect(isUndescribedValue('com.x.Customer', SCOPE)).toBe(false);
  });
});

describe('typeScopeOf', () => {
  it('holds the enum list only when the context says it is complete', () => {
    const ctx = createGeneratorContext({}, [ORDER], [{ ...PRIORITY, packageName: 'com.x', values: [] }]);
    expect(typeScopeOf(ctx)).toEqual({ domains: [ORDER], enums: undefined });
    expect(typeScopeOf({ ...ctx, enumsComplete: true }).enums).toEqual(ctx.enums);
  });
});
