package eu.exeris.tooling.codegen.maven;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import java.net.URISyntaxException;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kernel-target ban ({@code .agents/policies/kernel-target-only.md}), checked against this
 * module's compiled main classes.
 *
 * <p>ArchUnit skips a class file it cannot read and reports the rules over what remains, so a
 * rule over an empty or partial import passes. The sentinel fails first in that case.
 */
@AnalyzeClasses(locations = MavenPluginArchitectureTest.MainClasses.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
class MavenPluginArchitectureTest {

    /** This module's main output directory, and nothing else on the test classpath. */
    static final class MainClasses implements LocationProvider {
        @Override
        public Set<Location> get(Class<?> testClass) {
            try {
                return Set.of(Location.of(GenerateMojo.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ArchTest
    static void sentinel_import_contains_the_generate_mojo(JavaClasses classes) {
        assertThat(classes).isNotEmpty();
        assertThat(classes.contain(GenerateMojo.class)).isTrue();
    }

    /**
     * Kernel-target policy rule 1 and scoped bans: no host framework or DI container. Maven's own
     * container is reached through {@code maven-plugin-api}, never through {@code javax.inject}.
     */
    @ArchTest
    static final ArchRule no_host_framework = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.quarkus..",
                    "io.micronaut..",
                    "jakarta.inject..",
                    "javax.inject..");
}
