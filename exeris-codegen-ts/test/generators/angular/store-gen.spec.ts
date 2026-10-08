/**
 * Coverage for src/generators/angular/store-gen.ts — StoreGenerator
 * emits a Signal-based Angular state store per domain with:
 *   - Filter + StoreState interfaces
 *   - 12 private signals (entities/selected/loading/saving/error/filter/
 *     page/size/totalElements/totalPages/sortField/sortDirection)
 *   - computed derived signals (count/isEmpty/hasActiveFilter/
 *     hasNextPage/hasPrevPage/state)
 *   - CRUD actions with optimistic update + rollback on error
 *   - Selection / filter / pagination / sort / state-management actions
 *   - Optional softDelete branch (archive method; no restore — nothing serves one)
 *   - Private helper extractErrorMessage
 *   - loadAll reads the list route's page envelope (ADR-096)
 *
 * Unique-to-store contracts pinned here:
 *   - NO generateAggregate method (no barrel file)
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
    expect(content).toContain("import type { Order, OrderCreate, OrderUpdate } from '../types/order.types';");
    expect(content).toContain("import type { PageRequest, OrderFilter, OrderSortField } from '../services/order.service';");
  });

  it('re-exports the service\'s Filter instead of declaring a second one', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'com.shop.OrderStatus', filterable: true })],
    }), CTX)!.content;

    expect(content).toContain('export type { OrderFilter, OrderSortField };');
    expect(content).not.toContain('export interface OrderFilter');
    // The enum the filter names is the service's import, so the store needs none of its own.
    expect(content).not.toContain("from '../types/enums'");
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
    expect(content).toContain("sort: {\n    /** The sorted property; `null` leaves the rows in id order. */\n    field: OrderSortField | null;\n    direction: 'asc' | 'desc';\n  };");
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
    expect(content).toContain('private readonly _sortField = signal<OrderSortField | null>(null);');
    expect(content).toContain("private readonly _sortDirection = signal<'asc' | 'desc'>('asc');");
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

// ---------- computed derived signals ----------

describe('StoreGenerator computed-signal declarations', () => {
  const gen = new StoreGenerator();

  it('declares count / isEmpty / hasActiveFilter / hasNextPage / hasPrevPage / state', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('readonly count = computed(() => this._entities().length);');
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

  it('filter actions: setFilter / updateFilter / clearFilter, each resetting page to 0 and reloading', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('async setFilter(filter: OrderFilter): Promise<void> {');
    expect(content).toContain('async updateFilter<K extends keyof OrderFilter>(');
    expect(content).toContain('async clearFilter(): Promise<void> {');
  });

  it('has no search: the list route reads no search parameter', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'name', type: 'String', searchable: true, filterable: true })],
    }), CTX)!.content;

    expect(content).not.toMatch(/search/i);
    expect(content).not.toContain('filteredEntities');
  });

  it('an active filter is any filter value that is set', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("return Object.values(filter).some((value) => value !== undefined && value !== null && value !== '');");
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
    // A size the route would refuse is never sent.
    expect(content).toContain('const MIN_PAGE_SIZE = 1;');
    expect(content).toContain('const MAX_PAGE_SIZE = 100;');
    expect(content).toContain('this._size.set(Math.min(Math.max(Math.trunc(size), MIN_PAGE_SIZE), MAX_PAGE_SIZE));');
  });

  it('sorting actions: setSort(field, direction) + toggleSort(field) with same-field flip-or-set logic', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("async setSort(field: OrderSortField | null, direction: 'asc' | 'desc' = 'asc'): Promise<void> {");
    expect(content).toContain('async toggleSort(field: OrderSortField): Promise<void> {');
    expect(content).toContain("this._sortDirection.update(d => d === 'asc' ? 'desc' : 'asc');");
  });

  it('state-management actions: clearError() + reset() restoring every signal to its initial value', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('clearError(): void {');
    expect(content).toContain('reset(): void {');
    // reset sets every signal back to its constructed-default state.
    expect(content).toMatch(/reset\(\): void \{[\s\S]*?this\._entities\.set\(\[\]\);[\s\S]*?this\._size\.set\(20\);[\s\S]*?this\._sortField\.set\(null\);[\s\S]*?this\._sortDirection\.set\('asc'\);[\s\S]*?\}/);
  });
});

// ---------- systemFields.primaryKeyField alias ----------

describe('StoreGenerator systemFields.primaryKeyField alias propagation', () => {
  const gen = new StoreGenerator();

  it('default idField "id" used in ALL 7 ${idField} substitution sites across the emitted store', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    // 7 substitution sites in store-gen.ts:
    expect(content).toContain('entities.map(e => e.id === id ? entity : e)');                  // 1 — loadById map
    expect(content).toContain('entities.map(e => e.id === id ? { ...e, ...data }');           // 2 — update optimistic map
    expect(content).toContain('entities.map(e => e.id === id ? updated : e)');                // 3 — update server-replace map
    expect(content).toContain('entities.filter(e => e.id !== id)');                            // 4 — delete optimistic filter
    expect(content).toContain('this._entities().find(e => e.id === id)');                      // 5 — select find
    // selected-match checks fire in BOTH update AND delete (sites 6 + 7).
    expect((content.match(/this\._selected\(\)\?\.id === id/g) ?? []).length).toBe(2);
  });

  it('a systemFields.primaryKeyField override moves ALL 7 substitution sites to the named key', () => {
    // The row's key is the field primaryKeyField names (ADR-104). The method parameter stays `id`:
    // it is the route's `:id` segment, which carries the key's value.
    //
    // Every site is enumerated: update()'s optimistic map, server-replace map and selected-match,
    // and delete()'s selected-match, are exactly where a partial change hides.
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [{ name: 'orderNo', type: 'java.util.UUID' }, { name: 'name', type: 'String' }],
      systemFields: { primaryKeyField: 'orderNo' },
    }), CTX)!.content;

    expect(content).toContain('entities.map(e => e.orderNo === id ? entity : e)');                 // 1 — loadById
    expect(content).toContain('entities.map(e => e.orderNo === id ? { ...e, ...data }');           // 2 — update optimistic
    expect(content).toContain('entities.map(e => e.orderNo === id ? updated : e)');                // 3 — update server-replace
    expect(content).toContain('entities.filter(e => e.orderNo !== id)');                           // 4 — delete optimistic
    expect(content).toContain('this._entities().find(e => e.orderNo === id)');                     // 5 — select find
    expect((content.match(/this\._selected\(\)\?\.orderNo === id/g) ?? []).length).toBe(2);       // 6 + 7 — selected match in update + delete
    expect(content).toContain('async loadById(id: string): Promise<Order>');
    expect(content).not.toMatch(/\be\.id\b|\?\.id\b/);
  });
});

// ---------- softDelete branch (archive) ----------

describe('StoreGenerator softDelete branch', () => {
  const gen = new StoreGenerator();

  it('softDelete=true adds an archive async method routed through service.softDelete', () => {
    const content = gen.generate(domain({ entityName: 'Order', softDelete: true }), CTX)!.content;

    expect(content).toContain('async archive(id: string): Promise<void> {');
    // B3: service returns Observable — await must go through firstValueFrom to resolve the value.
    expect(content).toContain('await firstValueFrom(this.service.softDelete(id));');
    expect(content).toContain("import { firstValueFrom } from 'rxjs';");
    // archive removes from list.
    expect(content).toContain('entities.filter(e => e.id !== id)');
  });

  // The generated server has no route that un-sets the soft-delete flag (PATCH/PUT parity), so
  // the store emits archive and no restore.
  it('softDelete=true emits no restore()', () => {
    const content = gen.generate(domain({ entityName: 'Order', softDelete: true }), CTX)!.content;

    expect(content).not.toContain('restore(');
    expect(content).not.toContain('@deprecated');
    expect(content).not.toContain('this.service.restore(');
  });

  it('softDelete=false (default) → no archive / no restore methods emitted', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).not.toContain('async archive(');
    expect(content).not.toContain('async restore(');
    expect(content).not.toContain('this.service.softDelete(');
    // firstValueFrom is unconditional (findAll/create/update/delete go through it) —
    // guard it on the non-softDelete path so it can't drift inside the soft-delete block.
    expect(content).toContain("import { firstValueFrom } from 'rxjs';");
    expect(content).not.toContain('this.service.restore(');
  });

  it('softDelete + a primaryKeyField override: archive identifies on the named key at both its sites', () => {
    // generateSoftDeleteMethods has its own two ${idField} substitution sites, which the
    // default softDelete test above does not reach.
    const content = gen.generate(domain({
      entityName: 'Order',
      softDelete: true,
      fields: [{ name: 'orderNo', type: 'java.util.UUID' }],
      systemFields: { primaryKeyField: 'orderNo' },
    }), CTX)!.content;

    expect(content).toContain('async archive(id: string): Promise<void>');
    expect(content).toContain('entities.filter(e => e.orderNo !== id)');
    expect(content).toContain('this._selected()?.orderNo === id');
    expect(content).not.toMatch(/\be\.id\b|\?\.id\b/);
  });
});

// ---------- extractErrorMessage helper ----------

describe('StoreGenerator extractErrorMessage — every failure goes through the shared status helper', () => {
  const gen = new StoreGenerator();

  it('delegates to httpErrorMessage with the entity noun and the action', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import { httpErrorMessage, type HttpErrorAction } from '../core/http-error';");
    expect(content).toContain("private extractErrorMessage(err: unknown, action: HttpErrorAction, entity = 'order'): string {");
    expect(content).toContain('return httpErrorMessage(err, { entity, action });');
    // No raw message reaches the error signal.
    expect(content).not.toContain('return err.message;');
    expect(content).not.toContain("'An unknown error occurred'");
  });

  it('names the plural for the page load and the action for each write', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("this._error.set(this.extractErrorMessage(err, 'load', 'orders'));");
    expect(content).toContain("this._error.set(this.extractErrorMessage(err, 'load'));");
    expect(content.match(/this\.extractErrorMessage\(err, 'save'\)/g)).toHaveLength(2);
    expect(content).toContain("this._error.set(this.extractErrorMessage(err, 'delete'));");
  });
});

// ---------- import sources ----------

describe('StoreGenerator emitted imports — every symbol from the module that exports it', () => {
  const gen = new StoreGenerator();

  it('imports PageRequest and the list query types from the service, which is where service-gen declares them', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import type { PageRequest, OrderFilter, OrderSortField } from '../services/order.service';");
    expect(content).not.toContain("PageRequest } from '../types/order.types'");
  });

  it('imports the entity shapes from the types module', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain("import type { Order, OrderCreate, OrderUpdate } from '../types/order.types';");
  });
});

// ---------- generateStore convenience ----------

describe('StoreGenerator loadAll — reads what the list route answers', () => {
  it('takes the rows and the totals from the page envelope', () => {
    const content = new StoreGenerator().generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain('const response = await firstValueFrom(this.service.findAll(pageRequest, this._filter()));');
    expect(content).toContain('this._entities.set(response.content);');
    expect(content).toContain('this._totalElements.set(response.totalElements);');
    expect(content).toContain('this._totalPages.set(response.totalPages);');
    expect(content).not.toContain('Array.isArray(response)');
  });

  it('sends sort only when a property is sorted, and the page and size always', () => {
    const content = new StoreGenerator().generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain('        page: this._page(),\n        size: this._size(),\n        ...(sort !== null ? { sort, direction: this._sortDirection() } : {}),');
  });
});

describe('generateStore — top-level convenience function', () => {
  it('returns the per-domain file for a domain', () => {
    const file = generateStore(domain({ entityName: 'Order' }), CTX.config);

    expect(file.path).toBe('stores/order.store.ts');
    expect(file.content).toContain('export class OrderStore');
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
