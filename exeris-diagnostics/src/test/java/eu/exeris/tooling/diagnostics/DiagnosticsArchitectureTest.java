package eu.exeris.tooling.diagnostics;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import java.net.URISyntaxException;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This module sits inside the processor's closed dependency set
 * ({@code .agents/policies/processor-build-time-only.md} rule 1), so it depends on the JDK alone;
 * the kernel-target ban ({@code kernel-target-only.md}) holds here as in every tooling module.
 *
 * <p>ArchUnit skips a class file it cannot read and reports the rules over what remains, so a
 * rule over an empty or partial import passes. The sentinel fails first in that case.
 */
@AnalyzeClasses(locations = DiagnosticsArchitectureTest.MainClasses.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
class DiagnosticsArchitectureTest {

    /** This module's main output directory, and nothing else on the test classpath. */
    static final class MainClasses implements LocationProvider {
        @Override
        public Set<Location> get(Class<?> testClass) {
            try {
                return Set.of(Location.of(DiagnosticId.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ArchTest
    static void sentinel_import_contains_the_identifiers(JavaClasses classes) {
        assertThat(classes).isNotEmpty();
        assertThat(classes.contain(DiagnosticId.class)).isTrue();
    }

    @ArchTest
    static final ArchRule depends_only_on_the_jdk = classes()
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "eu.exeris.tooling.diagnostics..");

    /** Kernel-target policy rule 1 and scoped bans: no host framework or DI container. */
    @ArchTest
    static final ArchRule no_host_framework = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.quarkus..",
                    "io.micronaut..",
                    "jakarta.inject..",
                    "javax.inject..");
}
