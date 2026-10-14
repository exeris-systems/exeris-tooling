/**
 * The event and spectate payload interfaces name a field's type as the entity's own type does
 * (src/core/type-references.ts): another entity's read type, a declared enum, or `unknown`, and the
 * module imports what they name.
 */

import { describe, expect, it } from 'vitest';
import { EventHandlerGenerator } from '../../../src/generators/angular/event-gen.js';
import { StreamClientGenerator } from '../../../src/generators/angular/stream-client-gen.js';
import { createGeneratorContext, type GeneratorContext } from '../../../src/core/generator-registry.js';
import { DomainMetadataSchema, type DomainMetadata } from '../../../src/models/domain-model.js';

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

const ENUMS = [{ name: 'Priority', qualifiedName: 'com.shop.Priority', packageName: 'com.shop', values: [] }];

const FIELDS = [
  { name: 'id', type: 'java.util.UUID' },
  { name: 'customer', type: 'com.shop.Customer' },
  { name: 'priority', type: 'com.shop.Priority' },
  { name: 'total', type: 'com.shop.Money' },
  { name: 'watcher', type: 'com.shop.Watcher' },
];

function context(order: DomainMetadata, config: Parameters<typeof createGeneratorContext>[0] = {}): GeneratorContext {
  const domains = [order, domain({ entityName: 'Customer' }), domain({ entityName: 'Watcher' })];
  return { ...createGeneratorContext(config, domains, ENUMS), enumsComplete: true };
}

describe('EventHandlerGenerator — payload field types', () => {
  const gen = new EventHandlerGenerator();
  const order = domain({
    entityName: 'Order',
    fields: FIELDS,
    events: [{ name: 'OrderPlaced', payloadFields: ['id', 'customer', 'priority', 'total'] }],
  });
  const ctx = context(order, { eventBusEndpoint: '/orders/events' });

  it('types the payload as the entity does and imports what it names', () => {
    const content = gen.generate(order, ctx)!.content;
    expect(content).toContain([
      "import { EventBusService, DomainEvent } from './event-bus.service';",
      "import type { Priority } from '../types/enums';",
      "import type { Customer } from '../types/customer.types';",
      '',
    ].join('\n'));
    expect(content).toContain('    customer: Customer;');
    expect(content).toContain('    priority: Priority;');
    expect(content).toContain('    total: unknown;');
    expect(content).not.toContain('watcher.types');
  });

  it('imports nothing for a payload of JDK types', () => {
    const plain = domain({ entityName: 'Order', fields: FIELDS, events: [{ name: 'OrderPlaced', payloadFields: ['id'] }] });
    expect(gen.generate(plain, ctx)!.content).toContain("import { EventBusService, DomainEvent } from './event-bus.service';\n\n");
  });
});

describe('StreamClientGenerator — spectate payload field types', () => {
  const gen = new StreamClientGenerator();
  const order = domain({
    entityName: 'Order',
    realTimeApi: true,
    fields: FIELDS,
    events: [
      { name: 'OrderPlaced', payloadFields: ['id', 'customer', 'priority', 'total'] },
      // A second event of the same name is not rendered, so nothing it names is imported.
      { name: 'OrderPlaced', payloadFields: ['watcher'] },
    ],
  });
  const ctx = context(order);

  it('types the payload as the entity does and imports what the rendered payloads name', () => {
    const content = gen.generate(order, ctx)!.content;
    expect(content).toContain([
      "import { Observable } from 'rxjs';",
      "import type { Priority } from '../types/enums';",
      "import type { Customer } from '../types/customer.types';",
      '',
    ].join('\n'));
    expect(content).toContain('  customer: Customer;');
    expect(content).toContain('  total: unknown;');
    expect(content).not.toContain('watcher.types');
  });
});
