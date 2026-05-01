/**
 * Exeris Annotation Processor module.
 * <p>
 * Provides compile-time annotation processing for Exeris SDK annotations.
 * The processor extracts domain metadata from annotated classes and writes
 * JSON metadata files for use by code generators.
 *
 * <h2>Supported Annotations</h2>
 * <ul>
 *   <li>{@code @ExerisDomain} - Domain entity definition</li>
 *   <li>{@code @Saga} - Saga orchestration definition</li>
 *   <li>{@code @Action} - Domain action</li>
 *   <li>{@code @Field} - Entity field metadata</li>
 *   <li>{@code @Graph} - Graph entity definition</li>
 *   <li>{@code @Projection} - Read-only projection</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * This module is part of the Exeris Tooling layer (L2) and depends ONLY on SDK (L0).
 * It MUST NOT depend on Kernel (L1) or Platform (L3).
 *
 * <h2>Output</h2>
 * Generates JSON metadata files in {@code exeris-metadata/} directory:
 * <pre>
 * target/classes/
 *   exeris-metadata/
 *     Order.json
 *     Customer.json
 *     PaymentSaga.json
 * </pre>
 *
 * @since 0.1.0
 */
package eu.exeris.tooling.processor;

