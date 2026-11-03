/**
 * The SSE frame reader every fetch-based stream client emits, written once.
 *
 * A client that opens its stream with `fetch` reads `response.body` itself, because only a native
 * `EventSource` parses SSE for the caller. The per-action stream clients and an identity-requiring
 * live view both need that reader; it is generated here so the two cannot parse the wire
 * differently. The emitted code declares `pump`, which reads the body to its end; the caller emits
 * the `response` binding before it and the `pump()` call after it.
 *
 * Frames are separated by a blank line. A field value drops exactly one leading space (the SSE
 * field rule) and keeps the rest, trailing whitespace included. A frame with no `event:` line is
 * named `message`, and a frame with no `data:` line is skipped.
 */

/** Statements the caller places inside the reader, written at the indentation they land at. */
export interface SsePumpHooks {
  /** Run once the body has ended. */
  readonly onDone: readonly string[];
  /**
   * Run per frame, with `event` (the frame's name) and `dataLines` (its data lines) in scope. A
   * `return;` among them stops the reader.
   */
  readonly onFrame: readonly string[];
}

/** The reader's lines, at `indent`: the `reader`, `decoder` and `buffer` state and the `pump` arrow. */
export function ssePumpLines(indent: string, hooks: SsePumpHooks): string[] {
  const body = [
    `const reader = response.body.getReader();`,
    `const decoder = new TextDecoder();`,
    `let buffer = '';`,
    `const pump = (): Promise<void> =>`,
    `  reader.read().then(({ done, value }) => {`,
    `    if (done) {`,
    ...hooks.onDone.map((line) => `      ${line}`),
    `      return;`,
    `    }`,
    `    buffer += decoder.decode(value, { stream: true });`,
    `    // SSE frames are separated by a blank line.`,
    `    const chunks = buffer.split('\\n\\n');`,
    `    buffer = chunks.pop() ?? '';`,
    `    for (const chunk of chunks) {`,
    `      let event = 'message';`,
    `      const dataLines: string[] = [];`,
    `      for (const line of chunk.split('\\n')) {`,
    `        // A field value drops exactly one leading space (the SSE`,
    `        // field rule); the rest of the value, trailing whitespace`,
    `        // included, is payload.`,
    `        const colon = line.indexOf(':');`,
    `        if (colon <= 0) {`,
    `          continue;`,
    `        }`,
    `        const field = line.slice(0, colon);`,
    `        const raw = line.slice(colon + 1);`,
    `        const value = raw.startsWith(' ') ? raw.slice(1) : raw;`,
    `        if (field === 'event') {`,
    `          event = value;`,
    `        } else if (field === 'data') {`,
    `          dataLines.push(value);`,
    `        }`,
    `      }`,
    `      if (dataLines.length === 0) {`,
    `        continue;`,
    `      }`,
    ...hooks.onFrame.map((line) => `      ${line}`),
    `    }`,
    `    return pump();`,
    `  });`,
  ];
  return body.map((line) => `${indent}${line}`);
}

/**
 * The request headers of a stream route that demands identity, at `indent`: `headers` carries
 * `Authorization: Bearer` with `this.auth.token()` while a token is held, and is empty otherwise.
 * The emitting class declares `auth`, the injected `AuthService`.
 */
export function bearerHeaderLines(indent: string): string[] {
  return [
    `${indent}const token = this.auth.token();`,
    `${indent}const headers: Record<string, string> = token === null ? {} : { Authorization: \`Bearer \${token}\` };`,
  ];
}
