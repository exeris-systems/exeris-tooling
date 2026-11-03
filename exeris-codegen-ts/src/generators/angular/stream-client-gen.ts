/**
 * Angular Stream Client Generator (SSE live-view, ADR-043 Slice 1, ADR-044).
 *
 * Parity twin of the Java `KernelStreamHandlerGenerator`: for every
 * entity annotated `@ExerisDomain(realTimeApi = true)` the Java side emits
 * an `HttpStreamHandler` registered at `GET {base}/stream` via the
 * kernel router's `streamRoute(...)`. This generator emits the matching
 * browser client — a native `EventSource` (GET-only, no custom headers)
 * hitting the SAME `{base}/stream` route.
 *
 * Wire contract (ADR-044 obligations 2 and 5): every frame the handler emits is
 * NAMED. A domain event arrives as `event: <@DomainEvent name>` (the entity
 * name + `Event` when the name is blank) with the codec-encoded payload JSON
 * as `data:`; the keep-alive fallback, for an entity with no
 * `@DomainEvent`, sends `event: keep-alive` with empty data.
 * `EventSource.onmessage` never fires for a named frame, so the client
 * registers one `addEventListener` per declared event name and surfaces
 * each as a `MessageEvent` whose `type` is that name. The heartbeat
 * has no listener: it carries nothing to process.
 *
 * Spectate: the same client opens the Java `KernelSpectateStreamHandlerGenerator` route,
 * `GET {base}/{id}/stream`, through `spectate(id)`. It is an open-ended stream of one row's
 * events: the domain-event frames carry the event payload as JSON, which `spectate` parses and
 * types by the `@DomainEvent` that names the frame. The `keep-alive` frame is dropped. A
 * `stream-error` frame (a refusal or failure after the response head, whose data is an RFC 9457
 * problem object) closes the source and errors the Observable, because a source the server
 * closed would otherwise be reconnected by the browser into the same refusal. `message`, `open`
 * and `error` are the event types `EventSource` dispatches itself and no `@DomainEvent` may be
 * named like them or like a reserved frame.
 *
 * Reconnection: the server closes the stream on its own (the keep-alive fallback
 * closes after a fixed window, and a stream opened before the application is
 * composed is closed at once), and the browser reconnects on its own. An
 * `error` while the source is reconnecting is therefore not terminal; only
 * a source the browser has given up on (`readyState === CLOSED`) errors the
 * Observable.
 *
 * Transport (ADR-105): the kernel reads identity from an `Authorization: Bearer` header only, and a
 * native `EventSource` cannot send one. A stream route whose requirement row demands identity is
 * therefore opened with `fetch` instead, carrying `AuthService.token()`, and parsed by the reader
 * the per-action stream clients share (`sse-fetch-lines.ts`). The class, its members and their
 * types are the same either way, and so are the named-event, `stream-error`, reconnect and
 * teardown rules above. A public or undeclared stream route keeps `EventSource`.
 *
 * Deterministic: no timestamps / UUIDs / random — same `DomainMetadata`
 * yields byte-identical output; event names keep declaration order.
 */

import { outPath } from '../../core/paths.js';
import type { DomainMetadata } from '../../models/index.js';
import { DslMapper } from '../../models/index.js';
import { isTenantPartitioned } from '../../models/domain-model.js';
import { tsSingleQuoted } from './ts-literal.js';
import type { GeneratorConfig } from '../../config.js';
import type { CodeGenerator, GeneratedFile, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import { fileHeaderLines } from '../file-header.js';
import { mapScopedType, typeOnlyImports, typeScopeOf, type TypeScope } from '../../core/type-references.js';
import { deriveRouteRequirements, requirementFor, requiresIdentity } from '../../core/route-requirements.js';
import { bearerHeaderLines, ssePumpLines } from './sse-fetch-lines.js';

export { GeneratedFile };

/**
 * Whether the entity gets a stream client (the live view and the spectate stream).
 *
 * Not for a tenant-partitioned entity: the kernel event descriptor carries no isolation key, so
 * the collection-wide live view could not filter its events by tenant and would deliver every
 * tenant's events to every subscriber; the processor refuses `realTimeApi` on such an entity, and
 * the client is emitted once the event carries the key.
 */
export function hasLiveViewClient(domain: DomainMetadata): boolean {
  return domain.realTimeApi && !isTenantPartitioned(domain);
}

export class StreamClientGenerator implements CodeGenerator {
  readonly name = 'StreamClientGenerator';
  readonly artifactType = 'STREAM' as const;
  readonly supportedBackends: BackendType[] = [];
  readonly priority = 6;

  generate(domain: DomainMetadata, context: GeneratorContext): GeneratedFile | null {
    if (!hasLiveViewClient(domain)) {
      return null;
    }

    const content = this.renderStreamClient(domain, context);
    const fileName = `${DslMapper.toKebabCase(domain.entityName)}.stream.ts`;
    const filePath = outPath('services', fileName);

    return {
      path: filePath,
      content,
      artifactType: 'STREAM',
      overwritable: true,
    };
  }

  generateAggregate(domains: DomainMetadata[], _context: GeneratorContext): GeneratedFile[] {
    const streamingDomains = domains.filter(hasLiveViewClient);
    if (streamingDomains.length === 0) {
      return [];
    }
    return [
      {
        path: 'services/streams.index.ts',
        content: this.renderBarrel(streamingDomains),
        artifactType: 'STREAM',
        overwritable: true,
      },
    ];
  }

  private renderBarrel(domains: DomainMetadata[]): string {
    const lines: string[] = [
      ...fileHeaderLines({ title: 'SSE Stream Clients - Barrel Export', adr: 'ADR-043 Slice 1' }),
      ``,
    ];
    for (const domain of domains) {
      const kebab = DslMapper.toKebabCase(domain.entityName);
      lines.push(`export * from './${kebab}.stream';`);
    }
    return lines.join('\n');
  }

  /**
   * Builds the SSE route, byte-for-byte parity with the kernel route the Java
   * handler is registered under: `{base}/stream`. The `{base}`
   * derivation mirrors the ServiceGenerator's `baseUrl`
   * (`apiBasePath + apiPath`).
   *
   * `apiVersion` is deliberately NOT folded in. The router registers
   * `streamRoute` at `effectivePath() + "/stream"` with no version
   * segment, so a domain that declares `@ExerisDomain(apiVersion = …)`
   * must not include that version in the client's route.
   */
  private streamUrl(domain: DomainMetadata, context: GeneratorContext): string {
    const pathSegment = domain.path ?? `/${DslMapper.routePlural(domain.entityName)}`;
    const apiPath = domain.apiPath ?? pathSegment;
    return `${context.config.apiBasePath}${apiPath}/stream`;
  }

  /**
   * The SSE `event:` names the kernel handler emits for this entity, in
   * declaration order: the raw `@DomainEvent` name, or the entity name +
   * `Event` when the name is blank — the same choice
   * `KernelStreamHandlerGenerator.eventBindings` makes.
   */
  private streamEventNames(domain: DomainMetadata): string[] {
    const names: string[] = [];
    for (const event of domain.events ?? []) {
      const name = event.name.trim().length > 0 ? event.name : `${domain.entityName}Event`;
      if (!names.includes(name)) {
        names.push(name);
      }
    }
    return names;
  }

  /**
   * The types of the spectate stream: one payload interface per event name, the frame union
   * over them, and the error a `stream-error` frame raises. A payload names the fields the
   * `@DomainEvent` lists, typed from the entity's own fields; a name no field carries is
   * `unknown`, and a sensitive field is marked as redacted before publish. The type of every field
   * a payload names is added to `payloadTypes`.
   */
  private renderSpectateTypes(domain: DomainMetadata, scope: TypeScope, payloadTypes: string[]): string[] {
    const entityName = domain.entityName;
    const fieldByName = new Map(domain.fields.map(f => [f.name, f] as const));
    const lines: string[] = [];
    const members: string[] = [];
    const usedTypeNames = new Set<string>();
    const seen = new Set<string>();

    for (const event of domain.events ?? []) {
      const name = event.name.trim().length > 0 ? event.name : `${entityName}Event`;
      if (seen.has(name)) {
        continue;
      }
      seen.add(name);
      const pascal = name.replace(/[^A-Za-z0-9]+(.)?/g, (_, c: string | undefined) => (c ?? '').toUpperCase())
        .replace(/^(.)/, (_, c: string) => c.toUpperCase());
      let typeName = `${entityName}${pascal}SpectatePayload`;
      for (let n = 2; usedTypeNames.has(typeName); n++) {
        typeName = `${entityName}${pascal}SpectatePayload${n}`;
      }
      usedTypeNames.add(typeName);

      const sensitive = new Set(event.sensitiveFields ?? []);
      lines.push(`/** The payload of the '${tsSingleQuoted(name)}' frame of a ${entityName} spectate stream. */`);
      lines.push(`export interface ${typeName} {`);
      const payloadFields = event.payloadFields ?? [];
      if (payloadFields.length === 0) {
        lines.push(`  // No additional payload fields`);
      }
      for (const fieldName of payloadFields) {
        const field = fieldByName.get(fieldName);
        if (field) payloadTypes.push(field.type);
        const tsType = field ? mapScopedType(field.type, scope).tsType : 'unknown';
        const suffix = sensitive.has(fieldName) ? ' // sensitive: redacted before publish' : '';
        lines.push(`  ${fieldName}: ${tsType};${suffix}`);
      }
      lines.push(`}`);
      lines.push(``);
      members.push(`  | { readonly event: '${tsSingleQuoted(name)}'; readonly data: ${typeName} }`);
    }

    lines.push(`/** A domain-event frame of a ${entityName} spectate stream, discriminated by \`event\`. */`);
    if (members.length === 0) {
      lines.push(`export type ${entityName}SpectateFrame = never;`);
    } else {
      lines.push(`export type ${entityName}SpectateFrame =`);
      lines.push(members.join('\n') + ';');
    }
    lines.push(``);
    lines.push(`/**`);
    lines.push(` * The failure a 'stream-error' frame reports: the RFC 9457 problem object the stream`);
    lines.push(` * sends after its response head, with the \`status\` the by-id GET answers for the same`);
    lines.push(` * failure. A frame whose data is not a problem object leaves the members undefined.`);
    lines.push(` */`);
    lines.push(`export class ${entityName}SpectateError extends Error {`);
    lines.push(`  constructor(`);
    lines.push(`    message: string,`);
    lines.push(`    readonly status?: number,`);
    lines.push(`    readonly title?: string,`);
    lines.push(`    readonly detail?: string,`);
    lines.push(`  ) {`);
    lines.push(`    super(message);`);
    lines.push(`    this.name = '${entityName}SpectateError';`);
    lines.push(`  }`);
    lines.push(``);
    lines.push(`  static fromFrame(data: string): ${entityName}SpectateError {`);
    lines.push(`    try {`);
    lines.push(`      const problem = JSON.parse(data) as { status?: number; title?: string; detail?: string };`);
    lines.push(`      const message = problem.detail ?? problem.title ?? '${entityName} spectate stream failed';`);
    lines.push(`      return new ${entityName}SpectateError(message, problem.status, problem.title, problem.detail);`);
    lines.push(`    } catch {`);
    lines.push(`      return new ${entityName}SpectateError('${entityName} spectate stream failed');`);
    lines.push(`    }`);
    lines.push(`  }`);
    lines.push(`}`);
    lines.push(``);
    return lines;
  }

  private renderStreamClient(domain: DomainMetadata, context: GeneratorContext): string {
    const entityName = domain.entityName;
    const streamUrl = this.streamUrl(domain, context);
    const spectateTemplate = streamUrl.replace(/\/stream$/, '/${encodeURIComponent(id)}/stream');
    const eventNames = this.streamEventNames(domain);
    const rows = context.routeRequirements ?? deriveRouteRequirements(context.allDomains);
    const fetchLive = requiresIdentity(requirementFor(rows, entityName, 'GET', '{base}/stream'));
    const fetchSpectate = requiresIdentity(requirementFor(rows, entityName, 'GET', '{base}/{id}/stream'));
    const lines: string[] = [];

    lines.push(
      ...fileHeaderLines({
        title: `${entityName} SSE Live-View Stream Client`,
        adr: 'ADR-043 Slice 1',
        notes: [
          '',
          `Parity twin of the kernel ${entityName}StreamHandler, registered at`,
          ...this.transportNotes(streamUrl, fetchLive, fetchSpectate),
        ],
      }),
    );
    lines.push(``);
    const scope = typeScopeOf(context);
    const payloadTypes: string[] = [];
    const spectateTypes = this.renderSpectateTypes(domain, scope, payloadTypes);
    if (fetchLive || fetchSpectate) {
      lines.push(`import { Injectable, inject } from '@angular/core';`);
      lines.push(`import { Observable } from 'rxjs';`);
      lines.push(`import { AuthService } from '../core/auth.service';`);
    } else {
      lines.push(`import { Injectable } from '@angular/core';`);
      lines.push(`import { Observable } from 'rxjs';`);
    }
    // The enums and entities the spectate payloads name.
    lines.push(...typeOnlyImports(payloadTypes, scope, '../types'));
    lines.push(``);
    lines.push(...spectateTypes);
    lines.push(`@Injectable({ providedIn: 'root' })`);
    lines.push(`export class ${entityName}StreamClient {`);
    lines.push(`  /**`);
    lines.push(`   * The named SSE events the kernel ${entityName}StreamHandler emits: one per`);
    lines.push(`   * @DomainEvent, with the event payload JSON as data.`);
    if (eventNames.length === 0) {
      lines.push(`   * ${entityName} declares no @DomainEvent, so the handler sends only its`);
      lines.push(`   * 'keep-alive' heartbeat and this stream delivers no message.`);
    }
    lines.push(`   */`);
    lines.push(`  static readonly STREAM_EVENT_TYPES: readonly string[] = [${eventNames.map(n => `'${tsSingleQuoted(n)}'`).join(', ')}];`);
    lines.push(``);
    lines.push(`  private readonly streamUrl = '${streamUrl}';`);
    lines.push(``);
    lines.push(`  private spectateUrl(id: string): string {`);
    lines.push(`    return \`${spectateTemplate}\`;`);
    lines.push(`  }`);
    lines.push(``);
    if (fetchLive || fetchSpectate) lines.push(...this.renderConnect(entityName));
    lines.push(...(fetchLive ? this.renderFetchLiveView(entityName) : this.renderEventSourceLiveView(entityName)));
    lines.push(...(fetchSpectate
      ? this.renderFetchSpectate(entityName, spectateTemplate)
      : this.renderEventSourceSpectate(entityName, spectateTemplate)));
    lines.push(`}`);
    lines.push(``);

    return lines.join('\n');
  }

  /**
   * The header lines naming the transport. A native `EventSource` cannot send the bearer header,
   * so a stream route that demands an identity is opened with fetch.
   */
  private transportNotes(streamUrl: string, fetchLive: boolean, fetchSpectate: boolean): string[] {
    const registered = `GET ${streamUrl} via the router's streamRoute(...).`;
    if (fetchLive && fetchSpectate) {
      return [`${registered} Both stream routes`, 'demand an identity, so both are opened with fetch and the bearer token.'];
    }
    if (fetchLive || fetchSpectate) {
      return [
        `${registered} Uses the native`,
        'EventSource where a stream route needs no identity, and fetch with the',
        'bearer token where it does.',
      ];
    }
    return [`${registered} Uses the native`, 'EventSource (GET-only, no custom headers — auth via cookie/session).'];
  }

  /** `stream()` over a native `EventSource`, for a live view that needs no identity. */
  private renderEventSourceLiveView(entityName: string): string[] {
    const lines: string[] = [];
    lines.push(`  /**`);
    lines.push(`   * Opens the ${entityName} live-view SSE stream and surfaces each domain event`);
    lines.push(`   * as a MessageEvent: \`type\` is the event name (one of STREAM_EVENT_TYPES),`);
    lines.push(`   * \`data\` the payload JSON. Every server frame is named, so each name gets its`);
    lines.push(`   * own addEventListener — onmessage would see none of them. The 'keep-alive'`);
    lines.push(`   * heartbeat is not delivered. The browser reconnects when the server closes`);
    lines.push(`   * the stream; the Observable errors only once the EventSource gives up`);
    lines.push(`   * (readyState CLOSED). The EventSource is closed when the subscription is`);
    lines.push(`   * torn down.`);
    lines.push(`   */`);
    lines.push(`  stream(): Observable<MessageEvent<string>> {`);
    lines.push(`    return new Observable<MessageEvent<string>>((subscriber) => {`);
    lines.push(`      const source = new EventSource(this.streamUrl, { withCredentials: true });`);
    lines.push(`      const forward = (event: MessageEvent<string>): void => subscriber.next(event);`);
    lines.push(`      for (const type of ${entityName}StreamClient.STREAM_EVENT_TYPES) {`);
    lines.push(`        source.addEventListener(type, forward);`);
    lines.push(`      }`);
    lines.push(`      source.onerror = () => {`);
    lines.push(`        if (source.readyState === EventSource.CLOSED) {`);
    lines.push(`          subscriber.error(new Error(\`${entityName} stream closed: \${this.streamUrl}\`));`);
    lines.push(`        }`);
    lines.push(`      };`);
    lines.push(`      return () => source.close();`);
    lines.push(`    });`);
    lines.push(`  }`);
    lines.push(``);
    return lines;
  }

  /** `spectate(id)` over a native `EventSource`, for a spectate route that needs no identity. */
  private renderEventSourceSpectate(entityName: string, spectateTemplate: string): string[] {
    const lines: string[] = [];
    lines.push(`  /**`);
    lines.push(`   * Opens the SSE stream of one ${entityName} row, \`GET ${spectateTemplate.replace('${encodeURIComponent(id)}', '{id}')}\`,`);
    lines.push(`   * and surfaces each of its domain events as a frame: \`event\` is the @DomainEvent name`);
    lines.push(`   * and \`data\` the payload parsed from the frame's JSON. The 'keep-alive' heartbeat is`);
    lines.push(`   * dropped. A 'stream-error' frame closes the source and errors the Observable with a`);
    lines.push(`   * ${entityName}SpectateError, so the browser does not reconnect into the same refusal;`);
    lines.push(`   * so does a source the browser has given up on (readyState CLOSED). The stream has no`);
    lines.push(`   * end of its own: it runs until the subscription is torn down, which closes the`);
    lines.push(`   * EventSource.`);
    lines.push(`   */`);
    lines.push(`  spectate(id: string): Observable<${entityName}SpectateFrame> {`);
    lines.push(`    return new Observable<${entityName}SpectateFrame>((subscriber) => {`);
    lines.push(`      const source = new EventSource(this.spectateUrl(id), { withCredentials: true });`);
    lines.push(`      for (const type of ${entityName}StreamClient.STREAM_EVENT_TYPES) {`);
    lines.push(`        source.addEventListener(type, (event) => {`);
    lines.push(`          let data: unknown;`);
    lines.push(`          try {`);
    lines.push(`            data = JSON.parse((event as MessageEvent<string>).data);`);
    lines.push(`          } catch (err) {`);
    lines.push(`            source.close();`);
    lines.push(`            subscriber.error(err);`);
    lines.push(`            return;`);
    lines.push(`          }`);
    lines.push(`          subscriber.next({ event: type, data } as ${entityName}SpectateFrame);`);
    lines.push(`        });`);
    lines.push(`      }`);
    lines.push(`      source.addEventListener('stream-error', (event) => {`);
    lines.push(`        source.close();`);
    lines.push(`        subscriber.error(${entityName}SpectateError.fromFrame((event as MessageEvent<string>).data));`);
    lines.push(`      });`);
    lines.push(`      source.onerror = () => {`);
    lines.push(`        if (source.readyState === EventSource.CLOSED) {`);
    lines.push(`          subscriber.error(new Error(\`${entityName} spectate stream closed: \${this.spectateUrl(id)}\`));`);
    lines.push(`        }`);
    lines.push(`      };`);
    lines.push(`      return () => source.close();`);
    lines.push(`    });`);
    lines.push(`  }`);
    return lines;
  }

  /**
   * The private `connect` both fetch-based methods open their route with, and the reconnect delay
   * it waits. It reconnects where an `EventSource` would and gives up where one would.
   */
  private renderConnect(entityName: string): string[] {
    return [
      `  /** The wait before a stream the server ended, or whose connection failed, is opened again. */`,
      `  static readonly RECONNECT_DELAY_MS = 3000;`,
      ``,
      `  private readonly auth = inject(AuthService);`,
      ``,
      `  /**`,
      `   * Opens \`url\` with fetch, sending the signed-in user's token as \`Authorization: Bearer\`,`,
      `   * which an EventSource cannot send, and hands each frame to \`onFrame\`. A stream the server`,
      `   * ends, or a connection that fails, is opened again after RECONNECT_DELAY_MS with the token`,
      `   * held then, as an EventSource reconnects. A response that is not a 2xx stream calls \`fail\``,
      `   * and is not retried, as an EventSource gives up on it. \`onFrame\` returns false to close`,
      `   * the stream for good. The teardown returned aborts the fetch and cancels a pending`,
      `   * reconnect.`,
      `   */`,
      `  private connect(`,
      `    url: string,`,
      `    onFrame: (frame: { event: string; data: string }) => boolean,`,
      `    fail: () => void,`,
      `  ): () => void {`,
      `    let closed = false;`,
      `    let controller = new AbortController();`,
      `    let retry: ReturnType<typeof setTimeout> | undefined;`,
      `    const reopen = (): void => {`,
      `      if (!closed) {`,
      `        retry = setTimeout(open, ${entityName}StreamClient.RECONNECT_DELAY_MS);`,
      `      }`,
      `    };`,
      `    const open = (): void => {`,
      `      controller = new AbortController();`,
      ...bearerHeaderLines('      '),
      `      fetch(url, { credentials: 'include', headers, signal: controller.signal })`,
      `        .then((response) => {`,
      `          if (!response.ok || !response.body) {`,
      `            closed = true;`,
      `            fail();`,
      `            return;`,
      `          }`,
      ...ssePumpLines('          ', {
        onDone: ['reopen();'],
        onFrame: [
          `if (!onFrame({ event, data: dataLines.join('\\n') })) {`,
          `  closed = true;`,
          `  controller.abort();`,
          `  return;`,
          `}`,
        ],
      }),
      `          pump().catch(reopen);`,
      `        })`,
      `        .catch(reopen);`,
      `    };`,
      `    open();`,
      `    return () => {`,
      `      closed = true;`,
      `      clearTimeout(retry);`,
      `      controller.abort();`,
      `    };`,
      `  }`,
      ``,
    ];
  }

  /** `stream()` over `connect`, for a live view whose route demands identity. */
  private renderFetchLiveView(entityName: string): string[] {
    return [
      `  /**`,
      `   * Opens the ${entityName} live-view SSE stream with the bearer token and surfaces each`,
      `   * domain event as a MessageEvent: \`type\` is the event name (one of STREAM_EVENT_TYPES),`,
      `   * \`data\` the payload JSON. A frame of any other name, the 'keep-alive' heartbeat`,
      `   * included, is not delivered. The stream is opened again when the server closes it; the`,
      `   * Observable errors only when a response is refused. The fetch is aborted when the`,
      `   * subscription is torn down.`,
      `   */`,
      `  stream(): Observable<MessageEvent<string>> {`,
      `    return new Observable<MessageEvent<string>>((subscriber) =>`,
      `      this.connect(`,
      `        this.streamUrl,`,
      `        (frame) => {`,
      `          if (${entityName}StreamClient.STREAM_EVENT_TYPES.includes(frame.event)) {`,
      `            subscriber.next(new MessageEvent<string>(frame.event, { data: frame.data }));`,
      `          }`,
      `          return true;`,
      `        },`,
      `        () => subscriber.error(new Error(\`${entityName} stream closed: \${this.streamUrl}\`)),`,
      `      ),`,
      `    );`,
      `  }`,
      ``,
    ];
  }

  /** `spectate(id)` over `connect`, for a spectate route that demands identity. */
  private renderFetchSpectate(entityName: string, spectateTemplate: string): string[] {
    return [
      `  /**`,
      `   * Opens the SSE stream of one ${entityName} row, \`GET ${spectateTemplate.replace('${encodeURIComponent(id)}', '{id}')}\`,`,
      `   * with the bearer token, and surfaces each of its domain events as a frame: \`event\` is the`,
      `   * @DomainEvent name and \`data\` the payload parsed from the frame's JSON. The 'keep-alive'`,
      `   * heartbeat is dropped. A 'stream-error' frame closes the stream and errors the Observable`,
      `   * with a ${entityName}SpectateError, so it is not reopened into the same refusal; so does a`,
      `   * refused response. The stream has no end of its own: it is opened again when the server`,
      `   * closes it, and runs until the subscription is torn down, which aborts the fetch.`,
      `   */`,
      `  spectate(id: string): Observable<${entityName}SpectateFrame> {`,
      `    return new Observable<${entityName}SpectateFrame>((subscriber) =>`,
      `      this.connect(`,
      `        this.spectateUrl(id),`,
      `        (frame) => {`,
      `          if (frame.event === 'stream-error') {`,
      `            subscriber.error(${entityName}SpectateError.fromFrame(frame.data));`,
      `            return false;`,
      `          }`,
      `          if (!${entityName}StreamClient.STREAM_EVENT_TYPES.includes(frame.event)) {`,
      `            return true;`,
      `          }`,
      `          let data: unknown;`,
      `          try {`,
      `            data = JSON.parse(frame.data);`,
      `          } catch (err) {`,
      `            subscriber.error(err);`,
      `            return false;`,
      `          }`,
      `          subscriber.next({ event: frame.event, data } as ${entityName}SpectateFrame);`,
      `          return true;`,
      `        },`,
      `        () => subscriber.error(new Error(\`${entityName} spectate stream closed: \${this.spectateUrl(id)}\`)),`,
      `      ),`,
      `    );`,
      `  }`,
    ];
  }
}

export function generateStreamClient(metadata: DomainMetadata, config: GeneratorConfig): GeneratedFile | null {
  const generator = new StreamClientGenerator();
  const context: GeneratorContext = { config, backend: config.backend ?? 'KERNEL', allDomains: [metadata], enums: [] };
  return generator.generate(metadata, context);
}
