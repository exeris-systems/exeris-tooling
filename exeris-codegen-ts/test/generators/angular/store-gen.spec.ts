/**
 * Coverage for src/generators/angular/store-gen.ts — StoreGenerator
 * emits a Signal-based Angular state store per domain with:
 *   - Filter + StoreState interfaces
 *   - 12 private signals (entities/selected/loading/saving/error/filter/
 *     page/size/totalElements/totalPages/sortField/sortDirection)
 *   - 9 computed derived signals (filteredEntities/count/filteredCount/
 *     isEmpty/hasActiveFilter/hasNextPage/hasPrevPage/state)
 *   - CRUD actions with optimistic update + rollback on error
 *   - Selection / filter / pagination / sort / state-management actions
 *   - Optional softDelete branch (archive + restore methods)
 *   - Private helpers (getSearchableText / extractErrorMessage)
 *
 * Unique-to-store contracts pinned here:
 *   - NO generateAggregate method (no barrel file)
 *   - generateStore convenience THROWS on a hidden domain
 *     (sibling form/service/guard/list convenience funcs return null)
 *   - NO explicit priority field — defaults to undefined (10 fallback
 *     used by GeneratorRegistry)
 */

import { describe, expect, it } from 'vitest';
import { StoreGenerator, generateStore } from '../../../src/generators/angular/store-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  FieldMetadataSchema,
  type DomainMetadata,
  type FieldMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
}

function hiddenDomain(entityName: string): DomainMetadata {
  return domain({
    entityName,
    internalApi: { hidden: true, readOnly: false, internal: false },
  });
}

// ---------- CodeGenerator contract ----------

describe('StoreGenerator — CodeGenerator metadata', () => {
  const gen = new StoreGenerator();

  it('declares name / artifactType / supportedBackends; priority is undefined (defaults to 10 via registry fallback)', () => {
    expect(gen.name).toBe('StoreGenerator');
    expect(gen.artifactType).toBe('STORE');
    expect(gen.supportedBackends).toEqual([]);
    expect(gen.priority).toBeUndefined();
  });

  it('does NOT implement generateAggregate (no barrel file — different from sibling service/guard/list)', () => {
    expect(gen.generateAggregate).toBeUndefined();
  });
});

// ---------- generate — path + hidden-skip ----------

describe('StoreGenerator.generate — emit path + hidden-skip', () => {
  const gen = new StoreGenerator();

  it('emits stores/<kebab>.store.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('stores/order-line.store.ts');
    expect(file!.artifactType).toBe('STORE');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null for an internalApi.hidden domain', () => {
    expect(gen.generate(hiddenDomain('Audit'), CTX)).toBeNull();
  });
});

// ---------- emitted structure ----------

describe('StoreGenerator emitted content — top-level structure', () => {
  const gen = new StoreGenerator();

  it('imports Angular core signal primitives + DestroyRef + service + entity types', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('Injectable,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    expect(content).toContain('effect,');
    expect(content).toContain('DestroyRef,');
    expect(content).toContain("from '@angular/core';");
    expect(content).toContain("import { takeUntilDestroyed } from '@angular/core/rxjs-interop';");
    expect(content).toContain("import { OrderService } from '../services/order.service';");
    expect(content).toContain("import type { Order, OrderCreate, OrderUpdate, Page, PageRequest } from '../types/order.types';");
  });

  it('emits Filter interface with search?: string always + filterable fields', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status', type: 'String', filterable: true }),
        field({ name: 'paid', type: 'Boolean', filterable: true }),
        field({ name: 'notFilterable', type: 'String' }),
      ],
    }), CTX)!.content;

    expect(content).toContain('export interface OrderFilter {');
    expect(content).toContain('search?: string;');
    expect(content).toContain('status?: string;');
    expect(content).toContain('paid?: boolean;');
    expect(content).not.toContain('notFilterable');
  });

  it('emits StoreState interface with the full shape (entities/selected/loading/saving/error/filter/pagination/sort)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export interface OrderStoreState {');
    expect(content).toContain('entities: Order[];');
    expect(content).toContain('selected: Order | null;');
    expect(content).toContain('loading: boolean;');
    expect(content).toContain('saving: boolean;');
    expect(content).toContain('error: string | null;');
    expect(content).toContain('filter: OrderFilter;');
    expect(content).toContain('pagination: {');
    expect(content).toContain("sort: {\n    field: string;\n    direction: 'asc' | 'desc';\n  };");
  });

  it('emits @Injectable({ providedIn: \'root\' }) decorator on the entityName-suffixed Store class', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("@Injectable({ providedIn: 'root' })");
    expect(content).toContain('export class OrderLineStore {');
    expect(content).toContain('private readonly service = inject(OrderLineService);');
    expect(content).toContain('private readonly destroyRef = inject(DestroyRef);');
  });
});

// ---------- 12 private signals + 12 readonly accessors ----------

describe('StoreGenerator private signal declarations', () => {
  const gen = new StoreGenerator();

  it('declares all 12 private state signals', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('private readonly _entities = signal<Order[]>([]);');
    expect(content).toContain('private readonly _selected = signal<Order | null>(null);');
    expect(content).toContain('private readonly _loading = signal(false);');
    expect(content).toContain('private readonly _saving = signal(false);');
    expect(content).toContain('private readonly _error = signal<string | null>(null);');
    expect(content).toContain('private readonly _filter = signal<OrderFilter>({});');
    expect(content).toContain('private readonly _page = signal(0);');
    expect(content).toContain('private readonly _size = signal(20);');
    expect(content).toContain('private readonly _totalElements = signal(0);');
    expect(content).toContain('private readonly _totalPages = signal(0);');
    expect(content).toContain("private readonly _sortField = signal<string>('id');");
    expect(content).toContain("private readonly _sortDirection = signal<'asc' | 'desc'>('desc');");
  });

  it('public readonly signal surface exposes each private signal via .asReadonly()', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    for (const name of [
      'entities', 'selected', 'loading', 'saving', 'error', 'filter',
      'page', 'size', 'totalElements', 'totalPages', 'sortField', 'sortDirection',
    ]) {
      expect(content).toContain(`readonly ${name} = this._${name}.asReadonly();`);
    }
  });
});

// ---------- 9 computed derived signals ----------

describe('StoreGenerator computed-signal declarations', () => {
  const gen = new StoreGenerator();

  it('declares filteredEntities / count / filteredCount / isEmpty / hasActiveFilter / hasNextPage / hasPrevPage / state', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('readonly filteredEntities = computed(() => {');
    expect(content).toContain('readonly count = computed(() => this._entities().length);');
    expect(content).toContain('readonly filteredCount = computed(() => this.filteredEntities().length);');
    expect(content).toContain('readonly isEmpty = computed(() => this._entities().length === 0);');
    expect(content).toContain('readonly hasActiveFilter = computed(() => {');
    expect(content).toContain('readonly hasNextPage = computed(() => this._page() < this._totalPages() - 1);');
    expect(content).toContain('readonly hasPrevPage = computed(() => this._page() > 0);');
    expect(content).toContain('readonly state = computed<OrderStoreState>(() => ({');
  });
});

// ---------- CRUD action methods with optimistic update + rollback ----------

describe('StoreGenerator CRUD action methods', () => {
  const gen = new StoreGenerator();

  it('loadAll / loadById / create / update / delete are async + set loading/saving + handle errors', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    // Method signatures
    expect(content).toContain('async loadAll(): Promise<void> {');
    expect(content).toContain('async loadById(id: string): Promise<Order> {');
    expect(content).toContain('async create(data: OrderCreate): Promise<Order> {');
    expect(content).toContain('async update(id: string, data: OrderUpdate): Promise<Order> {');
    expect(content).toContain('async delete(id: string): Promise<void> {');

    // Loading vs saving distinction (read ops use loading, write ops use saving)
    expect(content).toMatch(/async loadAll[\s\S]*?this\._loading\.set\(true\)/);
    expect(content).toMatch(/async loadById[\s\S]*?this\._loading\.set\(true\)/);
    expect(content).toMatch(/async create[\s\S]*?this\._saving\.set\(true\)/);
    expect(content).toMatch(/async update[\s\S]*?this\._saving\.set\(true\)/);
    expect(content).toMatch(/async delete[\s\S]*?this\._saving\.set\(true\)/);
  });

  it('update + delete use optimistic-update-with-rollback (snapshot before, restore on catch)', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    // Both update and delete take a snapshot before the optimistic mutation.
    expect((content.match(/const previousEntities = this\._entities\(\);/g) ?? []).length).toBe(2);

    // Rollback on error: set entities back to the snapshot in the catch arm.
    expect((content.match(/this\._entities\.set\(previousEntities\);/g) ?? []).length).toBe(2);
  });

  it('create appends to entities array (latest-first) AND bumps totalElements', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('this._entities.update(entities => [created, ...entities]);');
    expect(content).toContain('this._totalElements.update(n => n + 1);');
  });

  it('delete decrements totalElements with Math.max(0, n - 1) guard', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('this._totalElements.update(n => Math.max(0, n - 1));');
  });

  it('delete + update both clear _selected when the deleted/updated id matches the current selection', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    // delete: clear-on-match
    expect(content).toMatch(/this\._selected\(\)\?\.id === id[\s\S]*?this\._selected\.set\(null\);/);
    // update: replace-on-match
    expect(content).toMatch(/this\._selected\(\)\?\.id === id[\s\S]*?this\._selected\.set\(updated\);/);
  });

  it('all 5 CRUD methods reset loading/saving in finally + clear _error at start', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    // Each async method clears error at the top, sets loading/saving false in finally.
    const errorSetCount = (content.match(/this\._error\.set\(null\);/g) ?? []).length;
    expect(errorSetCount).toBeGreaterThanOrEqual(5); // 5 CRUD methods minimum
    expect((content.match(/} finally \{[\s\S]*?this\._loading\.set\(false\)[\s\S]*?\}/g) ?? []).length).toBeGreaterThanOrEqual(2);
    expect((content.match(/} finally \{[\s\S]*?this\._saving\.set\(false\)[\s\S]*?\}/g) ?? []).length).toBeGreaterThanOrEqual(3);
  });
});

// ---------- selection / filter / pagination / sort actions ----------

describe('StoreGenerator action surface', () => {
  const gen = new StoreGenerator();

  it('selection actions: select(id) + clearSelection()', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('select(id: string | null): void {');
    expect(content).toContain('clearSelection(): void {');
    // Selection by id finds in entities array by idField.
    expect(content).toContain('this._entities().find(e => e.id === id)');
  });

  it('filter actions: setFilter / updateFilter / clearFilter / setSearch + each resets page to 0 and reloads', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('async setFilter(filter: OrderFilter): Promise<void> {');
    expect(content).toContain('async updateFilter<K extends keyof OrderFilter>(');
    expect(content).toContain('async clearFilter(): Promise<void> {');
    expect(content).toContain('async setSearch(query: string): Promise<void> {');

    // setSearch routes through updateFilter('search', query || undefined)
    expect(content).toContain("await this.updateFilter('search', query || undefined);");
  });

  it('pagination actions: goToPage / nextPage / prevPage / setPageSize with bounds-guarded behaviour', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('async goToPage(page: number): Promise<void> {');
    expect(content).toContain('if (page < 0 || page >= this._totalPages()) return;');
    expect(content).toContain('async nextPage(): Promise<void> {');
    expect(content).toContain('if (this.hasNextPage()) {');
    expect(content).toContain('async prevPage(): Promise<void> {');
    expect(content).toContain('if (this.hasPrevPage()) {');
    expect(content).toContain('async setPageSize(size: number): Promise<void> {');
  });

  it('sorting actions: setSort(field, direction) + toggleSort(field) with same-field flip-or-set logic', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("async setSort(field: string, direction: 'asc' | 'desc' = 'asc'): Promise<void> {");
    expect(content).toContain('async toggleSort(field: string): Promise<void> {');
    expect(content).toContain("this._sortDirection.update(d => d === 'asc' ? 'desc' : 'asc');");
  });

  it('state-management actions: clearError() + reset() restoring every signal to its initial value', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('clearError(): void {');
    expect(content).toContain('reset(): void {');
    // reset sets every signal back to its constructed-default state.
    expect(content).toMatch(/reset\(\): void \{[\s\S]*?this\._entities\.set\(\[\]\);[\s\S]*?this\._size\.set\(20\);[\s\S]*?this\._sortDirection\.set\('desc'\);[\s\S]*?\}/);
  });
});

// ---------- systemFields.idField alias ----------

describe('StoreGenerator systemFields.idField alias propagation', () => {
  const gen = new StoreGenerator();

  it('default idField "id" used in ALL 9 ${idField} substitution sites across the emitted store', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    // 9 substitution sites in store-gen.ts:
    expect(content).toContain("private readonly _sortField = signal<string>('id');");        // 1 — _sortField init
    expect(content).toContain('entities.map(e => e.id === id ? entity : e)');                  // 2 — loadById map
    expect(content).toContain('entities.map(e => e.id === id ? { ...e, ...data }');           // 3 — update optimistic map
    expect(content).toContain('entities.map(e => e.id === id ? updated : e)');                // 4 — update server-replace map
    expect(content).toContain('entities.filter(e => e.id !== id)');                            // 5 — delete optimistic filter
    expect(content).toContain('this._entities().find(e => e.id === id)');                      // 6 — select find
    expect(content).toContain("this._sortField.set('id');");                                   // 7 — reset re-init
    // selected-match checks fire in BOTH update AND delete (sites 8 + 9).
    expect((content.match(/this\._selected\(\)\?\.id === id/g) ?? []).length).toBe(2);
  });

  it('custom systemFields.idField overrides ALL 9 substitution sites uniformly (matches the default-idField parity)', () => {
    // Reviewer of #57 flagged that the previous version of this test
    // checked 5 of 9 sites — the 4 missed sites were in update()
    // (optimistic + server-replace mapping + selected-match check)
    // and in delete()'s selected-match check. A future edit forgetting
    // to substitute `${idField}` at any of those would have passed the
    // old test. All 9 are now explicitly pinned for parity with the
    // default-idField sibling test above.
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { idField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain("private readonly _sortField = signal<string>('uuid');");      // 1
    expect(content).toContain('entities.map(e => e.uuid === id ? entity : e)');                // 2 — loadById
    expect(content).toContain('entities.map(e => e.uuid === id ? { ...e, ...data }');         // 3 — update optimistic
    expect(content).toContain('entities.map(e => e.uuid === id ? updated : e)');              // 4 — update server-replace
    expect(content).toContain('entities.filter(e => e.uuid !== id)');                          // 5 — delete optimistic
    expect(content).toContain('this._entities().find(e => e.uuid === id)');                    // 6 — select find
    expect(content).toContain("this._sortField.set('uuid');");                                 // 7 — reset re-init
    expect((content.match(/this\._selected\(\)\?\.uuid === id/g) ?? []).length).toBe(2);       // 8 + 9 — selected match in update + delete
    // Negative: zero 'id' references at these sites (proves the
    // substitution was complete, not partial).
    expect(content).not.toContain('this._selected()?.id === id');
    expect(content).not.toContain('entities.filter(e => e.id !== id)');
  });
});

// ---------- softDelete branch (archive + restore) ----------

describe('StoreGenerator softDelete branch', () => {
  const gen = new StoreGenerator();

  it('softDelete=true adds archive + restore async methods routed through service.softDelete / service.restore', () => {
    const content = gen.generate(domain({ entityName: 'Order', softDelete: true }), CTX)!.content;

    expect(content).toContain('async archive(id: string): Promise<void> {');
    expect(content).toContain('async restore(id: string): Promise<Order> {');
    expect(content).toContain('await this.service.softDelete(id);');
    expect(content).toContain('await this.service.restore(id);');
    // archive removes from list; restore prepends.
    expect(content).toContain('entities.filter(e => e.id !== id)');
    expect(content).toContain('[restored, ...entities]');
  });

  it('softDelete=false (default) → no archive / no restore methods emitted', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).not.toContain('async archive(');
    expect(content).not.toContain('async restore(');
    expect(content).not.toContain('this.service.softDelete(');
    expect(content).not.toContain('this.service.restore(');
  });

  it('softDelete + custom idField: archive method substitutes idField at BOTH its sites (filter + selected-match)', () => {
    // The softDelete branch in generateSoftDeleteMethods has its own
    // two ${idField} substitution sites that the default-idField
    // softDelete test above doesn't exercise. This combination test
    // catches a regression where archive() forgets the idField
    // substitution while the rest of the store correctly uses the
    // alias (e.g. by hardcoding 'id' inside the soft-delete branch
    // template).
    const content = gen.generate(domain({
      entityName: 'Order',
      softDelete: true,
      systemFields: { idField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain('async archive(id: string): Promise<void>');
    expect(content).toContain('entities.filter(e => e.uuid !== id)');
    expect(content).toContain('this._selected()?.uuid === id');
    // Negative: no leaked 'id' substitution at the soft-delete sites.
    expect(content).not.toContain('entities.filter(e => e.id !== id)');
  });
});

// ---------- getTsFilterType: nullability strip ----------

describe('StoreGenerator getTsFilterType — strips union/nullability', () => {
  const gen = new StoreGenerator();

  it('union-type field (Integer → "number | null") is collapsed to the FIRST arm ("number") in the Filter interface', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [
        field({ name: 'maybeNum', type: 'Integer', filterable: true }),
        field({ name: 'plainStr', type: 'String', filterable: true }),
      ],
    }), CTX)!.content;

    // Integer normally maps to "number | null"; filter strips to "number".
    expect(content).toContain('maybeNum?: number;');
    // String has no union to strip.
    expect(content).toContain('plainStr?: string;');
  });
});

// ---------- generateFieldFilters branch ----------

describe('StoreGenerator filteredEntities filter-loop emission', () => {
  const gen = new StoreGenerator();

  it('emits per-field equality guard for each filterable field', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'status', type: 'String', filterable: true }),
        field({ name: 'paid', type: 'Boolean', filterable: true }),
      ],
    }), CTX)!.content;

    expect(content).toContain('if (filter.status !== undefined && entity.status !== filter.status) {');
    expect(content).toContain('if (filter.paid !== undefined && entity.paid !== filter.paid) {');
  });

  it('no filterable fields → emits the "// No filterable fields" sentinel comment inside the loop body', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'name', type: 'String' })], // not filterable
    }), CTX)!.content;

    expect(content).toContain('// No filterable fields');
  });
});

// ---------- generateSearchableFieldAccess branch ----------

describe('StoreGenerator getSearchableText emission', () => {
  const gen = new StoreGenerator();

  it('emits a parts.push line per searchable field', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'name', type: 'String', searchable: true }),
        field({ name: 'description', type: 'String', searchable: true }),
      ],
    }), CTX)!.content;

    expect(content).toContain('if (entity.name) parts.push(String(entity.name));');
    expect(content).toContain('if (entity.description) parts.push(String(entity.description));');
  });

  it('no searchable fields → emits the "// No searchable fields defined" sentinel comment', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'name', type: 'String' })], // not searchable
    }), CTX)!.content;

    expect(content).toContain('// No searchable fields defined');
  });
});

// ---------- extractErrorMessage helper ----------

describe('StoreGenerator extractErrorMessage helper — Error / message-bearing / fallback arms', () => {
  const gen = new StoreGenerator();

  it('emits the 3-branch extractErrorMessage helper with $localize fallback', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('private extractErrorMessage(err: unknown): string {');
    // Arm 1: instanceof Error → err.message
    expect(content).toContain('if (err instanceof Error) {');
    expect(content).toContain('return err.message;');
    // Arm 2: object with "message" property
    expect(content).toContain("typeof err === 'object' && err !== null && 'message' in err");
    // Arm 3: fallback via $localize i18n marker
    expect(content).toContain('$localize`:@@error.unknown:An unknown error occurred`');
  });
});

// ---------- generateStore convenience (unique contract: THROWS on hidden) ----------

describe('generateStore — top-level convenience function (unique THROW-on-hidden contract)', () => {
  it('returns the per-domain file for a visible domain', () => {
    const file = generateStore(domain({ entityName: 'Order' }), CTX.config);

    expect(file.path).toBe('stores/order.store.ts');
    expect(file.content).toContain('export class OrderStore');
  });

  it('THROWS for a hidden domain — sibling generate*Convenience returns null instead', () => {
    // Unlike generateForm / generateGuard / generateList / generateService
    // which all return null for hidden domains, generateStore throws an
    // explicit Error. Pinned here so any future contract realignment
    // (e.g. widening to | null for cross-convention consistency) fails
    // this test loudly and surfaces the deliberate API change.
    expect(() => generateStore(hiddenDomain('Audit'), CTX.config))
      .toThrow(/Failed to generate store for Audit/);
  });

  it('hardcodes backend = "KERNEL" inside the convenience context', () => {
    // generateStore always passes 'KERNEL' to the GeneratorContext rather
    // than threading config.backend. Under kernel-target-only there is only
    // one valid backend; this confirms the convenience path emits.
    const file = generateStore(
      domain({ entityName: 'Order' }),
      { ...CTX.config, backend: 'KERNEL' },
    );
    expect(file.path).toBe('stores/order.store.ts');
  });
});
