/**
 * A generated TypeScript event payload interface declares the fields the Java publisher sends and
 * no other. `exeris-e2e-tests/src/test/resources/contract/event-payload.json` holds one entity,
 * its events and the components each payload carries; `EventPayloadParityE2ETest` holds the Java
 * publisher against the same file.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildGeneratedFiles } from '../../../src/orchestrator.js';
import { EventHandlerGenerator } from '../../../src/generators/angular/event-gen.js';
import { createGeneratorContext } from '../../../src/core/generator-registry.js';
import { DEFAULT_CONFIG } from '../../../src/config.js';
import { DomainMetadataSchema } from '../../../src/models/domain-model.js';

interface Component {
  name: string;
  javaType: string;
  tsType: string;
}

interface Contract {
  entity: string;
  fields: { name: string; type: string }[];
  events: { name: string; payloadFields: string[]; sensitiveFields: string[]; components: Component[] }[];
}

// A missing file must fail the suite, not skip it.
const CONTRACT_URL = new URL(
  '../../../../exeris-e2e-tests/src/test/resources/contract/event-payload.json',
  import.meta.url,
);
const contract = JSON.parse(readFileSync(fileURLToPath(CONTRACT_URL), 'utf8')) as Contract;

const domain = DomainMetadataSchema.parse({
  packageName: 'eu.exeris.e2e.parity.domain',
  entityName: contract.entity,
  fields: contract.fields,
  events: contract.events.map(({ name, payloadFields, sensitiveFields }) => ({ name, payloadFields, sensitiveFields })),
  realTimeApi: true,
});

const files = buildGeneratedFiles([domain], [], DEFAULT_CONFIG);

// The event generator is not part of the default orchestration, so it runs on its own.
const eventContext = createGeneratorContext({ eventBusEndpoint: '/events' });
const eventGenerator = new EventHandlerGenerator();
const eventFiles = [
  eventGenerator.generate(domain, eventContext)!,
  ...eventGenerator.generateAggregate([domain], eventContext),
];

function content(suffix: string): string {
  const all = [...files, ...eventFiles];
  const file = all.find((f) => f.path.endsWith(suffix));
  expect(file, `${suffix} is emitted`).toBeDefined();
  return file!.content;
}

/** The members of `export interface <name> { ... }`, as `name: type`. */
function members(source: string, name: string): string[] {
  const body = new RegExp(`export interface ${name} \\{([^}]*)\\}`).exec(source);
  expect(body, `${name} is declared`).not.toBeNull();
  return body![1]
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('//'))
    .map((line) => line.replace(/;$/, ''));
}

describe('event payload parity: the TypeScript interface declares the Java payload record', () => {
  const events = content('events/order.events.ts');
  const spectate = content('services/order.stream.ts');

  for (const event of contract.events) {
    const expected = event.components.map((c) => `${c.name}: ${c.tsType}`);

    it(`${event.name}: the event payload interface declares exactly the contract components`, () => {
      expect(members(events, `${contract.entity}${event.name}Payload`)).toEqual(expected);
    });

    it(`${event.name}: the spectate payload interface declares exactly the contract components`, () => {
      expect(members(spectate, `${contract.entity}${event.name}SpectatePayload`)).toEqual(expected);
    });
  }

  it('an event interface declares no field the publisher does not send', () => {
    for (const absent of ['occurredAt', 'userId', 'correlationId']) {
      expect(events).not.toContain(`${absent}:`);
      expect(events).not.toContain(`${absent}?:`);
    }
    expect(content('events/event-bus.service.ts')).not.toMatch(/\b(occurredAt|userId|correlationId)\??:/);
  });

  it('a sensitive field is declared by no payload interface', () => {
    expect(events).not.toContain('customerEmail');
    expect(spectate).not.toContain('customerEmail');
  });
});
