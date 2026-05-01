/**
 * REST Controller generators.
 * <p>
 * Generates REST controllers from domain metadata:
 * <ul>
 *   <li>CRUD operations (list, get, create, update, delete)</li>
 *   <li>Custom action endpoints</li>
 *   <li>OpenAPI annotations</li>
 *   <li>Validation support</li>
 *   <li>Pagination</li>
 * </ul>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link eu.exeris.tooling.codegen.java.rest.RestControllerGenerator} - Main generator</li>
 *   <li>{@link eu.exeris.tooling.codegen.java.rest.CrudMethodsBuilder} - CRUD method generation</li>
 *   <li>{@link eu.exeris.tooling.codegen.java.rest.ActionMethodsBuilder} - Action method generation</li>
 * </ul>
 *
 * <h2>Pluggable Backend</h2>
 * Supports generating code for:
 * <ul>
 *   <li>Spring MVC (@RestController, @RequestMapping)</li>
 *   <li>Micronaut (@Controller)</li>
 *   <li>Quarkus/JAX-RS (@Path)</li>
 *   <li>Exeris Kernel (native transport)</li>
 * </ul>
 *
 * @since 0.1.0
 */
package eu.exeris.tooling.codegen.java.rest;

