/**
 * Stable diagnostic identifiers shared by the annotation processor, the code-generation pipeline
 * and the Maven plugin (ADR-095).
 *
 * <p>Holds {@link eu.exeris.tooling.diagnostics.DiagnosticId} only, and depends on nothing
 * beyond {@code java.lang}, so a dependency on it adds nothing else to any module's dependency
 * set.
 *
 * @since 0.9.0
 */
package eu.exeris.tooling.diagnostics;
