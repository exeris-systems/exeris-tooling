/**
 * Escaping helper shared by the Angular emitters.
 *
 * Extracted when `saga-gen` became the fourth emitter to need it: `event-gen`, `detail-gen` and
 * `app-structure-gen` each carried a byte-identical private copy. Strong-default #2 (shared
 * scaffold, not copy-paste) applies to the TS side too.
 *
 * @author Exeris Team
 * @since 0.8.0
 */

/**
 * A TS single-quoted string literal body. The input is metadata — entity, event, step and app
 * names authored by a downstream consumer — so a quote or a backslash in it must not be able to
 * close or escape the literal the emitter is building.
 *
 * Newlines collapse to a space: a raw newline inside a single-quoted TS literal is a syntax
 * error, and every current caller emits a one-line label.
 */
export function tsSingleQuoted(s: string): string {
  return s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\r?\n/g, ' ');
}
