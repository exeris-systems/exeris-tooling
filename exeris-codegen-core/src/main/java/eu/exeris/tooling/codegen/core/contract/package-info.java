/**
 * The contract artifact a backend publishes and a consumer reads (ADR-048 Amendment 3).
 *
 * <p>The directory layout is defined once, by {@link eu.exeris.tooling.codegen.core.contract.ContractLayout};
 * the reader and the resolver apply the one set of refusals to a directory, a jar and a Maven coordinate.
 *
 * @since 0.12
 */
package eu.exeris.tooling.codegen.core.contract;
