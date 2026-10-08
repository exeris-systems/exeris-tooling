/**
 * Coverage for the MANY_TO_ONE foreign-key link in the list cell and the detail row.
 *
 * Exercises:
 *   - a UUID foreign key links to `/<routePlural(target)>/<id>` in list and detail
 *   - a qualified targetEntity resolves by its simple name
 *   - a target that is not loaded renders as plain text
 *   - a non-UUID field, an entity-typed field and the other three kinds are unchanged, but for the
 *     list's `<base>Id` equality filter, which every MANY_TO_ONE adds (ADR-096)
 *   - no new router import: RouterModule, already imported by both components, carries RouterLink
 *   - the form's picker: the same keys, resolved against a target whose service is generated
 */

import { describe, expect, it } from 'vitest';
import { ListGenerator } from '../../../src/generators/angular/list-gen.js';
import { DetailGenerator } from '../../../src/generators/angular/detail-gen.js';
import { foreignKeyLinks, foreignKeyPickers } from '../../../src/generators/angular/relationship-links.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import { DslMapper } from '../../../src/models/dsl-mapper.js';
import {
  DomainMetadataSchema,
  type DomainMetadata,
  type RelationshipMetadata,
} from '../../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

// Irregular plural on purpose: the expected segment is derived, never spelled out.
const category = domain({ entityName: 'Category', fields: [{ name: 'id', type: 'java.util.UUID' }] });
const CATEGORY_ROUTE = `/${DslMapper.routePlural('Category')}`;

function product(
  relationships: Array<Partial<RelationshipMetadata> & { name: string; targetEntity: string; type: RelationshipMetadata['type'] }>,
  fieldType = 'java.util.UUID',
): DomainMetadata {
  return domain({
    entityName: 'Product',
    fields: [
      { name: 'id', type: 'java.util.UUID' },
      { name: 'name', type: 'String' },
      { name: 'categoryId', type: fieldType },
    ],
    relationships: relationships as RelationshipMetadata[],
  });
}

function emit(d: DomainMetadata, all: DomainMetadata[]): { list: string; detail: string } {
  const ctx = createGeneratorContext({}, all);
  return {
    list: new ListGenerator().generate(d, ctx)!.content,
    detail: new DetailGenerator().generate(d, ctx)!.content,
  };
}

const FK = { name: 'categoryId', targetEntity: 'Category', type: 'MANY_TO_ONE' as const };

describe('MANY_TO_ONE UUID foreign key', () => {
  it('links the list cell to the target detail route, keeping the id as text', () => {
    const p = product([FK]);
    const { list } = emit(p, [p, category]);
    expect(list).toContain(`@if (item.categoryId) {`);
    expect(list).toContain(`<a [routerLink]="['${CATEGORY_ROUTE}', item.categoryId]"`);
    expect(list).toContain(`>{{ item.categoryId }}</a>`);
    // An empty foreign key renders as an unlinked cell.
    expect(list).toMatch(/\} @else \{\n\s+\{\{ item\.categoryId \}\}\n\s+\}/);
  });

  it('links the detail row through the field descriptor', () => {
    const p = product([FK]);
    const { detail } = emit(p, [p, category]);
    expect(detail).toContain(`  link?: string;`);
    expect(detail).toContain(`{ name: 'categoryId' as keyof Product, label: 'Category Id', type: 'text', link: '${CATEGORY_ROUTE}' },`);
    expect(detail).toContain(`@if (field.link && rawValue(field, entity()) !== null) {`);
    expect(detail).toContain(`<a [routerLink]="[field.link, rawValue(field, entity())]"`);
    // Other rows carry no link.
    expect(detail).toContain(`{ name: 'name' as keyof Product, label: 'Name', type: 'text' },`);
  });

  it('resolves a qualified targetEntity by its simple name', () => {
    const p = product([{ ...FK, targetEntity: 'com.shop.catalog.Category' }]);
    expect(foreignKeyLinks(p, [p, category]).get('categoryId')).toBe(CATEGORY_ROUTE);
    expect(emit(p, [p, category]).list).toContain(`['${CATEGORY_ROUTE}', item.categoryId]`);
  });

  it('reads fieldName when the relationship names its field explicitly', () => {
    const p = product([{ ...FK, name: 'category', fieldName: 'categoryId' }]);
    expect(foreignKeyLinks(p, [p, category]).get('categoryId')).toBe(CATEGORY_ROUTE);
  });

  it('adds no router import: RouterModule already provides RouterLink', () => {
    const p = product([FK]);
    const linked = emit(p, [p, category]);
    const plain = emit(p, [p]);
    for (const [a, b] of [[linked.list, plain.list], [linked.detail, plain.detail]]) {
      const imports = (s: string) => s.split('\n').filter((l) => l.startsWith('import ') || l.startsWith('  imports:'));
      expect(imports(a)).toEqual(imports(b));
      expect(a).not.toContain('RouterLink,');
      expect(a).not.toContain('RouterLink }');
    }
  });
});

/**
 * No link: the detail view is the one without the relationship, and the list cell is plain text. The
 * list differs only by the `<base>Id` filter of the list route, which a MANY_TO_ONE adds whether or
 * not its key links.
 */
function expectNoLink(out: { list: string; detail: string }, plain: { list: string; detail: string }): void {
  expect(out.detail).toEqual(plain.detail);
  expect(out.list).not.toContain(`[routerLink]="['${CATEGORY_ROUTE}'`);
  expect(out.list).not.toContain('link-categoryId');
  expect(out.list).toContain('data-testid="filter-categoryId"');
}

describe('no link — rendered exactly as before', () => {
  const baseline = emit(product([]), [product([]), category]);

  it('when the target is not loaded', () => {
    const p = product([FK]);
    expectNoLink(emit(p, [p]), baseline);
  });

  it('when the local field is not a UUID', () => {
    const p = product([FK], 'String');
    const plain = product([], 'String');
    expectNoLink(emit(p, [p, category]), emit(plain, [plain, category]));
  });

  it('when the relationship field is entity-typed', () => {
    const p = product([FK], 'com.shop.Category');
    const plain = product([], 'com.shop.Category');
    expectNoLink(emit(p, [p, category]), emit(plain, [plain, category]));
  });

  it.each(['ONE_TO_ONE', 'MANY_TO_MANY'] as const)('for %s', (type) => {
    const p = product([{ ...FK, type }]);
    expect(emit(p, [p, category])).toEqual(baseline);
  });

  it('for ONE_TO_MANY, whose only addition is the detail view\'s related-records link', () => {
    const p = product([{ ...FK, type: 'ONE_TO_MANY' }]);
    const out = emit(p, [p, category]);
    expect(out.list).toEqual(baseline.list);
    expect(out.detail).not.toContain(`link: '${CATEGORY_ROUTE}'`);
    expect(out.detail).toContain(`<a routerLink="${CATEGORY_ROUTE}" data-testid="related-category-id"`);
  });

  it('when the relationship names no declared field', () => {
    const p = product([{ ...FK, name: 'category' }]);
    expectNoLink(emit(p, [p, category]), baseline);
  });
});

describe('foreignKeyLinks — detail views off', () => {
  it('links nothing when no detail route is generated', () => {
    const p = product([FK]);
    expect(foreignKeyLinks(p, [p, category], false).size).toBe(0);
    const list = new ListGenerator().generate(p, createGeneratorContext({ generateDetails: false }, [p, category]))!.content;
    expect(list).not.toContain(`[routerLink]="['${CATEGORY_ROUTE}'`);
    expect(list).toContain('{{ item.categoryId }}');
  });
});

describe('foreignKeyPickers', () => {
  const named = domain({
    entityName: 'Category',
    fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'title', type: 'String' }],
  });

  it('picks a UUID MANY_TO_ONE key from its target, labelled by the display field the target declares', () => {
    const p = product([{ ...FK, displayField: 'title' }]);
    expect(foreignKeyPickers(p, [p, named], true).get('categoryId')).toEqual({
      target: 'Category',
      serviceModule: 'category.service',
      keyField: 'id',
      labelField: 'title',
    });
  });

  it('labels by id when the target declares no field named by displayField', () => {
    const p = product([{ ...FK, displayField: 'missing' }]);
    expect(foreignKeyPickers(p, [p, named], true).get('categoryId')).toEqual({
      target: 'Category',
      serviceModule: 'category.service',
      keyField: 'id',
    });
  });

  it('reads an option\'s value from the key the target names', () => {
    const renamed = domain({
      entityName: 'Category',
      fields: [{ name: 'categoryNo', type: 'java.util.UUID' }, { name: 'title', type: 'String' }],
      systemFields: { primaryKeyField: 'categoryNo' },
    });
    const p = product([{ ...FK, displayField: 'title' }]);
    expect(foreignKeyPickers(p, [p, renamed], true).get('categoryId')?.keyField).toBe('categoryNo');
  });

  it('resolves a qualified target by its simple name', () => {
    const p = product([{ ...FK, targetEntity: 'com.shop.Category', displayField: 'title' }]);
    expect(foreignKeyPickers(p, [p, named], true).get('categoryId')?.target).toBe('Category');
  });

  it('has no picker without generated services, for an absent target, or a target without an id', () => {
    const p = product([{ ...FK, displayField: 'title' }]);
    expect(foreignKeyPickers(p, [p, named], false).size).toBe(0);
    expect(foreignKeyPickers(p, [p], true).size).toBe(0);
    const idless = domain({ entityName: 'Category', fields: [{ name: 'title', type: 'String' }] });
    expect(foreignKeyPickers(p, [p, idless], true).size).toBe(0);
  });

  it('has no picker for a non-UUID field or another relationship kind', () => {
    const asString = product([{ ...FK, displayField: 'title' }], 'String');
    expect(foreignKeyPickers(asString, [asString, named], true).size).toBe(0);
    const oneToOne = product([{ ...FK, type: 'ONE_TO_ONE', displayField: 'title' }]);
    expect(foreignKeyPickers(oneToOne, [oneToOne, named], true).size).toBe(0);
  });

  it('does not depend on the target having a detail page', () => {
    const noDetail = domain({
      entityName: 'Category',
      fields: [{ name: 'id', type: 'java.util.UUID' }],
      uiMetadata: { detailView: false } as DomainMetadata['uiMetadata'],
    });
    const p = product([FK]);
    expect(foreignKeyLinks(p, [p, noDetail]).size).toBe(0);
    expect(foreignKeyPickers(p, [p, noDetail], true).get('categoryId')?.target).toBe('Category');
  });
});
