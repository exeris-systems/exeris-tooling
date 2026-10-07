package eu.exeris.tooling.codegen.java;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import javax.lang.model.element.Modifier;
import java.net.URISyntaxException;
import java.util.Set;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DomainMetadata contract ({@code .agents/policies/domain-metadata-contract.md}), codegen
 * determinism ({@code codegen-determinism.md}) and the kernel-target ban
 * ({@code kernel-target-only.md}), checked against this module's compiled main classes.
 *
 * <p>The determinism rules match bytecode calls, not text: a generator that writes
 * {@code "$T.randomUUID()"} into emitted source calls nothing.
 *
 * <p>ArchUnit skips a class file it cannot read and reports the rules over what remains, so a
 * rule over an empty or partial import passes. The sentinel fails first in that case.
 */
@AnalyzeClasses(locations = CodegenJavaArchitectureTest.MainClasses.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
class CodegenJavaArchitectureTest {

    /** This module's main output directory, and nothing else on the test classpath. */
    static final class MainClasses implements LocationProvider {
        @Override
        public Set<Location> get(Class<?> testClass) {
            try {
                return Set.of(Location.of(CodegenPipeline.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ArchTest
    static void sentinel_import_contains_the_pipeline(JavaClasses classes) {
        assertThat(classes).isNotEmpty();
        assertThat(classes.contain(CodegenPipeline.class)).isTrue();
    }

    /**
     * DomainMetadata policy rule 2. Maven cannot hold this boundary: the language model and the
     * processing API ship with the JDK. {@code Modifier} is the one exception, a plain enum that
     * JavaPoet takes as its modifier type.
     */
    @ArchTest
    static final ArchRule reads_no_language_model = noClasses()
            .should().dependOnClassesThat(resideInAPackage("javax.lang.model..")
                    .and(DescribedPredicate.not(equivalentTo(Modifier.class))))
            .orShould().dependOnClassesThat().resideInAPackage("javax.annotation.processing..");

    /** Determinism policy rules 1 and 2: no clock and no randomness. */
    @ArchTest
    static final ArchRule reads_no_clock_and_no_randomness = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe(
                    "a clock or a random source", CodegenJavaArchitectureTest::isClockOrRandom))
            .orShould().callConstructorWhere(DescribedPredicate.describe(
                    "new Random or new Date", CodegenJavaArchitectureTest::constructsClockOrRandom));

    /** Kernel-target policy rule 1 and scoped bans: no host framework or DI container. */
    @ArchTest
    static final ArchRule no_host_framework = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.quarkus..",
                    "io.micronaut..",
                    "jakarta.inject..",
                    "javax.inject..");

    private static boolean isClockOrRandom(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        String name = call.getTarget().getName();
        return switch (owner) {
            case "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
                 "java.time.OffsetDateTime", "java.time.ZonedDateTime" -> name.equals("now");
            case "java.lang.System" -> name.equals("currentTimeMillis") || name.equals("nanoTime");
            case "java.util.UUID" -> name.equals("randomUUID");
            case "java.lang.Math", "java.lang.StrictMath" -> name.equals("random");
            case "java.util.concurrent.ThreadLocalRandom" -> name.equals("current");
            default -> false;
        };
    }

    private static boolean constructsClockOrRandom(JavaConstructorCall call) {
        String owner = call.getTargetOwner().getName();
        return owner.equals("java.util.Random") || owner.equals("java.security.SecureRandom")
                || (owner.equals("java.util.Date") && call.getTarget().getRawParameterTypes().isEmpty());
    }
}
