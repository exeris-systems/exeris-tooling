/**
 * Exeris Java Code Generators.
 * <p>
 * Provides code generators for Java runtime components:
 * <ul>
 *   <li>{@code rest/} - REST controller generators</li>
 *   <li>{@code service/} - Service and Repository generators</li>
 *   <li>{@code openapi/} - OpenAPI specification generators</li>
 *   <li>{@code dsl/} - DSL JSON generators for frontend UI</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * This module is part of the Exeris Tooling layer (L2) and depends ONLY on SDK (L0).
 * Generated code may target different frameworks via {@link eu.exeris.tooling.codegen.core.PluggableBackend}.
 *
 * @since 0.1.0
 */
package eu.exeris.tooling.codegen.java;

