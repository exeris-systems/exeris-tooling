package eu.exeris.tooling.processor;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.LocationProvider;
import com.tngtech.archunit.lang.ArchRule;

import javax.annotation.processing.Messager;
import java.net.URISyntaxException;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build-time-only processor policy ({@code .agents/policies/processor-build-time-only.md}),
 * the processor half of {@code scoped-bans.md}, and the processor half of
 * {@code kernel-target-only.md}, checked against this module's compiled main classes.
 *
 * <p>ArchUnit skips a class file it cannot read and reports the rules over what remains, so a
 * rule over an empty or partial import passes. The sentinels fail first in that case: the import
 * must contain {@link ExerisDomainProcessor}, and the JDK supertypes the call rules match through
 * must resolve.
 */
@AnalyzeClasses(locations = ProcessorArchitectureTest.MainClasses.class,
        importOptions = ImportOption.DoNotIncludeTests.class)
class ProcessorArchitectureTest {

    /** This module's main output directory, and nothing else on the test classpath. */
    static final class MainClasses implements LocationProvider {
        @Override
        public Set<Location> get(Class<?> testClass) {
            try {
                return Set.of(Location.of(ExerisDomainProcessor.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ArchTest
    static void sentinel_import_contains_the_processor(JavaClasses classes) {
        assertThat(classes).isNotEmpty();
        assertThat(classes.contain(ExerisDomainProcessor.class)).isTrue();
    }

    @ArchTest
    static void sentinel_jdk_supertypes_resolve(JavaClasses classes) {
        assertThat(classes.get(ExerisDomainProcessor.class).getMethodCallsFromSelf())
                .as("a call to Throwable.getStackTrace on a subtype, matched through the hierarchy")
                .anyMatch(call -> isCall(call, Throwable.class, "getStackTrace"));
    }

    @ArchTest
    static void sentinel_the_printing_method_prints(JavaClasses classes) {
        assertThat(classes.get(ExerisDomainProcessor.class).getMethodCallsFromSelf())
                .anyMatch(call -> isCall(call, Messager.class, "printMessage")
                        && isPrintingMethod(call.getOrigin()));
    }

    /**
     * Policy rule 1, the permitted dependency set: the JDK (the language model, the processing
     * and compiler APIs, and the compiler tree API behind {@code Trees}), the SDK source model,
     * the diagnostic identifiers, {@code @AutoService} (rule 5), and Jackson for the
     * {@code DomainMetadata} write-out (rule 2). The SDK annotations are matched by name and never
     * referenced as types, so {@code eu.exeris.sdk.annotation} is outside the set.
     */
    @ArchTest
    static final ArchRule depends_only_on_the_build_time_set = classes()
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "javax.lang.model..",
                    "javax.annotation.processing..",
                    "javax.tools..",
                    "com.sun.source..",
                    "eu.exeris.sdk.sourcemodel..",
                    "eu.exeris.tooling.diagnostics..",
                    "eu.exeris.tooling.processor..",
                    "com.google.auto.service..",
                    "com.fasterxml.jackson..");

    /** Policy rule 4: diagnostics carry {@code e.toString()}; {@code getMessage()} can be null. */
    @ArchTest
    static final ArchRule never_calls_throwable_get_message = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe(
                    "Throwable.getMessage()",
                    call -> isCall(call, Throwable.class, "getMessage")
                            && call.getTarget().getRawParameterTypes().isEmpty()));

    /** Policy rule 4: one method prints, so every diagnostic carries a {@code DiagnosticId}. */
    @ArchTest
    static final ArchRule prints_only_from_the_printing_method = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe(
                    "Messager.printMessage outside ExerisDomainProcessor.print",
                    call -> isCall(call, Messager.class, "printMessage")
                            && !isPrintingMethod(call.getOrigin())));

    /** Policy rule 3: the processor reads the language model and never loads a consumer class. */
    @ArchTest
    static final ArchRule never_loads_classes = noClasses()
            .should().callMethodWhere(DescribedPredicate.describe(
                    "Class.forName, ClassLoader.loadClass or Thread.getContextClassLoader",
                    call -> isCall(call, Class.class, "forName")
                            || isCall(call, ClassLoader.class, "loadClass")
                            || isCall(call, Thread.class, "getContextClassLoader")));

    /** Kernel-target policy rule 1 and scoped bans: no host framework or DI container. */
    @ArchTest
    static final ArchRule no_host_framework = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "io.quarkus..",
                    "io.micronaut..",
                    "jakarta.inject..",
                    "javax.inject..");

    private static boolean isCall(JavaMethodCall call, Class<?> owner, String name) {
        return call.getTarget().getName().equals(name) && call.getTargetOwner().isAssignableTo(owner);
    }

    private static boolean isPrintingMethod(JavaCodeUnit codeUnit) {
        return codeUnit.getOwner().isEquivalentTo(ExerisDomainProcessor.class)
                && codeUnit.getName().equals("print");
    }
}
