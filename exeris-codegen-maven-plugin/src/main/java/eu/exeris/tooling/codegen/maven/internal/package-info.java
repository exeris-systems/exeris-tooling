/**
 * Internal implementation of the Exeris codegen Maven plugin — <b>not API</b>.
 *
 * <p>Types here back the mojos in the parent package; the plugin is consumed via
 * its goals ({@code exeris:generate}, {@code exeris:detach}), never by importing
 * these classes. They may change or move in any release, including across the
 * 1.0.0 mojo-parameter API freeze, without a deprecation cycle.
 */
package eu.exeris.tooling.codegen.maven.internal;
