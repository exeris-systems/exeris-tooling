package eu.exeris.tooling.initializr;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;
import eu.exeris.tooling.initializr.cli.InitializrCliMain;

import java.net.URISyntaxException;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The initializr writes a project and runs none of it: its main code depends on the JDK and the
 * diagnostic registry alone, never on the code-generation pipeline, a capability, the kernel or a
 * host framework. The generated project reaches those through its own build.
 *
 * <p>ArchUnit skips a class file it cannot read and reports the rules over what remains, so a
 * rule over an empty or partial import passes. The sentinel fails first in that case.
 */
@AnalyzeClasses(locations = InitializrArchitectureTest.MainClasses.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
class InitializrArchitectureTest {

    /** This module's main output directory, and nothing else on the test classpath. */
    static final class MainClasses implements LocationProvider {
        @Override
        public Set<Location> get(Class<?> testClass) {
            try {
                return Set.of(Location.of(InitializrCliMain.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ArchTest
    static void sentinel_import_contains_the_entry_point(JavaClasses classes) {
        assertThat(classes).isNotEmpty();
        assertThat(classes.contain(InitializrCliMain.class)).isTrue();
    }

    @ArchTest
    static final ArchRule depends_only_on_the_jdk_and_the_diagnostic_registry = classes()
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "javax.lang.model..",
                    "eu.exeris.tooling.diagnostics..",
                    "eu.exeris.tooling.initializr..");

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
