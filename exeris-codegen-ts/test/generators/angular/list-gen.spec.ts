/**
 * Coverage for src/generators/angular/list-gen.ts — ListGenerator emits a standalone, OnPush,
 * signal-driven list component that loads the whole collection (the server's list route returns
 * an array and reads no query parameter) and pages, sorts, searches and filters it in the browser.
 *
 * Exercises:
 *   - list columns: the first 5 non-hidden non-system fields
 *   - displayName / pluralName fallbacks
 *   - systemFields.primaryKeyField alias in track / data-testid / delete dispatch
 *   - per-column rendering: boolean badge, enum badge, number / currency / percent right-aligned,
 *     date against date-time, url and foreign-key links
 *   - sortable headers, every filterable field's control, search, page size and row actions
 */

import { describe, expect, it } from 'vitest';
import { ListGenerator, generateList } from '../../../src/generators/angular/list-gen.js';
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

describe('ListGenerator — CodeGenerator metadata', () => {
  const gen = new ListGenerator();

  it('declares name / artifactType / priority / supportedBackends', () => {
    expect(gen.name).toBe('ListGenerator');
    expect(gen.artifactType).toBe('LIST');
    expect(gen.priority).toBe(20);
    expect(gen.supportedBackends).toEqual([]);
  });
});

// ---------- generate — path + hidden-skip ----------

describe('ListGenerator.generate — emit path + hidden-skip', () => {
  const gen = new ListGenerator();

  it('emits components/<kebab>-list.component.ts for a visible domain', () => {
    const file = gen.generate(domain({ entityName: 'OrderLine' }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-line-list.component.ts');
    expect(file!.artifactType).toBe('LIST');
    expect(file!.overwritable).toBe(true);
  });

});

// ---------- emitted top-level structure ----------

describe('ListGenerator emitted content — top-level structure', () => {
  const gen = new ListGenerator();

  it('imports signals, the router, the service and the HTTP error helper; no forms, rxjs or animations', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain("import {");
    expect(content).toContain('Component,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    expect(content).toContain("from '@angular/core';");
    expect(content).toContain("import { RouterModule } from '@angular/router';");
    expect(content).toContain("import { Order, OrderService, Page } from '../services/order.service';");
    expect(content).toContain("import { httpErrorMessage } from '../core/http-error';");
    // Search and filters write signals from template references: nothing binds ngModel.
    expect(content).not.toContain('FormsModule');
    expect(content).not.toContain("from 'rxjs'");
    expect(content).not.toContain('CommonModule');
    // B4: @angular/animations is deprecated in v22 — row enter is native animate.enter (no import).
    expect(content).not.toContain("from '@angular/animations'");
  });

  it('imports exactly the pipes its columns use', () => {
    const content = gen.generate(domain({
      entityName: 'Invoice',
      fields: [
        field({ name: 'issuedOn', type: 'java.time.LocalDate' }),
        field({ name: 'lines', type: 'Integer' }),
        field({ name: 'amount', type: 'BigDecimal', dataType: 'currency' }),
      ],
    }), CTX)!.content;
    expect(content).toContain("import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';");
    expect(content).toContain('imports: [RouterModule, CurrencyPipe, DatePipe, DecimalPipe],');
    expect(content).not.toContain('PercentPipe');
  });

  it('emits @Component decorator with app-<kebab>-list selector + OnPush + row-enter styles + host class (no animations metadata)', () => {
    const content = gen.generate(domain({ entityName: 'OrderLine' }), CTX)!.content;

    expect(content).toContain("selector: 'app-order-line-list'");
    expect(content).toContain('standalone: true');
    expect(content).toContain('ChangeDetectionStrategy.OnPush');
    // B4: native animate.enter — keyframes live in component styles, not an animations: [] trigger array.
    expect(content).not.toContain('animations: [listAnimation]');
    expect(content).toContain('.row-enter { animation: row-enter-kf 200ms ease-out both; }');
    expect(content).toContain('@keyframes row-enter-kf {');
    expect(content).toContain("'class': 'block'");
  });

  // B4: native enter animation wiring on the row (animate.enter + CSS-delay stagger).
  it('rows use native animate.enter with an $index-driven stagger; tbody drops the trigger binding', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.id; let i = $index) {');
    expect(content).toContain('<tr animate.enter="row-enter" [style.animation-delay.ms]="i * 50"');
    expect(content).not.toContain('[@listAnimation]');
  });

  it('ListComponent class holds every loaded row and derives filtered, paged items from it', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('export class OrderListComponent implements OnInit {');
    expect(content).toContain('readonly rows = signal<Order[]>([]);');
    expect(content).toContain('readonly currentPage = signal(0);');
    expect(content).toContain('readonly pageSize = signal(20);');
    expect(content).toContain('readonly isLoading = signal(false);');
    expect(content).toContain('readonly error = signal<string | null>(null);');
    expect(content).toContain('readonly totalElements = computed(() => this.filtered().length);');
    expect(content).toContain('readonly totalPages = computed(() => Math.ceil(this.totalElements() / this.pageSize()));');
    expect(content).toContain('readonly page = computed(() => Math.min(this.currentPage(), Math.max(this.totalPages() - 1, 0)));');
    expect(content).toContain('return this.filtered().slice(start, start + this.pageSize());');
  });

  it('search is a signal the filtered rows read; no debounce plumbing', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'code', type: 'String' }), field({ name: 'note', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('(input)="onSearch(searchBox.value)"');
    expect(content).toContain("readonly searchQuery = signal('');");
    expect(content).toContain('const query = this.searchQuery().trim().toLowerCase();');
    // No field is marked searchable, so search covers the columns.
    expect(content).toContain("(query === '' || [item.code, item.note].some((value) => textOf(value).includes(query)))");
    expect(content).not.toContain('searchSubject');
    expect(content).not.toContain('debounceTime');
  });

  it('search covers only the searchable fields when any is marked', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'code', type: 'String', searchable: true }), field({ name: 'note', type: 'String' })],
    }), CTX)!.content;
    expect(content).toContain('[item.code].some((value) => textOf(value).includes(query))');
  });

  it('loadData asks for the whole collection and reads the array the server answers with', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('this.service.findAll().subscribe({');
    expect(content).toContain('this.rows.set(listRows(result));');
    expect(content).toContain('function listRows<T>(result: Page<T> | T[]): T[] {');
    expect(content).toContain('return Array.isArray(result) ? result : (result.content ?? []);');
    expect(content).not.toContain('page: this.currentPage()');
  });

  it('page size is a selector over 10 / 20 / 25 / 50 that returns to the first page', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain('data-testid="page-size"');
    for (const size of [10, 20, 25, 50]) {
      expect(content).toContain(`<option value="${size}" [selected]="pageSize() === ${size}">${size}</option>`);
    }
    expect(content).toContain('this.pageSize.set(Number(size));');
  });
});

describe('ListGenerator displayName / pluralName fallbacks', () => {
  const gen = new ListGenerator();

  it('pluralName falls back to <entityName>s; lowercased pluralName is used in template messaging', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    // Singular displayName 'Order' + plural fallback 'Orders'.
    expect(content).toContain('<h1 class="text-2xl font-bold tracking-tight text-gray-900 dark:text-white">Orders</h1>');
    expect(content).toContain('No orders yet');
    expect(content).toContain("Search orders...");
  });

  it('explicit displayName + pluralName both flow into emitted markup', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      displayName: 'Sales Order',
      pluralName: 'Sales Orders',
    }), CTX)!.content;

    expect(content).toContain('>Sales Orders</h1>');
    // The ternary in the emitted header gets both display+plural
    // lowercased at generation time.
    expect(content).toContain("'sales order' : 'sales orders'");
    expect(content).toContain('Get started by creating a new sales order.');
  });
});

// ---------- list column selection ----------

describe('ListGenerator list column selection', () => {
  const gen = new ListGenerator();

  function columnNamesIn(content: string): string[] {
    // Each emitted <th> spans across multiple lines; the easiest stable
    // marker is the click handler emitted only for sortable columns OR
    // the inline label inside the header span <span>{name}</span>.
    // Use the <span>Label</span> pattern present for every column.
    const matches = [...content.matchAll(/<span>([A-Z][A-Za-z0-9 ]*)<\/span>/g)];
    return matches.map(m => m[1]);
  }

  it('takes the first 5 non-hidden non-system fields', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        // System fields (always excluded from default selection)
        field({ name: 'id', type: 'UUID' }),
        field({ name: 'createdAt', type: 'Instant' }),
        field({ name: 'version', type: 'Long' }),
        // Hidden field (excluded)
        field({ name: 'secret', type: 'String', hidden: true }),
        // Six business fields (only first 5 selected — see slice(0,5))
        field({ name: 'orderNumber', type: 'String' }),
        field({ name: 'total', type: 'BigDecimal' }),
        field({ name: 'status', type: 'String' }),
        field({ name: 'paidAt', type: 'Instant' }),
        field({ name: 'notes', type: 'String' }),
        field({ name: 'sixth', type: 'String' }), // dropped — past slice(0, 5)
      ],
    }), CTX)!.content;

    const columns = columnNamesIn(content);
    expect(columns).toContain('Order Number');
    expect(columns).toContain('Total');
    expect(columns).toContain('Status');
    expect(columns).toContain('Paid At');
    expect(columns).toContain('Notes');
    expect(columns).not.toContain('Sixth');
    expect(columns).not.toContain('Id');
    expect(columns).not.toContain('Created At');
    expect(columns).not.toContain('Secret');
  });

  it('no fields → table renders with only the Actions header', () => {
    const content = gen.generate(domain({
      entityName: 'Empty',
    }), CTX)!.content;

    const columns = columnNamesIn(content);
    expect(columns).toEqual([]);
    // The Actions <th> sr-only label is always emitted.
    expect(content).toContain('<span class="sr-only">Actions</span>');
  });
});

// ---------- column-rendering matrix ----------

describe('ListGenerator per-column rendering matrix', () => {
  const gen = new ListGenerator();

  it('Boolean column renders Yes / No pill badges (no raw {{ value }})', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'flag', type: 'Boolean' })],
    }), CTX)!.content;

    expect(content).toContain('@if (item.flag)');
    expect(content).toContain('>Yes<');
    expect(content).toContain('>No<');
    expect(content).not.toContain('{{ item.flag }}');
  });

  it.each([
    ['Instant',                 'medium'],
    ['java.time.Instant',       'medium'],
    ['LocalDate',               'mediumDate'],
    ['java.time.LocalDate',     'mediumDate'],
    ['LocalDateTime',           'medium'],
    ['java.time.LocalDateTime', 'medium'],
    ['OffsetDateTime',          'medium'],
    ['ZonedDateTime',           'medium'],
  ])('temporal type %s → date pipe with %s pattern', (fieldType, expectedPattern) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'at', type: fieldType })],
    }), CTX)!.content;

    expect(content).toContain(`{{ item.at | date:'${expectedPattern}' }}`);
  });

  it('explicit format=datetime renders a String field with the date-time pipe', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'ts', type: 'String', format: 'datetime' })],
    }), CTX)!.content;
    expect(content).toContain("{{ item.ts | date:'medium' }}");
  });

  it.each(['boolean', 'Boolean', 'java.lang.Boolean'])('%s column renders the Yes / No badge', (type) => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'flag', type })],
    }), CTX)!.content;
    expect(content).toContain('@if (item.flag)');
    expect(content).toContain('>Yes<');
  });

  it('a numeric column is right-aligned with tabular digits and the number pipe', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'qty', type: 'Integer' })],
    }), CTX)!.content;
    expect(content).toContain('{{ item.qty | number }}');
    expect(content).toContain('px-6 py-3.5 text-right text-xs');
    expect(content).toContain('dark:text-gray-100 text-right tabular-nums">');
  });

  it('default column renders {{ item.<name> }} interpolation (no pipe)', () => {
    const content = gen.generate(domain({
      entityName: 'Thing',
      fields: [field({ name: 'title', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.title }}');
    expect(content).not.toContain('item.title | date');
  });
});

// ---------- @Field.dataType render facets (Wave 1A) ----------

describe('ListGenerator @Field.dataType render facets', () => {
  const gen = new ListGenerator();

  it("dataType 'currency' renders the | currency pipe", () => {
    const content = gen.generate(domain({
      entityName: 'Invoice',
      fields: [field({ name: 'amount', type: 'BigDecimal', dataType: 'currency' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.amount | currency }}');
  });

  it("dataType 'percent' renders the | percent pipe", () => {
    const content = gen.generate(domain({
      entityName: 'Stat',
      fields: [field({ name: 'rate', type: 'Double', dataType: 'percent' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.rate | percent }}');
  });

  it("dataType 'url' renders an <a [href]> anchor instead of raw interpolation", () => {
    const content = gen.generate(domain({
      entityName: 'Site',
      fields: [field({ name: 'homepage', type: 'String', dataType: 'url' })],
    }), CTX)!.content;

    expect(content).toContain('<a [href]="item.homepage"');
    expect(content).toContain('>{{ item.homepage }}</a>');
  });

  it('absent dataType keeps the default {{ item.<name> }} path (no pipe / anchor)', () => {
    const content = gen.generate(domain({
      entityName: 'Plain',
      fields: [field({ name: 'note', type: 'String' })],
    }), CTX)!.content;

    expect(content).toContain('{{ item.note }}');
    expect(content).not.toContain('| currency');
    expect(content).not.toContain('| percent');
    expect(content).not.toContain('[href]="item.note"');
  });
});

// ---------- sortable columns ----------

describe('ListGenerator sortable column markers', () => {
  const gen = new ListGenerator();

  it('sortable=true column gets click handler + aria-sort attribute + arrow SVG', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String', sortable: true })],
    }), CTX)!.content;

    expect(content).toContain("(click)=\"onSort('orderNumber')\"");
    expect(content).toContain("[attr.aria-sort]=\"sortField() === 'orderNumber'");
    expect(content).toContain("@if (sortField() === 'orderNumber')");
    expect(content).toContain('cursor-pointer select-none');
  });

  it('sortable=false (default) column gets NO click handler / NO aria-sort / NO arrow', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;

    expect(content).not.toContain("(click)=\"onSort('orderNumber')\"");
    expect(content).not.toContain('aria-sort');
  });
});

// ---------- systemFields.primaryKeyField alias ----------

describe('ListGenerator systemFields.primaryKeyField alias propagation', () => {
  const gen = new ListGenerator();

  it('default idField "id" used in track / data-testid attrs / sortField initial / delete dispatch', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.id; let i = $index)');
    expect(content).toContain("'row-' + item.id");
    expect(content).toContain('this.service.delete(String(item.id))');
  });

  it('a systemFields.primaryKeyField override does NOT move any of the 4 reference sites', () => {
    // A declared override must NOT move the emitted identity: nothing in the pipeline honours
    // `primaryKeyField` — Flyway emits `id UUID PRIMARY KEY`, the repository's clause is the
    // constant " WHERE id = ?", every by-id handler binds `{id}` — so an emitted app that
    // requested the override would talk to the wrong REST identifier.
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { primaryKeyField: 'uuid' },
    }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.id; let i = $index)');
    expect(content).toContain("'row-' + item.id");
    expect(content).toContain('this.service.delete(String(item.id))');
    expect(content).not.toContain('item.uuid');
  });
});

// ---------- sorting ----------

describe('ListGenerator client-side sorting', () => {
  const gen = new ListGenerator();

  it('a sortable column sorts the filtered rows; unsorted keeps the server order', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String', sortable: true })],
    }), CTX)!.content;

    expect(content).toContain("readonly sortField = signal<keyof Order | null>(null);");
    expect(content).toContain("readonly sortDirection = signal<'asc' | 'desc'>('asc');");
    expect(content).toContain('return [...rows].sort((a, b) => compareValues(a[field], b[field], direction));');
    expect(content).toContain('return this.sorted().slice(start, start + this.pageSize());');
    expect(content).toContain('function compareValues(a: unknown, b: unknown, direction: 1 | -1): number {');
    expect(content).toContain('onSort(field: keyof Order): void {');
  });

  it('no sortable column: no sort state, no comparator', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;
    expect(content).not.toContain('sortField');
    expect(content).not.toContain('compareValues');
  });
});

// ---------- filters ----------

describe('ListGenerator filters — every filterable field, client-side', () => {
  const gen = new ListGenerator();

  it('every filterable field gets a control: no first-two limit', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'active', type: 'Boolean', filterable: true }),
        field({ name: 'paid', type: 'boolean', filterable: true }),
        field({ name: 'shipped', type: 'java.lang.Boolean', filterable: true }),
      ],
    }), CTX)!.content;

    expect(content).toContain('data-testid="filter-active"');
    expect(content).toContain('data-testid="filter-paid"');
    expect(content).toContain('data-testid="filter-shipped"');
    expect(content).toContain("(activeFilter === '' || item.active === (activeFilter === 'true'))");
    expect(content).toContain("readonly filterActive = signal('');");
    expect(content).toContain('(change)="setFilter(filterActive, filterActiveControl.value)"');
  });

  it('a text field filters by contains, case-insensitively', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain('data-testid="filter-status"');
    expect(content).toContain('type="search"');
    expect(content).toContain("const statusFilter = this.filterStatus().trim().toLowerCase();");
    expect(content).toContain("(statusFilter === '' || textOf(item.status).includes(statusFilter))");
  });

  it('a date or date-time field filters by an inclusive day range', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'placedAt', type: 'java.time.Instant', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain('data-testid="filter-placedAt-from"');
    expect(content).toContain('data-testid="filter-placedAt-to"');
    expect(content).toContain('type="date"');
    expect(content).toContain('inDayRange(item.placedAt, this.filterPlacedAtFrom(), this.filterPlacedAtTo())');
    expect(content).toContain('function inDayRange(value: unknown, from: string, to: string): boolean {');
  });

  it('a numeric field filters by an inclusive range', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'priority', type: 'Integer', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain('data-testid="filter-priority-min"');
    expect(content).toContain('data-testid="filter-priority-max"');
    expect(content).toContain('inNumberRange(item.priority, this.filterPriorityMin(), this.filterPriorityMax())');
  });

  it('without a filterable field or search the filtered rows are the loaded rows', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      uiMetadata: { searchable: false },
      fields: [field({ name: 'status', type: 'String' })],
    }), CTX)!.content;
    expect(content).toContain('readonly filtered = computed(() => this.rows());');
    expect(content).not.toContain('setFilter');
    expect(content).not.toContain('WritableSignal');
  });
});

// ---------- enums ----------

describe('ListGenerator enum columns and filters', () => {
  const gen = new ListGenerator();
  const STATUS = {
    name: 'OrderStatus',
    qualifiedName: 'com.shop.OrderStatus',
    packageName: 'com.shop',
    values: [
      { name: 'NEW', displayName: 'New', ordinal: 0 },
      { name: 'PAID', displayName: 'Paid', ordinal: 1 },
    ],
  };
  const ctx = createGeneratorContext({}, [], [STATUS]);
  const order = (filterable: boolean) => domain({
    entityName: 'Order',
    fields: [field({ name: 'status', type: 'com.shop.OrderStatus', filterable })],
  });

  it('an emitted enum renders as a badge labelled by its display names, one tone per constant', () => {
    const content = gen.generate(order(false), ctx)!.content;
    expect(content).toContain("import { OrderStatusDisplayNames } from '../types/enums';");
    expect(content).toContain('protected readonly statusBadges: Readonly<Record<string, EnumBadge>> = {');
    expect(content).toContain("NEW: { label: OrderStatusDisplayNames.NEW, className: 'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset bg-blue-50");
    expect(content).toContain("PAID: { label: OrderStatusDisplayNames.PAID, className: 'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset bg-violet-50");
    expect(content).toContain('@let statusBadge = badgeOf(statusBadges, item.status);');
    expect(content).toContain('<span [class]="statusBadge.className">{{ statusBadge.label }}</span>');
  });

  it('a filterable enum filters by an exact constant, offered with its labels', () => {
    const content = gen.generate(order(true), ctx)!.content;
    expect(content).toContain('data-testid="filter-status"');
    expect(content).toContain(`<option value="NEW">{{ statusBadges['NEW'].label }}</option>`);
    expect(content).toContain("(statusFilter === '' || String(item.status) === statusFilter)");
  });

  it('a type no enum was emitted for renders as text and imports nothing from types/enums', () => {
    const content = gen.generate(order(false), CTX)!.content;
    expect(content).toContain('{{ item.status }}');
    expect(content).not.toContain('types/enums');
  });
});

// ---------- row actions ----------

describe('ListGenerator row actions', () => {
  const gen = new ListGenerator();

  it('offers each respond-once action that needs only the id; skips streaming and parameterised ones', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      actions: [
        { name: 'markPaid' },
        { name: 'ship-now' },
        { name: 'refund', params: [{ name: 'amount', type: 'BigDecimal' }] },
        { name: 'track', streaming: true },
      ],
    }), CTX)!.content;

    expect(content).toContain(`(click)="onMarkPaid(item)" [attr.data-testid]="'action-mark-paid-' + item.id"`);
    expect(content).toContain('>Mark Paid</button>');
    expect(content).toContain(`(click)="onShipNow(item)" [attr.data-testid]="'action-ship-now-' + item.id"`);
    expect(content).toContain('this.service.markPaid(String(item.id)).subscribe({');
    expect(content).toContain('this.service.shipNow(String(item.id)).subscribe({');
    expect(content).toContain('readonly actionError = signal<string | null>(null);');
    expect(content).not.toContain('onRefund');
    expect(content).not.toContain('onTrack');
  });

  it('no eligible action: no action error state', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).not.toContain('actionError');
  });
});

// ---------- generateList convenience ----------

describe('generateList — top-level convenience function', () => {
  it('returns the per-domain file for a visible domain', () => {
    const file = generateList(domain({ entityName: 'Order' }), CTX.config);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-list.component.ts');
  });

  it('falls back to KERNEL backend when config.backend is undefined (still emits per-domain file)', () => {
    const partialConfig = { ...CTX.config, backend: undefined as unknown as GeneratorContext['backend'] };
    const file = generateList(domain({ entityName: 'Order' }), partialConfig);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('components/order-list.component.ts');
  });
});

describe('ListGenerator default columns follow the system-field classification', () => {
  const gen = new ListGenerator();
  const columnNamesIn = (content: string): string[] =>
    [...content.matchAll(/<span>([A-Z][A-Za-z0-9 ]*)<\/span>/g)].map((m) => m[1]);

  it('leaves out the audit stamps an audited entity names through systemFields', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      audited: true,
      systemFields: { createdAtField: 'openedAt', updatedAtField: 'touchedAt' },
      fields: [
        field({ name: 'openedAt', type: 'Instant' }),
        field({ name: 'touchedAt', type: 'Instant' }),
        field({ name: 'orderNumber', type: 'String' }),
      ],
    }), CTX)!.content;
    expect(columnNamesIn(content)).toEqual(['Order Number']);
  });

  it('keeps a field an unaudited entity with a systemFields block does not name', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      systemFields: { versionField: 'revision' },
      fields: [
        field({ name: 'createdBy', type: 'String' }),
        field({ name: 'orderNumber', type: 'String' }),
      ],
    }), CTX)!.content;
    expect(columnNamesIn(content)).toEqual(['Created By', 'Order Number']);
  });
});
