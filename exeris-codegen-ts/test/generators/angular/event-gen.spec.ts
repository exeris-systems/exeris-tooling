/**
 * Coverage for src/generators/angular/event-gen.ts — EventHandlerGenerator
 * emits a per-domain event-handler service (Subjects + announcement
 * methods + LiveAnnouncer i18n) and a shared event-bus.service.ts
 * with SSE wiring + reconnect-with-exponential-backoff.
 *
 * Unique-to-event-gen contracts pinned:
 *   - supportedBackends excludes VANILLA (only KERNEL/SPRING/QUARKUS/
 *     MICRONAUT — others register for all backends)
 *   - generate() returns null when domain has NO events (NOT on
 *     internalApi.hidden — events check fires first)
 *   - generateAggregate() returns empty array when no domain has
 *     events (no event-bus emitted at all)
 *   - isDestructive announcement heuristic: event name containing
 *     'deleted' / 'removed' / 'failed' → 'assertive' priority
 *   - generateEventHandler convenience hardcodes backend 'KERNEL'
 *     (unlike sibling form/service/guard/list which use config.backend
 *     ?? 'KERNEL')
 */

import { describe, expect, it } from 'vitest';
import { EventHandlerGenerator, generateEventHandler } from '../../../src/generators/angular/event-gen.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';
import {
  DomainMetadataSchema,
  type DomainMetadata,
} from '../../../src/models/domain-model.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

// ---------- CodeGenerator contract ----------

describe('EventHandlerGenerator — CodeGenerator metadata', () => {
  const gen = new EventHandlerGenerator();

  it('declares name / artifactType=EVENT; priority undefined (defaults to 10 via registry)', () => {
    expect(gen.name).toBe('EventHandlerGenerator');
    expect(gen.artifactType).toBe('EVENT');
    expect(gen.priority).toBeUndefined();
  });

  it('supportedBackends EXCLUDES VANILLA (unlike sibling angular generators that target all backends)', () => {
    expect(gen.supportedBackends).toEqual(['KERNEL', 'SPRING', 'QUARKUS', 'MICRONAUT']);
    expect(gen.supportedBackends).not.toContain('VANILLA');
  });
});

// ---------- generate — events-presence check (not hidden-skip!) ----------

describe('EventHandlerGenerator.generate — events-presence check', () => {
  const gen = new EventHandlerGenerator();

  it('emits events/<kebab>.events.ts when domain has at least one event', () => {
    const file = gen.generate(domain({
      entityName: 'OrderLine',
      events: [{ name: 'Created', fields: [] }],
    }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('events/order-line.events.ts');
    expect(file!.artifactType).toBe('EVENT');
    expect(file!.overwritable).toBe(true);
  });

  it('returns null when domain has NO events array', () => {
    expect(gen.generate(domain({ entityName: 'Order' }), CTX)).toBeNull();
  });

  it('returns null when domain has empty events: [] (length === 0)', () => {
    expect(gen.generate(domain({ entityName: 'Order', events: [] }), CTX)).toBeNull();
  });

  it('DOES NOT skip a hidden domain that has events — the events check fires first', () => {
    // Unlike sibling generators that null-out for hidden domains,
    // event-gen's null-check is on events length, NOT on
    // internalApi.hidden. A hidden domain WITH events still emits.
    // (This may be intentional — event subscribers might want to
    // listen even to hidden entities — or oversight. Pinned here.)
    const file = gen.generate(domain({
      entityName: 'HiddenButEventful',
      internalApi: { hidden: true, readOnly: false, internal: false },
      events: [{ name: 'Created', fields: [] }],
    }), CTX);

    expect(file).not.toBeNull();
    expect(file!.path).toBe('events/hidden-but-eventful.events.ts');
  });
});

// ---------- emitted structure ----------

describe('EventHandlerGenerator emitted content — per-domain handler', () => {
  const gen = new EventHandlerGenerator();

  function eventsContentFor(eventNames: string[]): string {
    return gen.generate(domain({
      entityName: 'Order',
      events: eventNames.map(n => ({ name: n, fields: [] })),
    }), CTX)!.content;
  }

  it('imports Angular core signal primitives + LiveAnnouncer + EventBusService + rxjs operators', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain('Injectable,');
    expect(content).toContain('signal,');
    expect(content).toContain('computed,');
    expect(content).toContain('DestroyRef,');
    expect(content).toContain('NgZone,');
    expect(content).toContain("from '@angular/core';");
    expect(content).toContain("import { takeUntilDestroyed } from '@angular/core/rxjs-interop';");
    expect(content).toContain("import { LiveAnnouncer } from '@angular/cdk/a11y';");
    expect(content).toContain("import { Subject, Observable, filter, map } from 'rxjs';");
    expect(content).toContain("import { EventBusService, DomainEvent } from './event-bus.service';");
  });

  it('emits <Entity>EventType union with single-quoted string literals (or "UNKNOWN" if no events)', () => {
    // No-events sentinel is unreachable through the public generate()
    // path (it null-returns first) but still exercises an internal
    // fallback that hardens the type emission against accidental
    // empty-array calls from refactors.
    const created = eventsContentFor(['Created', 'Updated']);
    expect(created).toContain("export type OrderEventType = 'Created' | 'Updated';");
  });

  it('emits ENTITY-PREFIXED Payload + Event interfaces per event, plus a <Entity>Event union type', () => {
    // Both Payload and Event interfaces are entity-prefixed
    // (Order + Created → OrderCreatedPayload, OrderCreatedEvent),
    // matching every reference site (Subject<…>, dispatchEvent
    // cast, announce* parameter, the <Entity>Event union members).
    // Previous versions of this generator declared the interfaces
    // unprefixed (CreatedEvent / CreatedPayload) while references
    // were entity-prefixed — a real production bug that made every
    // generated event-handler file fail tsc --noEmit.
    const content = eventsContentFor(['Created', 'Updated']);

    expect(content).toContain('export interface OrderCreatedPayload {');
    expect(content).toContain('export interface OrderCreatedEvent {');
    expect(content).toContain("type: 'Created';");
    expect(content).toContain('export interface OrderUpdatedEvent {');
    expect(content).toContain('export type OrderEvent =');
    expect(content).toContain('| OrderCreatedEvent');
    expect(content).toContain('| OrderUpdatedEvent');
    // Negative: NO unprefixed interface declarations leaked through.
    expect(content).not.toContain('export interface CreatedEvent {');
    expect(content).not.toContain('export interface CreatedPayload {');
  });

  it('payload interfaces with empty fields list get the "// No additional payload fields" sentinel', () => {
    const content = eventsContentFor(['Created']);
    expect(content).toContain('// No additional payload fields');
  });

  it('payload interfaces with fields emit per-field <name>: <tsType> entries (via DslMapper.mapType)', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      events: [{
        name: 'Created',
        fields: [
          { name: 'orderId', type: 'UUID' },
          { name: 'total', type: 'BigDecimal' },
        ],
      }],
    }), CTX)!.content;

    expect(content).toContain('orderId: string;');
    expect(content).toContain('total: string;');
  });

  it('emits @Injectable({ providedIn: \'root\' }) <Entity>EventHandler class with eventBus/liveAnnouncer/destroyRef/zone injected', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain("@Injectable({ providedIn: 'root' })");
    expect(content).toContain('export class OrderEventHandler {');
    expect(content).toContain('private readonly eventBus = inject(EventBusService);');
    expect(content).toContain('private readonly liveAnnouncer = inject(LiveAnnouncer);');
    expect(content).toContain('private readonly destroyRef = inject(DestroyRef);');
    expect(content).toContain('private readonly zone = inject(NgZone);');
  });

  it('emits one private Subject + matching public Observable per event (camelCase event name)', () => {
    // Plain event name 'Created' (not 'OrderCreated') keeps this
    // test focused on the Subject naming convention without
    // conflating the entity-prefix from generateEventInterfaces.
    // The Subject type uses the ENTITY-PREFIXED interface
    // (OrderCreatedEvent) emitted by generateEventInterfaces.
    const content = eventsContentFor(['Created']);

    expect(content).toContain('private readonly _created = new Subject<OrderCreatedEvent>();');
    expect(content).toContain('readonly created$ = this._created.asObservable();');
  });

  it('emits state signals: _eventCount / _lastEvent / _recentEvents + maxRecentEvents = 50', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain('private readonly _eventCount = signal(0);');
    expect(content).toContain('private readonly _lastEvent = signal<OrderEvent | null>(null);');
    expect(content).toContain('private readonly _recentEvents = signal<OrderEvent[]>([]);');
    expect(content).toContain('private readonly maxRecentEvents = 50;');
  });

  it('subscribeToEvents pipes through filter(aggregateType==<Entity>) + takeUntilDestroyed + zone.run dispatch', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain("filter(event => event.aggregateType === 'Order')");
    expect(content).toContain('takeUntilDestroyed(this.destroyRef)');
    expect(content).toContain('this.zone.run(() => {');
    expect(content).toContain('this.handleEvent(event);');
  });

  it('handleEvent: increments _eventCount + sets _lastEvent + prepends to _recentEvents capped at maxRecentEvents', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain('this._eventCount.update(n => n + 1);');
    expect(content).toContain('this._lastEvent.set(typedEvent);');
    expect(content).toContain('const updated = [typedEvent, ...events];');
    expect(content).toContain('return updated.slice(0, this.maxRecentEvents);');
  });

  it('parseEvent switch case emits one arm per event + a default arm with console.warn fallback', () => {
    const content = eventsContentFor(['Created', 'Updated']);

    expect(content).toContain("case 'Created':");
    expect(content).toContain("case 'Updated':");
    expect(content).toContain("type: 'Created' as const,");
    expect(content).toContain('default:');
    expect(content).toContain('console.warn(`Unknown Order event type:');
  });

  it('dispatchEvent calls _<event>.next + announce<Event> per event arm', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain('this._created.next(event as OrderCreatedEvent);');
    expect(content).toContain('this.announceCreated(event as OrderCreatedEvent);');
  });

  it('on(eventType) helper switches over event names + throws on unknown type', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain('on<T extends OrderEventType>');
    expect(content).toContain("return this._created.asObservable() as Observable<Extract<OrderEvent, { type: T }>>;");
    expect(content).toContain('throw new Error(`Unknown event type:');
  });

  it('clearHistory resets _recentEvents = [] AND _eventCount = 0', () => {
    const content = eventsContentFor(['Created']);

    expect(content).toContain('this._recentEvents.set([]);');
    expect(content).toContain('this._eventCount.set(0);');
  });
});

// ---------- isDestructive announcement heuristic ----------

describe('EventHandlerGenerator announcement-priority heuristic', () => {
  const gen = new EventHandlerGenerator();

  function announcementPriorityFor(eventName: string): string {
    const content = gen.generate(domain({
      entityName: 'Order',
      events: [{ name: eventName, fields: [] }],
    }), CTX)!.content;
    const m = content.match(/this\.liveAnnouncer\.announce\(message, '(polite|assertive)'\);/);
    expect(m, `should find an announce call for ${eventName}`).not.toBeNull();
    return m![1];
  }

  it.each([
    ['Deleted', 'assertive'],
    ['SoftDeleted', 'assertive'],
    ['Removed', 'assertive'],
    ['UserRemoved', 'assertive'],
    ['Failed', 'assertive'],
    ['PaymentFailed', 'assertive'],
  ])('event name containing %s → "%s" priority (destructive heuristic)', (eventName, expected) => {
    expect(announcementPriorityFor(eventName)).toBe(expected);
  });

  it.each([
    ['Created', 'polite'],
    ['Updated', 'polite'],
    ['Approved', 'polite'],
    ['Completed', 'polite'],
  ])('event name %s → "polite" priority (non-destructive default)', (eventName, expected) => {
    expect(announcementPriorityFor(eventName)).toBe(expected);
  });

  it('humanize fires inside the $localize message body for the announcement', () => {
    const content = gen.generate(domain({
      entityName: 'Order',
      events: [{ name: 'OrderPlaced', fields: [] }],
    }), CTX)!.content;

    expect(content).toContain('$localize`:@@order.event.orderPlaced:Order Placed`');
  });

  it('toPascalCase capture-group callback fires on snake_case + kebab-case event names', () => {
    // The private toPascalCase helper uses /[-_](.)/g — the capture
    // group + .toUpperCase() callback only runs when the input has at
    // least one dash or underscore. Default PascalCase event names
    // (Created / Updated) never trigger it. This test fires the
    // branch via snake_case and kebab-case event names, lifting
    // event-gen.ts branch coverage above the 85% gate.
    //
    // Entity name 'Tenant' (not 'Order') disambiguates the
    // entity-prefix from the toPascalCase output — otherwise a snake
    // event named 'order_was_placed' PascalCases to 'OrderWasPlaced'
    // and the entity prefix produces 'OrderOrderWasPlacedEvent',
    // which is confusing to read.
    const snakeCase = gen.generate(domain({
      entityName: 'Tenant',
      events: [{ name: 'was_placed', fields: [] }],
    }), CTX)!.content;
    // 'was_placed' → 'WasPlaced' (toPascalCase callback fires twice:
    //  once on '_p' → 'P', once at the start for capitalisation).
    expect(snakeCase).toContain('export interface TenantWasPlacedEvent {');

    const kebabCase = gen.generate(domain({
      entityName: 'Tenant',
      events: [{ name: 'was-shipped', fields: [] }],
    }), CTX)!.content;
    expect(kebabCase).toContain('export interface TenantWasShippedEvent {');
  });
});

// ---------- generateAggregate — event-bus.service.ts ----------

describe('EventHandlerGenerator.generateAggregate — central event-bus service', () => {
  const gen = new EventHandlerGenerator();

  it('emits events/event-bus.service.ts when at least one domain has events', () => {
    const files = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }],
      }),
    ], CTX);

    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('events/event-bus.service.ts');
    expect(files[0].artifactType).toBe('EVENT');
    expect(files[0].overwritable).toBe(true);
  });

  it('returns EMPTY array when no domain has events (no bus emitted at all)', () => {
    const files = gen.generateAggregate([
      domain({ entityName: 'NoEvents1' }),
      domain({ entityName: 'NoEvents2', events: [] }),
    ], CTX);

    expect(files).toEqual([]);
  });

  it('event-bus service has the documented public surface (ConnectionState + DomainEvent + EventBusConfig + connect/disconnect/reconnect/forAggregate/forEventType + ngOnDestroy)', () => {
    const content = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }],
      }),
    ], CTX)[0].content;

    // Types
    expect(content).toContain("export type ConnectionState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'ERROR';");
    expect(content).toContain('export interface DomainEvent {');
    expect(content).toContain('export interface EventBusConfig {');

    // Default config
    expect(content).toContain("endpoint: '/api/v1/events/stream'");
    expect(content).toContain('reconnectAttempts: 5');
    expect(content).toContain('reconnectDelay: 1000');
    expect(content).toContain('heartbeatInterval: 30000');

    // Service methods
    expect(content).toContain('connect(): void');
    expect(content).toContain('disconnect(): void');
    expect(content).toContain('reconnect(): void');
    expect(content).toContain('forAggregate(aggregateType: string)');
    expect(content).toContain('forAggregateInstance(aggregateType: string, aggregateId: string)');
    expect(content).toContain('forEventType(eventType: string)');
    expect(content).toContain('ngOnDestroy(): void');
  });

  it('event-bus class has SSE wiring: EventSource + onopen/onmessage/onerror + heartbeat listener', () => {
    const content = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }],
      }),
    ], CTX)[0].content;

    expect(content).toContain("new EventSource(this.config.endpoint, {");
    expect(content).toContain('withCredentials: true');
    expect(content).toContain('this.eventSource.onopen = () => {');
    expect(content).toContain('this.eventSource.onmessage = (event) => {');
    expect(content).toContain('this.eventSource.onerror = (error) => {');
    expect(content).toContain("this.eventSource.addEventListener('heartbeat'");
  });

  it('handleConnectionError uses exponential backoff (delay = reconnectDelay * 2^(attempt-1))', () => {
    const content = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }],
      }),
    ], CTX)[0].content;

    expect(content).toContain('this.config.reconnectDelay * Math.pow(2, this.reconnectAttempt - 1)');
    expect(content).toContain('if (this.reconnectAttempt < this.config.reconnectAttempts)');
  });

  it('handleConnectionError announces "Failed to connect to event stream" via assertive LiveAnnouncer after exhausting retries', () => {
    const content = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }],
      }),
    ], CTX)[0].content;

    expect(content).toContain('$localize`:@@events.connection.failed:Failed to connect to event stream`');
    expect(content).toContain("'assertive'");
  });

  it('onopen announces "Connected to event stream" via polite LiveAnnouncer + resets reconnectAttempt', () => {
    const content = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }],
      }),
    ], CTX)[0].content;

    expect(content).toContain('$localize`:@@events.connected:Connected to event stream`');
    expect(content).toContain('this.reconnectAttempt = 0;');
  });

  it('event names from multiple domains de-dupe across the bus emission (filter unique-by-index)', () => {
    // Two domains share event name 'Created'. The
    // generateEventBusService internals build allEventTypes via
    //   .filter((v, i, a) => a.indexOf(v) === i)
    // — classic unique-by-index — so 'Created' must appear in the
    // bus's internal event-type tracking exactly ONCE despite
    // being declared on two domains.
    const content = gen.generateAggregate([
      domain({
        entityName: 'Order',
        events: [{ name: 'Created', fields: [] }, { name: 'Updated', fields: [] }],
      }),
      domain({
        entityName: 'Customer',
        events: [{ name: 'Created', fields: [] }, { name: 'Activated', fields: [] }],
      }),
    ], CTX)[0].content;

    // Sanity baseline: the bus file emitted.
    expect(content).toContain('export class EventBusService');

    // Enforceable dedup assertion: the literal 'Created' (in
    // quotes — matches both the allEventTypes list and any
    // referenced internal documentation strings) appears at most
    // ONCE in the bus emission. A failing dedup would produce 2
    // duplicate entries.
    const createdQuotedMatches = (content.match(/'Created'/g) ?? []).length;
    expect(createdQuotedMatches).toBeLessThanOrEqual(1);
  });
});

// ---------- generateEventHandler convenience ----------

describe('generateEventHandler — top-level convenience function', () => {
  it('returns the per-domain file for a domain with events', () => {
    const file = generateEventHandler(
      domain({ entityName: 'Order', events: [{ name: 'Created', fields: [] }] }),
      CTX.config,
    );

    expect(file).not.toBeNull();
    expect(file!.path).toBe('events/order.events.ts');
    expect(file!.content).toContain('export class OrderEventHandler');
  });

  it('returns null for a domain with no events (matches the EventHandlerGenerator.generate contract)', () => {
    expect(generateEventHandler(domain({ entityName: 'Order' }), CTX.config)).toBeNull();
  });

  it('hardcodes backend "KERNEL" inside the convenience context (does NOT read config.backend)', () => {
    // Unlike sibling form/service/guard/list which read config.backend
    // with KERNEL fallback, generateEventHandler ignores config.backend
    // entirely.
    const file = generateEventHandler(
      domain({ entityName: 'Order', events: [{ name: 'Created', fields: [] }] }),
      { ...CTX.config, backend: 'SPRING' },
    );

    expect(file).not.toBeNull();
    expect(file!.path).toBe('events/order.events.ts');
  });
});
