/**
 * Exeris Java code generators — kernel-target only.
 * <p>
 * Subpackages:
 * <ul>
 *   <li>{@code kernel/} — Exeris kernel artifact generators (handler, service,
 *       repository, saga, events, application bootstrap, OpenAPI, Flyway)</li>
 *   <li>{@code openapi/} — OpenAPI 3.1 specification builders shared by
 *       {@code kernel/}</li>
 *   <li>{@code dsl/} — DSL JSON generators for Studio frontend UI</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * This module depends only on the SDK source model and the codegen-core
 * infrastructure. Detachment to community/enterprise tiers is a runtime
 * dependency swap, not a codegen variation.
 *
 * @since 0.1.0
 */
package eu.exeris.tooling.codegen.java;
