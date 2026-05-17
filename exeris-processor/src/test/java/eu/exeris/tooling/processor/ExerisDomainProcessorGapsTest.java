package eu.exeris.tooling.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the remaining coverage gaps in {@link ExerisDomainProcessor} that
 * {@link ExerisDomainProcessorTest} and {@link ExerisDomainProcessorBranchTest}
 * leave behind. Two areas are uncovered by the existing suites:
 *
 * <ol>
 *   <li><b>{@code extractEventsMetadata} single-annotation + nested-class
 *       paths.</b> The existing {@code DomainEventTriggerSuffixTests} uses
 *       6 {@code @DomainEvent} annotations on a single class, which javac
 *       collapses into a single {@code @DomainEvents} container — the
 *       repeatable container path is the only one that fires. This class
 *       exercises (a) the single-{@code @DomainEvent} direct branch and
 *       (b) the legacy nested-class path that walks {@code getEnclosedElements()}
 *       for inner-class events.</li>
 *   <li><b>{@code applyDeprecatedValidationFallbacks}.</b> The deprecated
 *       {@code @Validation.required} and {@code @Validation.validateOn}
 *       attributes still fall back to canonical {@code @Field} properties
 *       and emit deprecation warnings. The fallback paths and the
 *       "validateOn unrecognised" warning had zero coverage.</li>
 * </ol>
 *
 * <p>Structural residual not covered (documented for the next reader):
 * {@code reportProcessingFailure} (lines 1003-1014) is unreachable
 * through the public annotation-processing API without a deliberately
 * faulted processor or a visibility-relaxing test-only refactor; the
 * residual is left as a documented gap rather than papered over with a
 * synthetic exception.
 */
@DisplayName("ExerisDomainProcessor — remaining coverage gaps")
class ExerisDomainProcessorGapsTest {

    // ---------- extractEventsMetadata: single-@DomainEvent direct branch ----------

    @Nested
    @DisplayName("@DomainEvent direct branch (single annotation, not container)")
    class SingleDomainEventTests {

        @Test
        @DisplayName("Single @DomainEvent on a class is read via the direct branch (not the @DomainEvents container)")
        void singleDomainEventDirectBranch() throws IOException {
            // Exactly one @DomainEvent → javac does NOT synthesise the
            // @DomainEvents container, so extractEventsMetadata takes the
            // single-annotation branch at line 657.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.SingleEvent",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.DomainEvent;
                    import eu.exeris.sdk.annotation.DomainEvent.Trigger;

                    @ExerisDomain(module = "sales", path = "/single-event")
                    @DomainEvent(trigger = Trigger.CREATE, topic = "se.created")
                    public class SingleEvent {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "SingleEvent");
            assertThat(json)
                    .contains("\"SingleEventCreatedEvent\"")
                    .contains("\"se.created\"");
        }

        @Test
        @DisplayName("@DomainEvent with explicit name=\"...\" uses it verbatim; description + topic propagate")
        void singleDomainEventWithExplicitNameAndDescription() throws IOException {
            // Exercises the `values.containsKey("name")` true branch +
            // the `description` containsKey-true branch of
            // extractSingleEventMetadata.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.ExplicitNameEvent",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.DomainEvent;
                    import eu.exeris.sdk.annotation.DomainEvent.Trigger;

                    @ExerisDomain(module = "billing", path = "/explicit-name")
                    @DomainEvent(
                            name = "InvoiceFinalised",
                            trigger = Trigger.ACTION,
                            action = "finalise",
                            topic = "billing.invoice.finalised",
                            description = "Fired once an invoice transitions to FINAL")
                    public class ExplicitNameEvent {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "ExplicitNameEvent");
            assertThat(json)
                    // Explicit name wins over the trigger-suffix derivation.
                    .contains("\"InvoiceFinalised\"")
                    .doesNotContain("\"ExplicitNameEventActionEvent\"")
                    .contains("\"billing.invoice.finalised\"")
                    .contains("\"Fired once an invoice transitions to FINAL\"");
        }
    }

    // ---------- extractEventsMetadata: nested-class @DomainEvent (legacy path) ----------

    @Nested
    @DisplayName("Nested-class @DomainEvent (legacy path)")
    class NestedDomainEventTests {

        @Test
        @DisplayName("@DomainEvent on a nested class is harvested via the getEnclosedElements walk; topic propagates")
        void nestedDomainEventWithTopic() throws IOException {
            // Exercises the second loop in extractEventsMetadata (lines
            // 664-676) which scans the entity's nested classes for
            // @DomainEvent. Existing tests never put @DomainEvent on a
            // nested class.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.OrderWithNested",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.DomainEvent;
                    import eu.exeris.sdk.annotation.DomainEvent.Trigger;

                    @ExerisDomain(module = "sales", path = "/nested-event")
                    public class OrderWithNested {

                        @DomainEvent(trigger = Trigger.CREATE, topic = "nested.created")
                        public static class OrderPlacedEvent {}

                        @DomainEvent(trigger = Trigger.UPDATE, topic = "nested.updated")
                        public static class OrderUpdatedEvent {}
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "OrderWithNested");
            // Nested classes appear in the events list under their own
            // simple names (with topic propagated through
            // DomainEventMetadata.withTopic).
            assertThat(json)
                    .contains("\"OrderPlacedEvent\"")
                    .contains("\"OrderUpdatedEvent\"")
                    .contains("\"nested.created\"")
                    .contains("\"nested.updated\"");
        }

        @Test
        @DisplayName("Nested class without @DomainEvent annotation is ignored (the nested-walk only consumes @DomainEvent-tagged inner classes)")
        void nestedClassWithoutAnnotationIgnored() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.OrderWithInnerHelper",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "sales", path = "/inner-helper")
                    public class OrderWithInnerHelper {

                        public static class Helper {
                            public String label;
                        }
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeededWithoutWarnings();

            String json = metadataFor(compilation, "OrderWithInnerHelper");
            // No event annotation present on the inner class → events list
            // stays empty for the entity.
            assertThat(json).contains("\"events\" : [ ]");
        }
    }

    // ---------- applyDeprecatedValidationFallbacks: residual branch arms ----------

    /**
     * Six tests in {@code ExerisDomainProcessorTest} (deprecatedValidationRequired,
     * bothRequiredFieldWinsStillWarns, deprecatedValidateOnCreate,
     * deprecatedValidateOnUpdate, bothValidateOnFieldWinsStillWarns,
     * unrecognizedValidateOnTwoWarnings) cover the FIRING paths of both
     * fallback chains. What they leave uncovered are the inverse branches:
     *
     * <ul>
     *   <li>{@code containsKey("required")} TRUE but value is FALSE — the
     *       {@code Boolean.TRUE.equals(validationRequired)} else-arm fires
     *       (no fallback, no warning).</li>
     *   <li>{@code containsKey("required")} FALSE AND
     *       {@code containsKey("validateOn")} FALSE — the @Validation is
     *       present but uses none of the deprecated attributes; both
     *       top-level guards take the false arm (no fallback, no warning).</li>
     * </ul>
     */
    @Nested
    @DisplayName("@Validation deprecated-attribute fallbacks (residual branch arms)")
    class DeprecatedValidationFallbackResidualBranchTests {

        @Test
        @DisplayName("@Validation(required = false) explicit → Boolean.TRUE.equals(false) arm taken: no fallback, no deprecation warning")
        void explicitlyFalseRequiredSkipsFallback() throws IOException {
            // The deprecated @Validation.required attribute is present
            // (containsKey true) but its value is false — the inner
            // Boolean.TRUE.equals guard short-circuits and neither the
            // fallback nor the deprecation warning fires.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Name")
                        @Validation(required = false)
                        private String name;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            // The processor must NOT have emitted an [Exeris] warning here.
            long exerisWarnings = compilation.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("[Exeris]"))
                    .count();
            assertThat(exerisWarnings)
                    .as("processor-emitted [Exeris] warnings — Boolean.TRUE.equals(false) arm should suppress")
                    .isZero();
        }

        @Test
        @DisplayName("@Validation present but neither deprecated attribute set → both containsKey-false arms taken; no fallback, no warning")
        void noDeprecatedAttributesNoWarning() throws IOException {
            // Both `containsKey("required")` and `containsKey("validateOn")`
            // are false — the top-level guards in
            // applyDeprecatedValidationFallbacks each take their else arm,
            // so no fallback fires and no deprecation warning is emitted.
            // @Validation IS still present so the method IS invoked; only
            // the chain of fallbacks short-circuits at the top.
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.Item",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import eu.exeris.sdk.annotation.Validation;

                    @ExerisDomain(module = "catalog", path = "/items")
                    public class Item {
                        @Field(label = "Code")
                        @Validation(minLength = 3, maxLength = 20, pattern = "^[A-Z]+$")
                        private String code;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            long exerisWarnings = compilation.warnings().stream()
                    .filter(d -> d.getMessage(null) != null
                            && d.getMessage(null).contains("[Exeris]"))
                    .count();
            assertThat(exerisWarnings)
                    .as("processor-emitted [Exeris] warnings — both containsKey-false arms should suppress all deprecation chatter")
                    .isZero();

            // Sanity: @Validation.pattern still propagates to the field
            // metadata (proves the @Validation annotation was actually
            // picked up; if it weren't, applyDeprecatedValidationFallbacks
            // would never be called and the branch wouldn't fire either).
            // Note: @Validation.minLength / .maxLength are NOT read by the
            // processor today — that's a known gap, not in scope here.
            String json = metadataFor(compilation, "Item");
            assertThat(json).contains("\"pattern\" : \"^[A-Z]+$\"");
        }
    }

    // ---------- bugfix round-trips (computedFrom + @EventSourced SDK alignment) ----------

    /**
     * Two pre-existing data-loss bugs flagged in #45's review and fixed
     * in this PR. Both were caused by drift between the SDK annotation
     * surface and the processor's read-side assumptions:
     *
     * <ul>
     *   <li>{@code @Field.computedFrom} array values were silently dropped
     *       because the call site used {@code instanceof String[]} on a
     *       javac-surfaced {@code List<AnnotationValue>}. Now flattened at
     *       {@code extractAnnotationValues}.</li>
     *   <li>{@code @EventSourced.streamPrefix} / {@code .snapshotThreshold}
     *       were silently ignored because the processor read
     *       {@code aggregateType} / {@code snapshotEvery} (the SDK
     *       metadata-model field names, which are NOT attributes on the
     *       annotation). Fixed by translating annotation→model at the
     *       extract seam.</li>
     * </ul>
     */
    @Nested
    @DisplayName("Bugfix round-trips: @Field.computedFrom + @EventSourced SDK alignment")
    class BugfixRoundTripTests {

        @Test
        @DisplayName("@Field(computed = true, computedFrom = {\"a\",\"b\"}) → computedFrom propagates verbatim as a JSON array")
        void computedFromArrayPropagates() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.HasComputed",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "sales", path = "/has-computed")
                    public class HasComputed {
                        @Field(label = "First Name")
                        private String firstName;

                        @Field(label = "Last Name")
                        private String lastName;

                        @Field(label = "Full Name", computed = true, computedFrom = {"firstName", "lastName"})
                        private String fullName;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            String json = metadataFor(compilation, "HasComputed");
            assertThat(json)
                    .contains("\"computed\" : true")
                    .contains("\"computedFrom\" : [ \"firstName\", \"lastName\" ]");
        }

        @Test
        @DisplayName("@Field(computedFrom = {}) → empty list is emitted (or omitted) but doesn't crash")
        void emptyComputedFromList() throws IOException {
            // The empty-array case verifies that the new
            // extractAnnotationValues flatten step handles an empty
            // List<AnnotationValue> cleanly (stream.toList() returns an
            // empty list, the call site puts an empty list on the builder).
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.EmptyComputed",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;

                    @ExerisDomain(module = "sales", path = "/empty-computed")
                    public class EmptyComputed {
                        @Field(label = "Derived", computed = true, computedFrom = {})
                        private String derived;
                    }
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
            // computed=true still propagates; computedFrom omitted from JSON
            // when empty (NON_NULL serialisation on the SDK metadata
            // record's empty-list default).
            String json = metadataFor(compilation, "EmptyComputed");
            assertThat(json).contains("\"computed\" : true");
        }

        @Test
        @DisplayName("@EventSourced(streamPrefix = \"Billing\", snapshotThreshold = 25) → aggregateType=Billing, snapshotEvery=25")
        void eventSourcedUserValuesPropagate() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.BillingAggregate",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.EventSourced;

                    @ExerisDomain(module = "billing", path = "/billing")
                    @EventSourced(streamPrefix = "Billing", snapshotThreshold = 25)
                    public class BillingAggregate {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            String json = metadataFor(compilation, "BillingAggregate");
            // Both translations land: user's streamPrefix overrides the
            // class-name fallback for aggregateType, and the user-supplied
            // snapshotThreshold overrides the SDK default of 50.
            assertThat(json)
                    .contains("\"aggregateType\" : \"Billing\"")
                    .contains("\"snapshotEvery\" : 25");
        }

        @Test
        @DisplayName("@EventSourced with no streamPrefix → aggregateType falls back to the entity class name (per SDK Javadoc)")
        void eventSourcedEmptyStreamPrefixDefaultsToClassName() throws IOException {
            JavaFileObject source = JavaFileObjects.forSourceString(
                    "com.example.OrderAggregate",
                    """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.EventSourced;

                    @ExerisDomain(module = "sales", path = "/orders")
                    @EventSourced
                    public class OrderAggregate {}
                    """
            );

            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();

            String json = metadataFor(compilation, "OrderAggregate");
            // streamPrefix not written by user → falls back to
            // element.getSimpleName(); snapshotThreshold not written by
            // user → SDK default 50.
            assertThat(json)
                    .contains("\"aggregateType\" : \"OrderAggregate\"")
                    .contains("\"snapshotEvery\" : 50");
        }
    }

    // ---------- helpers (mirrors the existing test classes' pattern) ----------

    private static Compilation compileWithProcessor(JavaFileObject... sources) {
        return javac().withProcessors(new ExerisDomainProcessor()).compile(sources);
    }

    private static String metadataFor(Compilation compilation, String entity) throws IOException {
        Optional<JavaFileObject> file = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT,
                "exeris-metadata/" + entity + ".json");
        assertThat(file).isPresent();
        return readContent(file.get());
    }

    private static String readContent(JavaFileObject file) throws IOException {
        try (var inputStream = file.openInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
