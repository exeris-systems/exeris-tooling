/**
 * Exeris Codegen Core module.
 * <p>
 * Provides shared infrastructure for code generation:
 * <ul>
 *   <li>Template engine abstraction</li>
 *   <li>Metadata loading from JSON</li>
 *   <li>Pluggable backend support for different frameworks</li>
 *   <li>Output writing utilities</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * This module is part of the Exeris Tooling layer (L2) and depends ONLY on SDK (L0).
 * It MUST NOT depend on Kernel (L1) or Platform (L3).
 *
 * @since 0.1.0
 */
package eu.exeris.tooling.codegen.core;

