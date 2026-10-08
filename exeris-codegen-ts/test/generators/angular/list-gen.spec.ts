/**
 * Coverage for src/generators/angular/list-gen.ts — ListGenerator emits a standalone, OnPush,
 * signal-driven list component that reads one page at a time from the list route, which pages,
 * sorts and filters on the server and answers a page envelope (ADR-096).
 *
 * Exercises:
 *   - list columns: the first 5 non-hidden non-system fields
 *   - displayName / pluralName fallbacks
 *   - systemFields.primaryKeyField alias in track / data-testid / delete dispatch
 *   - per-column rendering: boolean badge, enum badge, number / currency / percent right-aligned,
 *     date against date-time, url and foreign-key links
 *   - sortable headers and equality filters for exactly what the route reads, no search, page size
 *     within the route's bounds, paging from the envelope, and row actions
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

  it('reads one page through an rxResource keyed on the page, size, sort and filters', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'code', type: 'String', sortable: true, filterable: true }),
      ],
    }), CTX)!.content;

    expect(content).toContain('export class OrderListComponent {');
    expect(content).not.toContain('OnInit');
    expect(content).toContain("import { rxResource } from '@angular/core/rxjs-interop';");
    expect(content).toContain("import { Order, OrderService, Page, OrderFilter, OrderSortField } from '../services/order.service';");
    expect(content).toContain('readonly currentPage = signal(0);');
    expect(content).toContain('readonly pageSize = signal(20);');
    expect(content).toContain('readonly sortField = signal<OrderSortField | null>(null);');
    expect(content).toContain("readonly sortDirection = signal<'asc' | 'desc'>('asc');");
    expect(content).toContain([
      '  private readonly pageResource = rxResource({',
      '    params: () => {',
      '      const sort = this.sortField();',
      '      return {',
      '        request: {',
      '          page: this.currentPage(),',
      '          size: this.pageSize(),',
      '          ...(sort !== null ? { sort, direction: this.sortDirection() } : {}),',
      '        },',
      '        filter: this.filter(),',
      '      };',
      '    },',
      '    stream: ({ params }) => this.service.findAll(params.request, params.filter),',
      '  });',
    ].join('\n'));
  });

  it('shows the page envelope the route answers, holding the last one while the next loads', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;

    expect(content).toContain('private readonly result = linkedSignal<Page<Order> | undefined, Page<Order> | undefined>({');
    expect(content).toContain('    computation: (value, previous) => value ?? previous?.value,');
    expect(content).toContain('readonly items = computed(() => this.result()?.content ?? []);');
    expect(content).toContain('readonly totalElements = computed(() => this.result()?.totalElements ?? 0);');
    expect(content).toContain('readonly totalPages = computed(() => this.result()?.totalPages ?? 0);');
    expect(content).toContain('readonly page = computed(() => this.result()?.number ?? this.currentPage());');
    expect(content).toContain('readonly isFirst = computed(() => this.result()?.first ?? true);');
    expect(content).toContain('readonly isLast = computed(() => this.result()?.last ?? true);');
    expect(content).toContain('[disabled]="isFirst()"');
    expect(content).toContain('[disabled]="isLast()"');
    expect(content).toContain('Showing <span class="font-medium">{{ rangeStart() }}</span>');
    expect(content).toContain('readonly isLoading = computed(() => this.pageResource.isLoading());');
  });

  it('without a sortable column or a filter, the request is the page and size alone', () => {
    const content = gen.generate(domain({ entityName: 'Order', fields: [field({ name: 'code', type: 'String' })] }), CTX)!.content;

    expect(content).toContain([
      '    params: () => ({',
      '      request: { page: this.currentPage(), size: this.pageSize() },',
      '      filter: {},',
      '    }),',
    ].join('\n'));
    expect(content).not.toContain('sortField');
    expect(content).not.toContain('OrderFilter');
    expect(content).not.toContain('WritableSignal');
  });

  it('keeps no client-side paging, sorting, filtering or search', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'code', type: 'String', searchable: true, sortable: true, filterable: true }),
        field({ name: 'total', type: 'java.math.BigDecimal', sortable: true, filterable: true }),
        field({ name: 'placedOn', type: 'java.time.LocalDate', filterable: true }),
      ],
    }), CTX)!.content;

    for (const gone of [
      'readonly rows', 'readonly filtered', 'readonly sorted', 'listRows', 'textOf', 'inDayRange', 'inNumberRange',
      'compareValues', 'compareDecimals', '.slice(', 'search', 'Math.',
    ]) {
      expect(content, gone).not.toContain(gone);
    }
  });

  it('reloads the page after an action or a delete; the last row of a later page steps back a page', () => {
    const content = gen.generate(domain({ entityName: 'Order', actions: [{ name: 'cancel', params: [] }] }), CTX)!.content;

    expect(content).toContain('  loadData(): void {\n    this.pageResource.reload();\n  }');
    expect(content).toContain([
      '  private afterRemoval(): void {',
      '    const page = this.page();',
      '    if (this.items().length === 1 && page > 0) {',
      '      this.currentPage.set(page - 1);',
      '    } else {',
      '      this.pageResource.reload();',
      '    }',
      '  }',
    ].join('\n'));
    expect(content).toContain('        next: () => {\n          this.afterRemoval();\n        },');
    expect(content).toContain('      next: () => {\n        this.pageResource.reload();\n      },');
  });

  it('page size is a selector over 10 / 20 / 25 / 50 / 100 that returns to the first page and never leaves 1..100', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    expect(content).toContain('data-testid="page-size"');
    for (const size of [10, 20, 25, 50, 100]) {
      expect(content).toContain(`<option value="${size}" [selected]="pageSize() === ${size}">${size}</option>`);
    }
    expect(content).toContain('const MAX_PAGE_SIZE = 100;');
    expect(content).toContain([
      '  onPageSizeChange(size: string): void {',
      '    const value = Number(size);',
      '    if (Number.isInteger(value) && value >= 1 && value <= MAX_PAGE_SIZE) {',
      '      this.pageSize.set(value);',
      '      this.currentPage.set(0);',
      '    }',
      '  }',
    ].join('\n'));
  });
});

describe('ListGenerator displayName / pluralName fallbacks', () => {
  const gen = new ListGenerator();

  it('pluralName falls back to <entityName>s; lowercased pluralName is used in template messaging', () => {
    const content = gen.generate(domain({ entityName: 'Order' }), CTX)!.content;
    // Singular displayName 'Order' + plural fallback 'Orders'.
    expect(content).toContain('<h1 class="text-2xl font-bold tracking-tight text-gray-900 dark:text-white">Orders</h1>');
    expect(content).toContain('No orders yet');
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
    expect(content).toMatch(/<th\s+scope="col"\s+class="text-right"/);
    expect(content).toContain('<td class="text-right tabular-nums">');
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

  it('a column the route does not sort on has no sort control, whatever the field says', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      audited: true,
      fields: [
        field({ name: 'tags', type: 'java.util.List<java.lang.String>', sortable: true }),
        field({ name: 'placedAt', type: 'java.time.Instant', sortable: true }),
      ],
    }), CTX)!.content;

    expect(content).not.toContain("onSort('tags')");
    expect(content).toContain("onSort('placedAt')");
    expect(content).toContain("readonly sortField = signal<OrderSortField | null>(null);");
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

  it('a systemFields.primaryKeyField override moves every reference site to the named key', () => {
    // The row's key is the field primaryKeyField names (ADR-104); the routes keep their `:id`.
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNo', type: 'java.util.UUID' }), field({ name: 'name', type: 'String' })],
      systemFields: { primaryKeyField: 'orderNo' },
    }), CTX)!.content;

    expect(content).toContain('@for (item of items(); track item.orderNo; let i = $index)');
    expect(content).toContain("'row-' + item.orderNo");
    expect(content).toContain('[routerLink]="[item.orderNo]"');
    expect(content).toContain('this.service.delete(String(item.orderNo))');
    expect(content).not.toMatch(/\bitem\.id\b/);
  });
});

// ---------- sorting ----------

describe('ListGenerator server-side sorting', () => {
  const gen = new ListGenerator();

  it('a sort header sets the sorted property, flips the direction on a second click, and returns to the first page', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String', sortable: true })],
    }), CTX)!.content;

    expect(content).toContain([
      '  onSort(field: OrderSortField): void {',
      '    if (this.sortField() === field) {',
      "      this.sortDirection.update((d) => (d === 'asc' ? 'desc' : 'asc'));",
      '    } else {',
      '      this.sortField.set(field);',
      "      this.sortDirection.set('asc');",
      '    }',
      '    this.currentPage.set(0);',
      '  }',
    ].join('\n'));
  });

  it('no sortable column: no sort state', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'orderNumber', type: 'String' })],
    }), CTX)!.content;
    expect(content).not.toContain('sortField');
    expect(content).not.toContain('onSort');
  });
});

// ---------- filters ----------

describe('ListGenerator filters — the list route\'s equality filters', () => {
  const gen = new ListGenerator();

  it('every filter of the route gets a control: no first-two limit', () => {
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
    expect(content).toContain("    active: this.filterActive() === '' ? undefined : this.filterActive() === 'true',");
    expect(content).toContain("readonly filterActive = signal('');");
    expect(content).toContain('(change)="setFilter(filterActive, filterActiveControl.value)"');
    expect(content).toContain('<option value="true">Yes</option>');
  });

  it('a change to a filter returns to the first page', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain([
      '  setFilter(target: WritableSignal<string>, value: string): void {',
      '    target.set(value.trim());',
      '    this.currentPage.set(0);',
      '  }',
    ].join('\n'));
  });

  it('a string filter is a text input applied when committed, sent as typed', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain('data-testid="filter-status"');
    expect(content).toContain('          type="text"\n          (change)="setFilter(filterStatus, filterStatusControl.value)"');
    expect(content).toContain('    status: this.filterStatus() || undefined,');
  });

  it('a LocalDate filter is a date input', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'placedOn', type: 'java.time.LocalDate', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain('data-testid="filter-placedOn"');
    expect(content).toContain('          type="date"\n          (change)="setFilter(filterPlacedOn, filterPlacedOnControl.value)"');
    expect(content).toContain('    placedOn: this.filterPlacedOn() || undefined,');
  });

  it('a number filter is a text input with a numeric keyboard, sent as a number; a BigDecimal as its text', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [
        field({ name: 'priority', type: 'Integer', filterable: true }),
        field({ name: 'weight', type: 'double', filterable: true }),
        field({ name: 'total', type: 'java.math.BigDecimal', filterable: true }),
      ],
    }), CTX)!.content;
    expect(content).toContain('data-testid="filter-priority"');
    expect(content).toContain('          type="text"\n          inputmode="numeric"\n          (change)="setFilter(filterPriority, filterPriorityControl.value)"');
    expect(content).toContain('          inputmode="decimal"\n          (change)="setFilter(filterWeight, filterWeightControl.value)"');
    expect(content).toContain('          inputmode="decimal"\n          (change)="setFilter(filterTotal, filterTotalControl.value)"');
    expect(content).toContain('    priority: numberFilter(this.filterPriority()),');
    expect(content).toContain('    weight: numberFilter(this.filterWeight()),');
    expect(content).toContain('    total: this.filterTotal() || undefined,');
    expect(content).toContain('function numberFilter(text: string): number | undefined {');
    expect(content).toContain("  return text.trim() === '' || !Number.isFinite(value) ? undefined : value;");
  });

  it('an Instant, a LocalDateTime, a List, a system field and a hidden field get no control', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      audited: true,
      fields: [
        field({ name: 'placedAt', type: 'java.time.Instant', filterable: true }),
        field({ name: 'pickupAt', type: 'java.time.LocalDateTime', filterable: true }),
        field({ name: 'tags', type: 'java.util.List<java.lang.String>', filterable: true }),
        field({ name: 'createdAt', type: 'java.time.Instant', filterable: true }),
        field({ name: 'secret', type: 'String', filterable: true, hidden: true }),
      ],
    }), CTX)!.content;
    expect(content).not.toContain('data-testid="filter-');
    expect(content).not.toContain('setFilter');
    expect(content).not.toContain('-from"');
    expect(content).not.toContain('-min"');
  });

  it('a MANY_TO_ONE filters by its <base>Id, a text input, after the fields', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'code', type: 'String', filterable: true })],
      relationships: [{ name: 'customer', targetEntity: 'Customer', type: 'MANY_TO_ONE' }],
    }), CTX)!.content;
    expect(content).toContain('aria-label="Filter by Customer"');
    expect(content).toContain('data-testid="filter-customerId"');
    expect(content).toContain('    customerId: this.filterCustomerId() || undefined,');
    expect(content.indexOf('filter-code')).toBeLessThan(content.indexOf('filter-customerId'));
  });

  it('a filter row reports when the filters match nothing', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'code', type: 'String', filterable: true })],
    }), CTX)!.content;
    expect(content).toContain('readonly filtersActive = computed(() => Object.values(this.filter()).some((value) => value !== undefined));');
    expect(content).toContain('} @else if (filtersActive()) {\n              No orders match');
  });

  it('without a filter there is no filter row, state or setter', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      fields: [field({ name: 'status', type: 'String' })],
    }), CTX)!.content;
    expect(content).not.toContain('<!-- Filters -->');
    expect(content).not.toContain('setFilter');
    expect(content).not.toContain('WritableSignal');
    expect(content).not.toContain('filtersActive');
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
    expect(content).toContain("    status: (this.filterStatus() || undefined) as OrderFilter['status'],");
  });

  it('an enum the front cannot recognise is not a filter', () => {
    const content = gen.generate(order(true), CTX)!.content;
    expect(content).not.toContain('filter-status');
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
// ---------- kit component classes ----------

describe('ListGenerator styles its controls, table, actions and panels through the kit classes', () => {
  const gen = new ListGenerator();
  const STATUS = {
    name: 'OrderStatus',
    qualifiedName: 'com.shop.OrderStatus',
    packageName: 'com.shop',
    values: [{ name: 'NEW', displayName: 'New', ordinal: 0 }],
  };
  const content = gen.generate(domain({
    entityName: 'Order',
    fields: [
      field({ name: 'note', type: 'String', filterable: true }),
      field({ name: 'paid', type: 'Boolean', filterable: true }),
      field({ name: 'total', type: 'java.lang.Integer', filterable: true }),
      field({ name: 'status', type: 'com.shop.OrderStatus', filterable: true }),
    ],
    actions: [{ name: 'markPaid' }] as never,
  }), createGeneratorContext({}, [], [STATUS]))!.content;

  it('the table is an exeris-table in an exeris-card, and its cells carry only their alignment', () => {
    expect(content).toContain('<div class="exeris-card overflow-hidden">');
    expect(content).toContain('<table class="exeris-table" aria-label="Orders table" data-testid="data-table" [attr.aria-busy]="isLoading()" [class.opacity-60]="isLoading()">');
    expect(content).toContain('<thead>');
    expect(content).toContain('<tbody>');
    expect(content).toContain('<td class="text-right tabular-nums">');
    expect(content).not.toMatch(/<t[hd][^>]*\bpx-6\b/);
  });

  it('filters and the page size are kit fields', () => {
    expect(content).toMatch(/data-testid="filter-note"\s+class="exeris-input sm:w-auto"/);
    expect(content).toMatch(/data-testid="filter-paid"\s+class="exeris-select sm:w-auto"/);
    expect(content).toMatch(/data-testid="filter-total"\s+class="exeris-input sm:w-auto"/);
    expect(content).toMatch(/data-testid="filter-status"\s+class="exeris-select sm:w-auto"/);
    expect(content).toContain('data-testid="page-size" class="exeris-select w-auto"');
  });

  it('create, row actions, retry and paging are kit buttons, Delete the danger one', () => {
    expect(content).toMatch(/data-testid="action-create"\s+class="exeris-btn exeris-btn-primary"/);
    expect(content).toContain(`'action-view-' + item.id" class="exeris-btn exeris-btn-ghost exeris-btn-sm">View</a>`);
    expect(content).toContain(`'action-edit-' + item.id" class="exeris-btn exeris-btn-ghost exeris-btn-sm">Edit</a>`);
    expect(content).toContain(`'action-mark-paid-' + item.id" class="exeris-btn exeris-btn-ghost exeris-btn-sm">Mark Paid</button>`);
    expect(content).toContain(`'action-delete-' + item.id" class="exeris-btn exeris-btn-danger exeris-btn-sm">Delete</button>`);
    expect(content).toContain('(click)="loadData()" class="exeris-btn exeris-btn-secondary exeris-btn-sm mt-4">Try again</button>');
    expect(content).toMatch(/data-testid="pagination-prev"\s+class="exeris-btn exeris-btn-secondary"/);
    expect(content).toMatch(/data-testid="pagination-next"\s+class="exeris-btn exeris-btn-secondary"/);
  });

  it('the delete and action errors are danger alerts', () => {
    expect(content).toContain('data-testid="delete-error" class="exeris-alert exeris-alert-danger text-sm"');
    expect(content).toContain('data-testid="action-error" class="exeris-alert exeris-alert-danger text-sm"');
  });

  it('a boolean is a kit badge: success for Yes, the neutral surface for No', () => {
    expect(content).toContain('<span class="exeris-badge exeris-badge-success">Yes</span>');
    expect(content).toContain('<span class="exeris-badge bg-[rgb(var(--exeris-bg-tertiary))] text-[rgb(var(--exeris-text-secondary))]">No</span>');
  });

  it('an enum keeps its per-constant palette rather than a semantic kit badge', () => {
    expect(content).toContain("NEW: { label: OrderStatusDisplayNames.NEW, className: 'inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset bg-blue-50");
  });
});
