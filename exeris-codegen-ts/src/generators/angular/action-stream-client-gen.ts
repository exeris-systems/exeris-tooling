/**
 * Angular Per-Action Stream Client Generator (per-action SSE streaming, ADR-044 Slice 2).
 *
 * Parity twin of the Java `KernelActionStreamHandlerGenerator`: for every
 * `@Action(streaming = true)` on an entity, the Java side emits a
 * `<Entity><ActionPascal>StreamHandler` registered at
 * `POST {base}/{id}/actions/{kebab(name)}` via the kernel router's
 * `streamRoute(...)` — the request OPENS the stream. This generator emits
 * the matching browser client.
 *
 * Why NOT a native EventSource (RFC-2026-06-22 Axis 4b): a per-action stream is
 * opened over POST (the action invocation IS the subscription) and may need
 * request headers; native `EventSource` is GET-only with no custom headers.
 * So the client is RxJS over `fetch(url, { method: 'POST', … })` +
 * `response.body.getReader()` (`ReadableStream`), parsing SSE frames
 * by hand and aborting the fetch on unsubscribe. Two client idioms coexist
 * (EventSource for entity-level live-view, RxJS-over-fetch for per-action),
 * justified by the transport limits and bounded by the shared route/producer
 * rules (ADR-044 §Trade-offs).
 *
 * Named-event honesty (ADR-044 obligation 2): unlike native EventSource's
 * `onmessage` (which drops named frames), this hand-rolled SSE parser reads
 * the `event:` line, so it dispatches the action's NAMED event
 * (`@Action.streamEventType`, or the action name when unset) — no silent
 * drops. The emitted `StreamFrame` carries the parsed `event` name so
 * the caller can demux; the JSDoc states this truthfully.
 *
 * Determinism (hard-constraint #3): no timestamps / UUIDs / random — same
 * `DomainMetadata` yields byte-identical output. Actions are emitted in
 * declared order; the route derivation matches the Java side and the TS
 * service-gen byte-for-byte (`apiBasePath + apiPath + /{id}/actions/{kebab}`).
 */

import { outPath } from '../../core/paths.js';
import type { ActionMetadata, DomainMetadata } from '../../models/index.js';
import { DslMapper } from '../../models/index.js';
import { isTenantPartitioned } from '../../models/domain-model.js';
import { tsSingleQuoted } from './ts-literal.js';
import type { GeneratorConfig } from '../../config.js';
import type { CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';

export { GeneratedFile };

/**
 * Whether the entity gets per-action stream clients (it declares a streaming action).
 *
 * Not for a tenant-partitioned entity: the kernel stream routes carry no tenant guard and the
 * handler's producer subscribes to the event bus unfiltered, so a tenant-partitioned entity's
 * stream would deliver every tenant's events to every subscriber. The client is emitted once the
 * server guards the route.
 */
export function hasActionStreamClients(domain: DomainMetadata): boolean {
  return !isTenantPartitioned(domain)
    && (domain.actions ?? []).some(a => a.streaming);
}

export class ActionStreamClientGenerator implements CodeGenerator {
  readonly name = 'ActionStreamClientGenerator';
  readonly artifactType = 'STREAM' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 6;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    // Driver parity with the Java side: only entities with at least one
    // @Action(streaming) action get a per-action stream client file. Hidden
    // internal APIs are excluded like every other Angular emitter.
    if (!hasActionStreamClients(domain)) {
      return null;
    }
    const streamingActions = this.streamingActions(domain);

    const content = this.renderActionStreamClients(domain, streamingActions, context);
    const fileName = `${DslMapper.toKebabCase(domain.entityName)}.action-streams.ts`;
    const filePath = outPath('services', fileName);

    return {
      path: filePath,
      content,
      artifactType: 'STREAM',
      overwritable: true,
    };
  }

  generateAggregate(domains: DomainMetadata[], context: GeneratorContext): GeneratedFile[] {
    const streamingDomains = domains.filter(hasActionStreamClients);
    if (streamingDomains.length === 0) {
      return [];
    }
    return [
      // StreamFrame lives in ONE shared module, imported by every per-entity
      // file. Emitting it per-entity (and `export *`-ing each) would make the
      // barrel re-export StreamFrame N times → TS2308 ambiguous re-export the
      // moment a second entity has a streaming action.
      {
        path: 'services/stream-types.ts',
        content: this.renderStreamTypes(),
        artifactType: 'STREAM',
        overwritable: true,
      },
      {
        path: 'services/action-streams.index.ts',
        content: this.renderBarrel(streamingDomains),
        artifactType: 'STREAM',
        overwritable: true,
      },
    ];
  }

  private streamingActions(domain: DomainMetadata): ActionMetadata[] {
    // Declared order — deterministic output (hard-constraint #3).
    return (domain.actions ?? []).filter(a => a.streaming);
  }

  private renderBarrel(domains: DomainMetadata[]): string {
    const lines: string[] = [
      `/**`,
      ` * Per-Action SSE Stream Clients - Barrel Export`,
      ` * Generated by @exeris/codegen-ts (ADR-044 Slice 2)`,
      ` * DO NOT EDIT - This file is auto-generated`,
      ` */`,
      ``,
      // Shared StreamFrame, re-exported once so barrel consumers still see it.
      `export * from './stream-types';`,
    ];
    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      lines.push(`export * from './${kebab}.action-streams';`);
    }
    return lines.join('\n');
  }

  /**
   * The shared `StreamFrame` type, emitted ONCE into
   * `services/stream-types.ts` and imported by every per-entity client
   * file. Single source → no ambiguous re-export across N streaming entities.
   */
  private renderStreamTypes(): string {
    return [
      `/**`,
      ` * Shared SSE stream types`,
      ` * Generated by @exeris/codegen-ts (ADR-044 Slice 2)`,
      ` * DO NOT EDIT - This file is auto-generated`,
      ` */`,
      ``,
      `/**`,
      ` * A parsed SSE frame. \`event\` is the SSE event: name (the action's`,
      ` * streamEventType, or 'keep-alive' for the scaffold heartbeat); \`data\` is the`,
      ` * raw data: payload. Unlike native EventSource.onmessage (which drops NAMED`,
      ` * frames), the per-action parser reads the event: line, so named events are`,
      ` * delivered.`,
      ` */`,
      `export interface StreamFrame {`,
      `  event: string;`,
      `  data: string;`,
      `}`,
      ``,
    ].join('\n');
  }

  /**
   * Builds the per-action SSE route, byte-for-byte parity with the kernel route
   * the Java handler is registered under: `{base}/{id}/actions/{kebab}`.
   * The `{base}` derivation mirrors the ServiceGenerator's `baseUrl`
   * (`apiBasePath + apiPath`); `{id}` is interpolated by the caller.
   *
   * `apiVersion` is deliberately NOT folded in — same reason, and same
   * miss, as `StreamClientGenerator.streamUrl`: the router registers no
   * version segment, so a domain declaring `@ExerisDomain(apiVersion = …)`
   * streamed to a path nothing serves.
   */
  private actionPath(domain: DomainMetadata, action: ActionMetadata, context: GeneratorContext): string {
    const pathSegment = domain.path ?? `/${DslMapper.routePlural(domain.entityName)}`;
    const apiPath = domain.apiPath ?? pathSegment;
    const kebabAction = DslMapper.toKebabCase(action.name);
    return `${context.config.apiBasePath}${apiPath}/${'${id}'}/actions/${kebabAction}`;
  }

  /** Mirrors Java NameCasing.pascal: camelCase method name, first char upper. */
  private pascal(name: string): string {
    const camel = DslMapper.toMethodName(name);
    return camel.charAt(0).toUpperCase() + camel.slice(1);
  }

  private renderActionStreamClients(
    domain: DomainMetadata,
    actions: ActionMetadata[],
    context: GeneratorContext,
  ): string {
    const entityName = domain.entityName;
    const lines: string[] = [];

    lines.push(`/**`);
    lines.push(` * ${entityName} Per-Action SSE Stream Clients`);
    lines.push(` * Generated by @exeris/codegen-ts (ADR-044 Slice 2)`);
    lines.push(` * DO NOT EDIT - This file is auto-generated`);
    lines.push(` *`);
    lines.push(` * One client per @Action(streaming) action. Each opens its stream over`);
    lines.push(` * POST (the action invocation IS the subscription) via fetch + ReadableStream`);
    lines.push(` * — native EventSource is GET-only and cannot carry POST/headers (Axis 4b).`);
    lines.push(` */`);
    lines.push(``);
    lines.push(`import { Injectable } from '@angular/core';`);
    lines.push(`import { Observable } from 'rxjs';`);
    // StreamFrame is shared (single source), not re-declared per entity, so the
    // barrel's `export *` cannot collide across N streaming entities (TS2308).
    lines.push(`import type { StreamFrame } from './stream-types';`);
    lines.push(``);

    for (const action of actions) {
      this.renderOneActionClient(domain, action, context, lines);
    }

    return lines.join('\n');
  }

  private renderOneActionClient(
    domain: DomainMetadata,
    action: ActionMetadata,
    context: GeneratorContext,
    lines: string[],
  ): void {
    const entityName = domain.entityName;
    const actionPascal = this.pascal(action.name);
    const className = `${entityName}${actionPascal}StreamClient`;
    const pathTemplate = this.actionPath(domain, action, context);
    // Named event: parity with the Java handler's STREAM_EVENT_TYPE — the
    // @Action.streamEventType, or the action name when unset (ADR-044 obligation 2).
    const eventName = action.streamEventType && action.streamEventType.length > 0
      ? action.streamEventType
      : action.name;

    lines.push(`@Injectable({ providedIn: 'root' })`);
    lines.push(`export class ${className} {`);
    lines.push(`  /** Named SSE event: emitted by the kernel ${entityName}${actionPascal}StreamHandler. */`);
    lines.push(`  static readonly STREAM_EVENT_TYPE = '${tsSingleQuoted(eventName)}';`);
    lines.push(``);
    lines.push(`  /**`);
    lines.push(`   * Opens the ${entityName}.${action.name}(...) per-action SSE stream and surfaces`);
    lines.push(`   * each parsed frame as a StreamFrame. The stream is opened over POST to`);
    lines.push(`   * ${pathTemplate.replace('${id}', '{id}')}`);
    lines.push(`   * (route-identical to the kernel streamRoute), credentials included for the`);
    lines.push(`   * kernel-edge auth (ADR-040). NAMED frames are delivered (frame.event ===`);
    lines.push(`   * '${eventName}' for domain events; 'keep-alive' for the heartbeat) — this`);
    lines.push(`   * hand-rolled parser reads the event: line, so nothing is silently dropped.`);
    lines.push(`   * The fetch is aborted when the subscription is torn down.`);
    lines.push(`   */`);
    lines.push(`  stream(id: string): Observable<StreamFrame> {`);
    lines.push(`    const url = \`${pathTemplate}\`;`);
    lines.push(`    return new Observable<StreamFrame>((subscriber) => {`);
    lines.push(`      const controller = new AbortController();`);
    lines.push(`      fetch(url, {`);
    lines.push(`        method: 'POST',`);
    lines.push(`        credentials: 'include',`);
    lines.push(`        signal: controller.signal,`);
    lines.push(`      })`);
    lines.push(`        .then((response) => {`);
    lines.push(`          if (!response.ok || !response.body) {`);
    lines.push(`            subscriber.error(new Error(\`Stream open failed: \${response.status}\`));`);
    lines.push(`            return;`);
    lines.push(`          }`);
    lines.push(`          const reader = response.body.getReader();`);
    lines.push(`          const decoder = new TextDecoder();`);
    lines.push(`          let buffer = '';`);
    lines.push(`          const pump = (): Promise<void> =>`);
    lines.push(`            reader.read().then(({ done, value }) => {`);
    lines.push(`              if (done) {`);
    lines.push(`                subscriber.complete();`);
    lines.push(`                return;`);
    lines.push(`              }`);
    lines.push(`              buffer += decoder.decode(value, { stream: true });`);
    lines.push(`              // SSE frames are separated by a blank line.`);
    lines.push(`              const chunks = buffer.split('\\n\\n');`);
    lines.push(`              buffer = chunks.pop() ?? '';`);
    lines.push(`              for (const chunk of chunks) {`);
    lines.push(`                let event = 'message';`);
    lines.push(`                const dataLines: string[] = [];`);
    lines.push(`                for (const line of chunk.split('\\n')) {`);
    lines.push(`                  // A field value drops exactly one leading space (the SSE`);
    lines.push(`                  // field rule); the rest of the value, trailing whitespace`);
    lines.push(`                  // included, is payload.`);
    lines.push(`                  const colon = line.indexOf(':');`);
    lines.push(`                  if (colon <= 0) {`);
    lines.push(`                    continue;`);
    lines.push(`                  }`);
    lines.push(`                  const field = line.slice(0, colon);`);
    lines.push(`                  const raw = line.slice(colon + 1);`);
    lines.push(`                  const value = raw.startsWith(' ') ? raw.slice(1) : raw;`);
    lines.push(`                  if (field === 'event') {`);
    lines.push(`                    event = value;`);
    lines.push(`                  } else if (field === 'data') {`);
    lines.push(`                    dataLines.push(value);`);
    lines.push(`                  }`);
    lines.push(`                }`);
    lines.push(`                if (dataLines.length === 0) {`);
    lines.push(`                  continue;`);
    lines.push(`                }`);
    lines.push(`                subscriber.next({ event, data: dataLines.join('\\n') });`);
    lines.push(`              }`);
    lines.push(`              return pump();`);
    lines.push(`            });`);
    lines.push(`          pump().catch((err) => {`);
    lines.push(`            // On unsubscribe the fetch aborts (AbortError); that is a clean`);
    lines.push(`            // teardown, so complete() — never error() — to honour the`);
    lines.push(`            // Observable contract (terminal signal on the stream's end).`);
    lines.push(`            if (controller.signal.aborted) {`);
    lines.push(`              subscriber.complete();`);
    lines.push(`            } else {`);
    lines.push(`              subscriber.error(err);`);
    lines.push(`            }`);
    lines.push(`          });`);
    lines.push(`        })`);
    lines.push(`        .catch((err) => {`);
    lines.push(`          if (controller.signal.aborted) {`);
    lines.push(`            subscriber.complete();`);
    lines.push(`          } else {`);
    lines.push(`            subscriber.error(err);`);
    lines.push(`          }`);
    lines.push(`        });`);
    lines.push(`      // Abort the fetch (and thus the stream) on unsubscribe.`);
    lines.push(`      return () => controller.abort();`);
    lines.push(`    });`);
    lines.push(`  }`);
    lines.push(`}`);
    lines.push(``);
  }
}

export function generateActionStreamClient(
  metadata: DomainMetadata,
  config: GeneratorConfig,
): GeneratedFile | null {
  const generator = new ActionStreamClientGenerator();
  const context: GeneratorContext = {
    config,
    backend: config.backend ?? 'KERNEL',
    allDomains: [metadata],
    enums: [],
  };
  return generator.generate(metadata, context);
}
