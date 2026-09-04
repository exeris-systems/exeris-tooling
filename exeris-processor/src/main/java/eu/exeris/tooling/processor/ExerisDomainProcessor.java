package eu.exeris.tooling.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.auto.service.AutoService;
import com.sun.source.util.Trees;
import eu.exeris.sdk.sourcemodel.ast.*;
import eu.exeris.sdk.sourcemodel.mutation.BaselineTrust;
import eu.exeris.sdk.sourcemodel.mutation.SourceDigest;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Compile-time annotation processor for Exeris SDK annotations.
 * <p>
 * Processes {@code @ExerisDomain} and {@code @Saga} annotated classes,
 * extracts domain metadata, validates annotations, and writes JSON
 * metadata files for code generators.
 *
 * <h2>Output</h2>
 * For each processed domain class, generates a JSON file in
 * {@code exeris-metadata/} containing complete domain metadata.
 *
 * @since 0.1.0
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "eu.exeris.sdk.annotation.ExerisDomain",
        "eu.exeris.sdk.annotation.Saga",
        "eu.exeris.sdk.annotation.capability.CapabilityModule",
        "eu.exeris.sdk.annotation.View"
})
@SupportedOptions({ExerisDomainProcessor.OPTION_VERBOSE, ExerisDomainProcessor.OPTION_STRICT})
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CouplingBetweenObjects"
})
public class ExerisDomainProcessor extends AbstractProcessor {

    /**
     * Tracks the running compiler instead of a pinned constant — a literal in
     * {@code @SupportedSourceVersion} warns on any consumer compiling at a higher release
     * (the kernel's {@code preview} line compiles at 28). Safe because nothing read here is
     * release-sensitive: annotations and {@code TypeMirror}s only, all {@code SOURCE}-retained.
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Metadata output directory name.
     */
    public static final String METADATA_DIR = "exeris-metadata";

    /**
     * Annotation processor option that opts in to per-entity progress notes
     * and full stack traces on processing failures. Pass via
     * {@code -Aexeris.verbose=true} to {@code javac} (or
     * {@code <compilerArg>-Aexeris.verbose=true</compilerArg>} in the
     * Maven compiler plugin).
     */
    public static final String OPTION_VERBOSE = "exeris.verbose";

    /**
     * Annotation processor option that opts in to a build-time completeness
     * audit: a per-attribute WARNING whenever the author sets an annotation
     * attribute that <em>no</em> code generator consumes (see
     * {@link #INERT_ATTRIBUTES}). Off by default so ordinary builds stay quiet;
     * pass {@code -Aexeris.strict=true} to {@code javac} (or
     * {@code <compilerArg>-Aexeris.strict=true</compilerArg>} in the Maven
     * compiler plugin) to enable it.
     */
    public static final String OPTION_STRICT = "exeris.strict";

    /** Diagnostic prefix prepended to every NOTE/WARNING/ERROR this processor emits. */
    private static final String DIAG_PREFIX = "[Exeris] ";

    /** Attribute name shared by {@code @Saga} and the capability {@code @Provides}/{@code @Requires}. */
    private static final String VERSION_ATTRIBUTE = "version";

    /** {@code @GraphEdge}, and the container javac synthesises when it is repeated on one field. */
    private static final String GRAPH_EDGE_FQN = "eu.exeris.sdk.annotation.GraphEdge";

    private static final String GRAPH_EDGES_FQN = "eu.exeris.sdk.annotation.GraphEdges";

    /** {@code @SagaStep}, and the container javac synthesises when it is repeated on one method. */
    private static final String SAGA_STEP_FQN = "eu.exeris.sdk.annotation.SagaStep";

    private static final String SAGA_STEPS_FQN = "eu.exeris.sdk.annotation.SagaSteps";

    /** Package every SDK annotation lives under, including the {@code capability} sub-package. */
    private static final String SDK_ANNOTATION_PACKAGE = "eu.exeris.sdk.annotation.";

    /** The sole element of a {@code @Repeatable} container, and of every single-value annotation. */
    private static final String VALUE_ELEMENT = "value";

    /** Closing clause on every strict-mode diagnostic — it is opt-in, so say so at the point of use. */
    private static final String STRICT_SUFFIX = ". (reported because -Aexeris.strict is enabled)";

    /**
     * An annotation attribute the author can set but that <em>no</em> code
     * generator — neither {@code exeris-codegen-java} nor
     * {@code exeris-codegen-ts} — currently reads, so setting it has no effect
     * on emitted output.
     *
     * @param annotation the SDK annotation's simple name (e.g. {@code "Field"})
     * @param attribute  the attribute (annotation element) name
     * @param note       why it is inert today — surfaced verbatim in the warning
     */
    private record InertAttribute(String annotation, String attribute, String note) {}

    /**
     * An SDK annotation that <em>no</em> generator consumes — so applying it has no
     * effect on emitted output, whether or not the processor extracts it. Distinct
     * from {@link InertAttribute}: here the <em>whole</em>
     * annotation is inert (a missing-generator gap), so strict mode reports it
     * once per entity rather than per attribute.
     *
     * @param fqn     the annotation's fully-qualified name (for mirror lookup)
     * @param display the simple name shown in the warning
     * @param note    why it is inert today — surfaced verbatim in the warning
     */
    private record InertAnnotation(String fqn, String display, String note) {}

    /**
     * An SDK annotation the processor never reads at all, with the reason. Distinct from
     * {@link InertAnnotation}, and the distinction is the whole point of the C0 audit: an
     * {@code InertAnnotation} is <em>extracted and consumed by nobody</em>, whereas these never
     * enter the pipeline in the first place — no extraction call, therefore no
     * {@link #warnInertAttributes} call site, therefore, before C0, no possible warning.
     *
     * @param display the simple name shown in the warning
     * @param note    why it is unread today — surfaced verbatim in the warning
     */
    private record UnreadAnnotation(String display, String note) {}


    /**
     * JSON wire shape for one {@code @CapabilityModule} class. The SDK's
     * {@link CapabilityModuleMetadata} carries no module identity (it has only
     * provides/requires/lifecycleOwner), so the processor wraps it with the
     * module's own name/package/FQN before write-out. Capabilities are app-wide,
     * not per-entity, so this is a separate JSON family ({@code capability_*.json})
     * parallel to — never nested inside — {@code DomainMetadata}. The
     * codegen-core read model ({@code CapabilityModuleDescriptor}) mirrors these
     * field names structurally (the processor and generators live in different
     * modules and share only the SDK source-model contract).
     */
    private record CapabilityModuleJson(String name,
                                        String packageName,
                                        String qualifiedName,
                                        CapabilityModuleMetadata module) {}

    /**
     * JSON wire shape for one {@code @View} class. The SDK's {@link ViewMetadata}
     * carries the view's own {@code name} but not its package / qualified name, so
     * — exactly like {@link CapabilityModuleJson} — the processor wraps it with the
     * declaring class's identity before write-out. Views are app-wide, not
     * per-entity, so this is a separate JSON family ({@code view_*.json}) parallel
     * to — never nested inside — {@code DomainMetadata} (RFC-2026-06-28 §2). A
     * downstream codegen-ts {@code ViewGenerator} will mirror these field names
     * structurally (processor and generators share only the SDK source-model
     * contract).
     */
    private record ViewJson(String name,
                            String packageName,
                            String qualifiedName,
                            ViewMetadata view) {}

    /**
     * Why these registries exist: every SDK annotation is
     * {@code @Retention(SOURCE)}, so it is erased by the compiler and is absent
     * from bytecode — the kernel runtime / SPI / Core <em>cannot</em> read any
     * of them. The build-time pipeline (this processor + the generators) is the
     * <em>only</em> possible consumer. An attribute or annotation that reaches
     * no generator therefore has literally zero effect; there is no runtime
     * escape hatch. That is exactly what strict mode surfaces.
     *
     * <p>Hand-maintained registry of annotation <em>attributes</em> that reach no
     * generator. Deliberately conservative. Every entry satisfies two checks:
     * (1) the attribute is actually declared on the named SDK annotation
     * (otherwise the author cannot write it — it would be a {@code javac} error,
     * not a silent no-op), and (2) no generator reads the corresponding metadata,
     * verified against <em>both</em> emitter code bases. Consumption is the union
     * of the Java and TS emitters — an attribute read by only one side is NOT
     * inert and must not appear here.
     *
     * <p><strong>(3) the annotation's extraction path must call
     * {@link #warnInertAttributes} with the same simple name.</strong> The audit
     * is driven from those call sites, not from this list, so an entry whose
     * annotation has no call site is unreachable — it reads as coverage and
     * produces nothing. Two entries sat here in exactly that state ({@code Action}
     * and {@code ExerisDomain}) until the call sites were added; a registry that
     * silently accepts dead entries is the same defect class the audit exists to
     * report.
     *
     * <p>NB: an AST record component (e.g. {@code RelationshipMetadata.valueField()})
     * is NOT an annotation attribute — several such accessors exist with no
     * matching annotation element, and they are deliberately absent here.
     *
     * <p><strong>Second carve-out: an attribute blocked upstream is not inert, and does not
     * belong here.</strong> {@code @SagaStep.parallel} is the live case — extracted, carried by
     * {@code SagaStepMetadata.parallel()}, read by no emitter, so it passes every test above and
     * an entry for it would look obviously correct. It would still be wrong. The emitted flow is
     * a strict linear chain because the kernel {@code FlowDefinition} has no way to express
     * concurrent steps; until it does, the chain is the only correct compilation available, and
     * registering the attribute would record a missing kernel contract as neglect by a generator
     * that has nothing else it could emit. {@code @SagaStep.waitForAll} and {@code .failFast}
     * share the cause and are additionally uncarried. When the kernel grows the contract, the
     * generators consume all three — no entry to delete, because none was ever added.
     *
     * <p>Today the point is moot twice over, and the second reason is the sharper one: an entry
     * for any of them would fire <em>nothing</em>. {@link #warnInertAttributes} is called for
     * {@code ExerisDomain}, {@code Field}, {@code Action} and {@code ActionParam} and for nothing
     * else, so {@code Saga} and {@code SagaStep} have no call site — condition (3) above, the
     * unreachable-entry trap, exactly as the standing {@code T11-strict} marker on
     * {@code DomainEvent} records for its own annotation. Adding the two missing call sites is
     * worth doing on its own merits; it is not a prerequisite for a decision that is to add no
     * entry.
     *
     * <p>When a generator starts consuming one of these, DELETE its entry in the
     * same change — a stale entry produces a false "no effect" warning on an
     * attribute that now matters. Surfaced only under {@code -Aexeris.strict}.
     */
    private static final List<InertAttribute> INERT_ATTRIBUTES = List.of(
            new InertAttribute("Action", "path",
                    "the route is derived as {domainPath}/{id}/actions/{kebab-action-name} and this "
                            + "value is never read — ActionMetadata carries no path component, so it "
                            + "does not even reach the JSON. Deleting the attribute changes nothing: "
                            + "it has had a default since SDK 0.11.0, so it is no longer a value "
                            + "every author is forced to write (T44)"),
            new InertAttribute("Action", "permissions",
                    "the processor does not extract it, so ActionMetadata's permissions field is "
                            + "empty in every build — and nothing would read it if it were filled. "
                            + "The one generator that copies the field, DomainMetadataGenerator, is "
                            + "constructed by no production code path, and the .meta.json it would "
                            + "write is read by nothing. So this is not an extraction gap in front "
                            + "of a waiting consumer: closing the extraction alone would still "
                            + "produce no effect. Of the two access attributes this is nonetheless "
                            + "the half with a destination in principle: the kernel's "
                            + "RouteRequirement decides on named scopes, so a permission is what a "
                            + "generated URL-to-policy table would carry — and that table is this "
                            + "repository's to emit and is not built (T53)"),
            new InertAttribute("Action", "roles",
                    "the processor does not extract it, and unlike permissions it has no route-level "
                            + "destination: the kernel decides a route on scopes and never on roles, "
                            + "and mapping ROLE_x onto a scope would stand up a second authority "
                            + "model at the edge. Roles resolve at the method level through the "
                            + "kernel's own @RequiresRole (kernel ADR-014). What this attribute "
                            + "should compile into here, if anything, is undecided (T53)"),
            new InertAttribute("ActionParam", "description",
                    "the value reaches ActionParamMetadata in the JSON, but no emitter renders "
                            + "it — action-parameter generation reads only the parameter name and type"),
            new InertAttribute("ActionParam", "required",
                    "the value reaches ActionParamMetadata in the JSON, but no emitter renders "
                            + "it — action-parameter generation reads only the parameter name and type"),
            new InertAttribute("TenantId", "autoPopulate",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The generated repository "
                            + "already stamps the tenant it writes (T36), unconditionally"),
            new InertAttribute("TenantId", "exposeInApi",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. Whether a tenant column "
                            + "reaches the DTO is decided by the emitted type, which omits system fields "
                            + "wholesale"),
            new InertAttribute("TenantId", "scopeUniqueConstraints",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. A DDL concern "
                            + "KernelFlywayGenerator could carry and does not"),
            new InertAttribute("TenantId", "validateOnMutation",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output"),
            new InertAttribute("Version", "initialValue",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted DDL defaults "
                            + "the version column to 0"),
            new InertAttribute("Version", "requiredOnUpdate",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The repository's "
                            + "optimistic-lock UPDATE always carries the version predicate"),
            new InertAttribute("Version", "useForETag",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. No emitted handler sets "
                            + "or reads an ETag header"),
            new InertAttribute("SoftDelete", "allowHardDelete",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted repository "
                            + "picks UPDATE-or-DELETE from the domain's softDelete flag alone"),
            new InertAttribute("SoftDelete", "defaultValue",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted DDL defaults "
                            + "the flag to false"),
            new InertAttribute("SoftDelete", "excludeFromUniqueConstraints",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. A DDL concern "
                            + "KernelFlywayGenerator could carry and does not"),
            new InertAttribute("SoftDelete", "retentionPeriod",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. Retention is a runtime "
                            + "scheduler rather than an emission concern — HLA places it in "
                            + "exeris-caps-soft-delete"),
            new InertAttribute("SoftDeleteTimestamp", "clearOnRestore",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted restore "
                            + "clears the timestamp unconditionally"),
            new InertAttribute("SoftDeletedBy", "clearOnRestore",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted restore "
                            + "clears the actor unconditionally"),
            new InertAttribute("AuditCreatedAt", "immutable",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted UPDATE never "
                            + "lists the created-at column"),
            new InertAttribute("AuditCreatedBy", "expression",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. An actor expression needs "
                            + "a principal to evaluate against, which the emitted repository does not "
                            + "take"),
            new InertAttribute("AuditCreatedBy", "immutable",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted UPDATE never "
                            + "lists the created-by column"),
            new InertAttribute("AuditUpdatedAt", "setOnCreate",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted INSERT stamps "
                            + "both timestamps"),
            new InertAttribute("AuditUpdatedBy", "expression",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The same principal gap as "
                            + "@AuditCreatedBy.expression"),
            new InertAttribute("AuditUpdatedBy", "setOnCreate",
                    "the annotation's role — which field plays it — is extracted (C1) and "
                            + "reaches the schema and the repository. This attribute is not: "
                            + "SystemFieldsMetadata carries one field name per role and has no component "
                            + "for it, so setting it changes no emitted output. The emitted INSERT stamps "
                            + "both actors"),
            new InertAttribute("ExerisDomain", "apiVersion",
                    "no emitted artifact carries a version segment: the router registers routes at "
                            + "the entity path, the OpenAPI document publishes the same, and the Java "
                            + "client and every TypeScript client were aligned onto it. Emitting "
                            + "/api/{version}/{path} from the router instead is a defensible API "
                            + "decision, but it changes every route and the published contract, so it "
                            + "is a decision to take rather than a default to assume. Until it is "
                            + "taken, setting this attribute has no effect on output"),
            new InertAttribute("ExerisDomain", "permissions",
                    "the processor does not extract it, so DomainMetadata's permissions field — "
                            + "which exists, and is mirrored in the TypeScript model — is empty in "
                            + "every build and no emitter can read it. The destination exists: the "
                            + "kernel's RouteRequirement decides on named scopes, and a generated "
                            + "URL-to-policy table would carry these. That table is this "
                            + "repository's to emit and is not built (T53)"),
            new InertAttribute("ExerisDomain", "primaryKeyField",
                    "it is extracted — SystemFieldsMetadata carries it — but it is the one "
                            + "component of that record no generator reads. The other nine are all "
                            + "honoured: KernelFlywayGenerator's sysCol maps tenantId, the four "
                            + "audit stamps, the three soft-delete columns and version, and "
                            + "KernelRepositoryGenerator resolves five of them. The primary key is "
                            + "not among them anywhere. Flyway emits id UUID PRIMARY KEY "
                            + "unconditionally, the repository identifies every row through the "
                            + "constant WHERE id = ?, and every by-id handler binds the {id} path "
                            + "variable, so a renamed key reaches neither the schema, the query nor "
                            + "the route. The TypeScript emitters did read it for one change and "
                            + "were corrected: honouring it on one side alone made the emitted app "
                            + "request an identifier the router does not serve, which is worse than "
                            + "ignoring it on both. Renaming the primary key is a change to the SQL, "
                            + "the repository and the route template together — that is C1's scope, "
                            + "and this entry is deleted in the change that lands it"),
            new InertAttribute("ExerisDomain", "roles",
                    "the processor does not extract it, and unlike permissions it has no route-level "
                            + "destination: the kernel decides a route on scopes and never on roles, "
                            + "and mapping ROLE_x onto a scope would stand up a second authority "
                            + "model at the edge. Roles resolve at the method level through the "
                            + "kernel's own @RequiresRole (kernel ADR-014). Note that the emitted "
                            + "Angular guards do check a role, but against a name this pipeline "
                            + "invents rather than one declared here (T53)"));

    /**
     * Hand-maintained registry of whole type-level annotations that are extracted
     * into {@code DomainMetadata} but consumed by no generator. Same discipline as
     * {@link #INERT_ATTRIBUTES}: an entry means the annotation does nothing today
     * because the emitting generator does not exist yet (a build gap, not a
     * runtime contract — SOURCE retention precludes a runtime consumer).
     *
     * <p>{@code @Saga} and {@code @Graph} are deliberately <em>absent</em>: their
     * generators ({@code KernelSagaGenerator}, {@code KernelGraphSyncGenerator})
     * do consume them. When the event-sourcing generator lands, DELETE the
     * {@code @EventSourced} entry in the same change.
     *
     * <p>{@code @View} is also <em>absent</em> (RFC-2026-06-28 §4): the codegen-ts
     * presentation-IR emitter (the {@code view-gen} ViewGenerator) now consumes
     * {@code view_*.json}, so {@code @View} is no longer inert. Consumption is the
     * Java∪TS union; an annotation read by the TS emitter is NOT inert even though
     * no Java generator touches it (views are a front-only facet — there is no Java
     * emitter counterpart, by construction).
     */
    private static final List<InertAnnotation> INERT_ANNOTATIONS = List.of(
            new InertAnnotation("eu.exeris.sdk.annotation.EventSourced", "EventSourced",
                    "event-sourcing emission is not yet implemented, so the extracted "
                            + "EventSourcedMetadata reaches no generator (see ROADMAP EV2). This "
                            + "is a tooling gap, NOT a kernel gate: the kernel line this repo "
                            + "pins (0.11.0) ships both halves — EventStreamReader."
                            + "replayFromVersion(StreamId, long) is the replayable per-aggregate "
                            + "read and EventStreamAppender.append(StreamId, expectedVersion, ...) "
                            + "the optimistic-concurrency write, with JDBC and Kafka Community "
                            + "bindings and a TCK. Two traps worth naming, because both have been "
                            + "walked into: eu.exeris.kernel.spi.persistence.EventStore is NOT "
                            + "that SPI (it is the transactional outbox — append / pollPending / "
                            + "markPublished — so it holds no stream to rehydrate from), and "
                            + "KernelProviders.eventStreamReader() returns an Optional because a "
                            + "broker may not support replay, so generated code must handle "
                            + "absence rather than assume a binding"),
            new InertAnnotation("eu.exeris.sdk.annotation.Blob", "Blob",
                    "the processor does not extract it and no generator consumes it, so the field "
                            + "is emitted exactly as if the annotation were absent. The design-time "
                            + "surface is reserved (ADR-072) and the transcription is additionally "
                            + "kernel-gated: Application.main() boots subsystems by name and there "
                            + "is no CommunityStorageSubsystem to name, a name alone would not be "
                            + "enough anyway (two Community blob drivers share a priority, and an "
                            + "unset selection key with more than one provider present is a startup "
                            + "failure by design), and the kernel has scheduled that subsystem "
                            + "post-1.0. See docs/adr/ADR-072.link.md (K6)"),
            new InertAnnotation("eu.exeris.sdk.annotation.Schedule", "Schedule",
                    "the processor does not extract it and no generator consumes it, so the "
                            + "annotated method is emitted exactly as if the annotation were absent. "
                            + "The design-time surface is reserved (ADR-072). This one is NOT "
                            + "capability-gated — the scheduling subsystem boots — it is gated on "
                            + "identity: JobScheduler.submit(...) captures the ambient "
                            + "PrincipalContext and StorageContext at submission and fails a job "
                            + "closed at dispatch when neither is bound, and a declared schedule has "
                            + "no submission event to capture from. See docs/adr/ADR-072.link.md"));

    /**
     * Every SDK annotation this processor extracts, by simple name. <strong>C0: this set is the
     * audit's authority, and it is an allowlist on purpose.</strong>
     *
     * <p>Before C0, strict mode could only report what somebody had written into
     * {@link #INERT_ATTRIBUTES} / {@link #INERT_ANNOTATIONS} — a denylist, driven from the
     * extraction call sites. That made it structurally blind to exactly the largest gap: an
     * annotation this processor never reads has no extraction site, so it has no
     * {@code warnInert*} call, so no entry in either registry could ever fire for it. Sixteen of
     * the SDK's thirty-five annotations were in that state, silently.
     *
     * <p>Inverting it closes the class rather than the instances. Anything present on a visited
     * element and absent from this set is reported without anyone having to notice it first, and
     * a new extraction must be added here in the same change — the mirror image of the
     * delete-your-registry-entry rule that already governs {@link #INERT_ATTRIBUTES}.
     *
     * <p>Membership means "the processor reads it", strictly — NOT "it reaches emitted output",
     * and NOT "the audit stays quiet about it". The narrower consumption question stays with the
     * two inert registries: {@code @EventSourced} is in this set and is still reported, by
     * {@link #INERT_ANNOTATIONS}. And an unextracted annotation that already has a registry entry
     * ({@code @Blob}, {@code @Schedule}) is deliberately absent here and silenced by
     * {@link #isAlreadyAudited} instead, so that this set never claims an extraction that does not
     * exist. Every SDK annotation is answered by exactly one of the two passes.
     */
    private static final Set<String> EXTRACTED_ANNOTATIONS = Set.of(
            "Action", "ActionParam", "AuditCreatedAt", "AuditCreatedBy", "AuditUpdatedAt",
            "AuditUpdatedBy", "Bind", "Block", "CapabilityLifecycle", "CapabilityModule",
            "DomainEvent", "EventSourced", "ExerisDomain", "Field", "Graph", "GraphEdge",
            "InternalApi", "Provides", "Region", "Relationship", "Requires", "Saga", "SagaStep",
            "SoftDelete", "SoftDeleteTimestamp", "SoftDeletedBy", "TenantId", "UI", "Validation",
            "Version", "View");

    /**
     * Reasons for the annotations {@link #EXTRACTED_ANNOTATIONS} does not contain. Optional by
     * design: the audit reports an unread annotation whether or not it has an entry here, which
     * is what makes the set above sufficient on its own. An entry only replaces the generic
     * sentence with the specific one.
     *
     * <p>The distinction these notes carry is real and worth the words: {@code @Derived},
     * {@code @Rule}, {@code @EventHandler} and {@code @Projection} are <em>reserved</em> — AST
     * carriers exist and the emission is design-gated on the behavioural corpus — whereas
     * {@code @NavMenu} or {@code @Tab} are simply unbuilt. Both are "no effect on output" and an
     * author deserves to know which one they have hit.
     */
    private static final List<UnreadAnnotation> UNREAD_NOTES = List.of(
            new UnreadAnnotation("PrimaryKey",
                    "the other nine annotation.system.* annotations are extracted (C1) and their "
                            + "field names reach the schema and the repository. This one is held "
                            + "back on purpose: SystemFieldsMetadata.primaryKeyField is the single "
                            + "component no generator honours — KernelFlywayGenerator emits "
                            + "id UUID PRIMARY KEY unconditionally, the repository identifies rows "
                            + "through the constant WHERE id = ?, and every by-id handler binds the "
                            + "{id} path variable. Extracting it would end this warning without "
                            + "changing one byte of emitted output. Renaming a primary key is one "
                            + "change across the SQL, the repository and the route template"),
            new UnreadAnnotation("Derived",
                    "DerivedMetadata exists in the source model and is filled by nobody: the "
                            + "processor performs no extraction and no emitter names the type. "
                            + "Reserved rather than overlooked — emission is design-gated on the "
                            + "behavioural corpus (ROADMAP, behavioural AST)"),
            new UnreadAnnotation("Rule",
                    "RuleMetadata exists in the source model and is filled by nobody, for the same "
                            + "reason as @Derived: reserved, design-gated on the behavioural corpus"),
            new UnreadAnnotation("EventHandler",
                    "reserved for the event-reaction verb, which is design-gated on the behavioural "
                            + "corpus. Not to be confused with @DomainEvent, which IS extracted and "
                            + "does reach the emitters"),
            new UnreadAnnotation("Projection",
                    "reserved. Its open question is cross-service exposure, which is the same fork "
                            + "T12 and the @View mesh binding sit on — so it is design-gated on a "
                            + "topology decision, not on an extractor"),
            new UnreadAnnotation("GraphProperty",
                    "the type-level @Graph is read and, since S3, so is @GraphEdge — this one is "
                            + "not, and GraphMetadata.properties is passed as null in consequence"),
            new UnreadAnnotation("GraphQuery",
                    "the type-level @Graph is read and, since S3, so is @GraphEdge — this one is "
                            + "not, and GraphMetadata.queries is passed as an empty list"),
            new UnreadAnnotation("SagaTransition",
                    "KernelSagaGenerator emits transitions as a strict linear chain over "
                            + "SagaMetadata.steps() in declaration order and consults no transition "
                            + "annotation, so a declared transition is discarded before it reaches "
                            + "any generator"),
            new UnreadAnnotation("QueryParam",
                    "action parameters are extracted through @ActionParam only; a parameter "
                            + "carrying just this annotation reaches ActionMetadata as if it were "
                            + "unannotated"),
            new UnreadAnnotation("NavMenu",
                    "no navigation artefact is emitted from it on either side. The TS app shell "
                            + "builds its sidebar from the entity list and the @View routes, never "
                            + "from this annotation"),
            new UnreadAnnotation("Tab",
                    "presentation grouping is emitted from @View (regions and blocks) and, at the "
                            + "leaf level, from @UI. This annotation feeds neither"),
            new UnreadAnnotation("UIGroup",
                    "same gap as @Tab: @UI is extracted per field, this grouping annotation is not"));

    private ObjectMapper objectMapper;
    private Messager messager;
    private Filer filer;
    /** javac Compiler Tree API, used to read entity source text for the ADR-042 digest; null off javac. */
    private Trees trees;
    private boolean verbose;
    private boolean strict;

    /** Collected enums from all processed entities */
    private final Set<TypeElement> discoveredEnums = new HashSet<>();

    /**
     * Default constructor required by annotation processing framework.
     */
    public ExerisDomainProcessor() {
        // Initialized in init()
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.objectMapper = createObjectMapper();
        // Compiler Tree API gives the entity's raw source text for the ADR-042
        // sourceDigest. Absent off a real javac (e.g. some IDE/incremental envs) —
        // degrade gracefully to a schemaVersion-only stamp rather than failing.
        try {
            this.trees = Trees.instance(processingEnv);
        } catch (IllegalArgumentException notJavac) {
            this.trees = null;
        }
        this.verbose = Boolean.parseBoolean(
                processingEnv.getOptions().getOrDefault(OPTION_VERBOSE, "false"));
        this.strict = Boolean.parseBoolean(
                processingEnv.getOptions().getOrDefault(OPTION_STRICT, "false"));
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            // On final round, write all discovered enum metadata
            processDiscoveredEnums();
            return false;
        }

        // Process @ExerisDomain annotated classes
        processExerisDomainAnnotations(roundEnv);

        // Process standalone @Saga annotated classes
        processSagaAnnotations(roundEnv);

        // Process @CapabilityModule annotated classes (parallel JSON, not part of
        // DomainMetadata — capabilities are app-wide, not per-entity)
        processCapabilityModuleAnnotations(roundEnv);

        // Process @View annotated classes (parallel JSON, not part of DomainMetadata
        // — views are an app-wide, front-only facet; RFC-2026-06-28)
        processViewAnnotations(roundEnv);

        return true;
    }

    private void processDiscoveredEnums() {
        if (discoveredEnums.isEmpty()) {
            return;
        }

        note("Processing " + discoveredEnums.size() + " discovered enum(s)");

        for (TypeElement enumElement : discoveredEnums) {
            try {
                EnumMetadata metadata = buildEnumMetadata(enumElement);
                writeMetadata("enum_" + enumElement.getSimpleName(), metadata);
                note("Generated enum metadata: " + enumElement.getSimpleName());
            } catch (Exception e) {
                reportProcessingFailure(enumElement, "Failed to process enum", e);
            }
        }
    }

    private EnumMetadata buildEnumMetadata(TypeElement enumElement) {
        String name = enumElement.getSimpleName().toString();
        String qualifiedName = enumElement.getQualifiedName().toString();
        String packageName = getPackageName(enumElement);

        // Extract description from Javadoc if available
        String description = processingEnv.getElementUtils().getDocComment(enumElement);
        if (description != null) {
            description = description.trim().split("\n")[0]; // First line only
        }

        List<EnumMetadata.EnumValueMetadata> values = new ArrayList<>();
        int ordinal = 0;

        for (Element enclosed : enumElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.ENUM_CONSTANT) {
                String valueName = enclosed.getSimpleName().toString();
                String valueDoc = processingEnv.getElementUtils().getDocComment(enclosed);
                String valueDescription = valueDoc != null ? valueDoc.trim() : null;

                // Convert to display name
                String displayName = toDisplayName(valueName);

                values.add(new EnumMetadata.EnumValueMetadata(
                        valueName,
                        displayName,
                        valueDescription,
                        ordinal++
                ));
            }
        }

        return new EnumMetadata(name, qualifiedName, packageName, description, values);
    }

    private String toDisplayName(String enumConstant) {
        // Convert SCREAMING_CASE to Title Case
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : enumConstant.toCharArray()) {
            if (c == '_') {
                sb.append(' ');
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private void processExerisDomainAnnotations(RoundEnvironment roundEnv) {
        TypeElement domainAnnotation = processingEnv.getElementUtils()
                .getTypeElement("eu.exeris.sdk.annotation.ExerisDomain");

        if (domainAnnotation == null) {
            return;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(domainAnnotation)) {
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "@ExerisDomain can only be applied to classes");
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            processDomainEntity(typeElement);
        }
    }

    private void processSagaAnnotations(RoundEnvironment roundEnv) {
        TypeElement sagaAnnotation = processingEnv.getElementUtils()
                .getTypeElement("eu.exeris.sdk.annotation.Saga");

        if (sagaAnnotation == null) {
            return;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(sagaAnnotation)) {
            if (element.getKind() != ElementKind.CLASS) {
                error(element, "@Saga can only be applied to classes");
                continue;
            }

            // Skip if also annotated with @ExerisDomain (processed above)
            TypeElement exerisDomainType = processingEnv.getElementUtils()
                    .getTypeElement("eu.exeris.sdk.annotation.ExerisDomain");
            if (exerisDomainType != null && hasAnnotation(element, exerisDomainType)) {
                continue;
            }

            TypeElement typeElement = (TypeElement) element;
            processSaga(typeElement);
        }
    }

    private boolean hasAnnotation(Element element, TypeElement annotationType) {
        return element.getAnnotationMirrors().stream()
                .anyMatch(am -> processingEnv.getTypeUtils()
                        .isSameType(am.getAnnotationType(), annotationType.asType()));
    }

    private void processCapabilityModuleAnnotations(RoundEnvironment roundEnv) {
        TypeElement capabilityModuleType = processingEnv.getElementUtils()
                .getTypeElement("eu.exeris.sdk.annotation.capability.CapabilityModule");

        if (capabilityModuleType == null) {
            return;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(capabilityModuleType)) {
            if (element.getKind() != ElementKind.CLASS && element.getKind() != ElementKind.INTERFACE) {
                error(element, "@CapabilityModule can only be applied to a type");
                continue;
            }
            processCapabilityModule((TypeElement) element);
        }
    }

    private void processCapabilityModule(TypeElement element) {
        String name = element.getSimpleName().toString();
        String packageName = getPackageName(element);
        String qualifiedName = element.getQualifiedName().toString();

        note("Processing capability module: " + qualifiedName);

        try {
            CapabilityModuleMetadata module = buildCapabilityModuleMetadata(element);
            writeMetadata("capability_" + name,
                    new CapabilityModuleJson(name, packageName, qualifiedName, module));
            note("Generated capability metadata for: " + name);
        } catch (Exception e) {
            reportProcessingFailure(element, "Failed to process capability module", e);
        }
    }

    private CapabilityModuleMetadata buildCapabilityModuleMetadata(TypeElement element) {
        List<ProvidesMetadata> provides = new ArrayList<>();
        List<RequiresMetadata> requires = new ArrayList<>();

        for (AnnotationMirror am : element.getAnnotationMirrors()) {
            String type = am.getAnnotationType().toString();
            switch (type) {
                case "eu.exeris.sdk.annotation.capability.Provides" ->
                        provides.add(extractProvides(am));
                case "eu.exeris.sdk.annotation.capability.Provides.List" ->
                        forEachContained(am, contained -> provides.add(extractProvides(contained)));
                case "eu.exeris.sdk.annotation.capability.Requires" ->
                        requires.add(extractRequires(am));
                case "eu.exeris.sdk.annotation.capability.Requires.List" ->
                        forEachContained(am, contained -> requires.add(extractRequires(contained)));
                default -> { /* not a capability declaration */ }
            }
        }

        // @CapabilityLifecycle is @Target(TYPE); when present on the module class
        // itself, the module owns its own lifecycle. (A separate lifecycle-owner
        // class would need an SDK-side identity attribute, which does not exist.)
        String lifecycleOwner =
                findAnnotation(element, "eu.exeris.sdk.annotation.capability.CapabilityLifecycle") != null
                        ? element.getQualifiedName().toString()
                        : null;

        return CapabilityModuleMetadata.builder()
                .provides(List.copyOf(provides))
                .requires(List.copyOf(requires))
                .lifecycleOwner(lifecycleOwner)
                .build();
    }

    /** Iterates the {@code value} array of a {@code @Provides.List}/{@code @Requires.List} container. */
    private void forEachContained(AnnotationMirror container, java.util.function.Consumer<AnnotationMirror> action) {
        Object value = extractAnnotationValues(container).get(VALUE_ELEMENT);
        if (value instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof AnnotationMirror contained) {
                    action.accept(contained);
                }
            }
        }
    }

    private ProvidesMetadata extractProvides(AnnotationMirror annotation) {
        Map<String, Object> values = extractAnnotationValues(annotation);
        String service = serviceFqn(values.get("service"));
        String version = getString(values, VERSION_ATTRIBUTE, null);
        return new ProvidesMetadata(service, blankToNull(version));
    }

    private RequiresMetadata extractRequires(AnnotationMirror annotation) {
        Map<String, Object> values = extractAnnotationValues(annotation);
        String service = serviceFqn(values.get("service"));
        String versionRange = getString(values, "versionRange", null);
        boolean optional = getBoolean(values, "optional", false);
        return new RequiresMetadata(service, blankToNull(versionRange), optional);
    }

    /**
     * Resolves a {@code Class<?>} annotation attribute (a {@link TypeMirror}) to
     * the service interface's fully-qualified name. {@code @Provides}/{@code @Requires}
     * reference services by class literal, so a provider and a requirer of the
     * same interface yield the identical FQN — the key the capability graph
     * matches on.
     */
    private String serviceFqn(Object serviceValue) {
        if (serviceValue instanceof TypeMirror tm && tm instanceof DeclaredType dt) {
            return ((TypeElement) dt.asElement()).getQualifiedName().toString();
        }
        // void.class / unresolved — surface the raw form rather than dropping it.
        return serviceValue != null ? serviceValue.toString() : null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ---------------------------------------------------------------------
    // @View presentation IR extraction (RFC-2026-06-28).
    //
    // Mirrors the @CapabilityModule round: a separate, app-wide JSON family
    // (view_*.json) parallel to DomainMetadata, never nested in it. The SDK
    // resolved "class-structure-derived" composition (RFC-2026-06-25); this
    // processor fixes the exact walk (RFC-2026-06-28 §1):
    //
    //   @View class          → ViewMetadata
    //     member @Region     → RegionMetadata (slot = @Region.slot or field name;
    //                          source declaration order)
    //       region field TYPE is a region/block class whose members carry @Block
    //                          → that region's ComponentNodeMetadata list (decl order)
    //         @Block member   → ComponentNodeMetadata (type / customType / props
    //                          passthrough; binding from @Bind on the same member)
    //           @Block whose TYPE is itself block-shaped → children (recursive),
    //                          guarded by a visited-set against cycles
    //         @Bind-only member (no @Block) → a leaf binding node
    //
    // Blank annotation strings normalise to null (matching the SDK records'
    // compact constructors); enums come straight from the annotation. The leaf
    // field:UIFieldMetadata facet is left null in this slice (modelled by the
    // record, minimal emission per RFC §1).
    // ---------------------------------------------------------------------

    private static final String VIEW_FQN = "eu.exeris.sdk.annotation.View";
    private static final String REGION_FQN = "eu.exeris.sdk.annotation.Region";
    private static final String BLOCK_FQN = "eu.exeris.sdk.annotation.Block";
    private static final String BIND_FQN = "eu.exeris.sdk.annotation.Bind";

    private void processViewAnnotations(RoundEnvironment roundEnv) {
        TypeElement viewAnnotation = processingEnv.getElementUtils().getTypeElement(VIEW_FQN);
        if (viewAnnotation == null) {
            return;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(viewAnnotation)) {
            // @View is @Target(TYPE) — a class, record, or interface carrier.
            if (!(element instanceof TypeElement typeElement)) {
                error(element, "@View can only be applied to a type");
                continue;
            }
            processView(typeElement);
        }
    }

    private void processView(TypeElement element) {
        String name = element.getSimpleName().toString();
        String packageName = getPackageName(element);
        String qualifiedName = element.getQualifiedName().toString();

        note("Processing view: " + qualifiedName);

        try {
            ViewMetadata view = buildViewMetadata(element);
            writeMetadata("view_" + name,
                    new ViewJson(name, packageName, qualifiedName, view));

            // T11 / RFC-2026-06-28 §4: @View is now CONSUMED by the codegen-ts
            // presentation-IR emitter (view-gen), so it is no longer in
            // INERT_ANNOTATIONS — a @View-only compilation under -Aexeris.strict
            // emits no inert warning for @View. The call stays so any *other* inert
            // annotation on a @View type is still honestly flagged (Java∪TS union).
            auditAnnotations(element);

            note("Generated view metadata for: " + name);
        } catch (Exception e) {
            reportProcessingFailure(element, "Failed to process view", e);
        }
    }

    /**
     * Builds the {@link ViewMetadata} for a {@code @View} class by the
     * class-structure-derived walk (RFC-2026-06-28 §1). The {@code @View}
     * attributes (name / kind / route / title / titleKey / layout) come straight
     * off the annotation; the regions are derived from the class's
     * {@code @Region}-carrying members in source declaration order.
     */
    private ViewMetadata buildViewMetadata(TypeElement element) {
        AnnotationMirror viewAnnotation = findAnnotation(element, VIEW_FQN);
        Map<String, Object> values = viewAnnotation != null
                ? extractAnnotationValues(viewAnnotation)
                : Map.of();

        // name is required on @View; fall back to the simple name defensively.
        String name = nonBlankOr(getString(values, "name", null),
                element.getSimpleName().toString());

        ViewMetadata.Builder builder = ViewMetadata.builder(name)
                .kind(viewKind(values.get("kind")))
                .route(blankToNull(getString(values, "route", null)))
                .title(blankToNull(getString(values, "title", null)))
                .titleKey(blankToNull(getString(values, "titleKey", null)))
                .layout(blankToNull(getString(values, "layout", null)));

        // A visited-set guards the recursive block walk against cycles in the
        // class graph (a block class that reaches itself). Seed it with the view
        // root so a region/block typed as the view itself does not recurse forever.
        Set<String> visited = new HashSet<>();
        visited.add(element.getQualifiedName().toString());

        builder.regions(extractRegions(element, visited));
        return builder.build();
    }

    /**
     * The {@code @Region}-carrying members of {@code viewClass}, in source
     * declaration order, each as a {@link RegionMetadata}. The slot is
     * {@code @Region.slot} or the member name when blank; the region's components
     * are walked from the member's declared TYPE (a region/block-shaped class).
     */
    private List<RegionMetadata> extractRegions(TypeElement viewClass, Set<String> visited) {
        List<RegionMetadata> regions = new ArrayList<>();
        for (Element member : memberFieldsInOrder(viewClass)) {
            AnnotationMirror region = findAnnotation(member, REGION_FQN);
            if (region == null) {
                continue;
            }
            Map<String, Object> values = extractAnnotationValues(region);
            String slot = nonBlankOr(getString(values, "slot", null),
                    member.getSimpleName().toString());

            TypeElement regionType = declaredTypeElement(member.asType());
            List<ComponentNodeMetadata> components = regionType != null
                    ? extractComponents(regionType, visited)
                    : List.of();

            regions.add(new RegionMetadata(slot, components));
        }
        return regions;
    }

    /**
     * The {@code @Block} / {@code @Bind}-carrying members of {@code blockClass},
     * in source declaration order, each as a {@link ComponentNodeMetadata}. A
     * member carrying {@code @Block} becomes a block node (its {@code type} /
     * {@code customType} / {@code props} from the annotation, {@code binding} from
     * an optional {@code @Bind} on the same member); when its declared TYPE is
     * itself a block-shaped class the walk recurses into {@code children} (cycle
     * guarded). A member carrying only {@code @Bind} (no {@code @Block}) becomes a
     * leaf binding node.
     */
    private List<ComponentNodeMetadata> extractComponents(TypeElement blockClass, Set<String> visited) {
        // Cycle guard: if we are already inside this class on the current path,
        // stop — emit no children rather than recursing forever.
        if (!visited.add(blockClass.getQualifiedName().toString())) {
            return List.of();
        }
        try {
            List<ComponentNodeMetadata> components = new ArrayList<>();
            for (Element member : memberFieldsInOrder(blockClass)) {
                AnnotationMirror block = findAnnotation(member, BLOCK_FQN);
                AnnotationMirror bind = findAnnotation(member, BIND_FQN);

                if (block != null) {
                    components.add(blockNode(member, block, bind, visited));
                } else if (bind != null) {
                    // @Bind without @Block → a leaf binding node. No declared
                    // BlockType, so the record's CONTAINER default applies on read.
                    components.add(ComponentNodeMetadata.leaf(null, bindingOf(bind)));
                }
            }
            return components;
        } finally {
            // Pop the path entry so a sibling region/block of the same type is not
            // mistaken for a cycle (the guard is path-scoped, not global).
            visited.remove(blockClass.getQualifiedName().toString());
        }
    }

    /**
     * Builds one {@link ComponentNodeMetadata} from a {@code @Block} member:
     * {@code type} / {@code customType} / {@code props} from {@code @Block},
     * {@code binding} from an optional sibling {@code @Bind}, and {@code children}
     * by recursing into the member's declared TYPE when that type is itself
     * block-shaped.
     */
    private ComponentNodeMetadata blockNode(Element member,
                                            AnnotationMirror block,
                                            AnnotationMirror bind,
                                            Set<String> visited) {
        Map<String, Object> values = extractAnnotationValues(block);
        BlockType type = blockType(values.get("type"));
        String customType = blankToNull(getString(values, "customType", null));
        String props = blankToNull(getString(values, "props", null));
        BindingMetadata binding = bind != null ? bindingOf(bind) : null;

        TypeElement memberType = declaredTypeElement(member.asType());
        List<ComponentNodeMetadata> children = memberType != null
                ? extractComponents(memberType, visited)
                : List.of();

        return new ComponentNodeMetadata(type, customType, binding, props, children, null);
    }

    /** Builds a {@link BindingMetadata} from a {@code @Bind} mirror; blanks → null. */
    private BindingMetadata bindingOf(AnnotationMirror bind) {
        Map<String, Object> values = extractAnnotationValues(bind);
        return new BindingMetadata(
                bindSource(values.get("source")),
                blankToNull(getString(values, "ref", null)),
                blankToNull(getString(values, "path", null)),
                blankToNull(getString(values, "expression", null)),
                blankToNull(getString(values, "language", null)));
    }

    /**
     * Fields / record-components of {@code type} in source declaration order.
     * Under javac, {@link Element#getEnclosedElements()} returns members in the
     * order they appear in the source — the same ordering the field / capability
     * extraction already relies on — so the emitted regions / blocks are
     * deterministic (hard-constraint #3) without an explicit sort key.
     */
    private List<Element> memberFieldsInOrder(TypeElement type) {
        List<Element> members = new ArrayList<>();
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.FIELD
                    || enclosed.getKind() == ElementKind.RECORD_COMPONENT) {
                members.add(enclosed);
            }
        }
        return members;
    }

    /** Resolves a member's declared (class / record / interface) type, or null for primitives / arrays / type vars. */
    private TypeElement declaredTypeElement(TypeMirror type) {
        if (type instanceof DeclaredType declaredType
                && declaredType.asElement() instanceof TypeElement typeElement) {
            return typeElement;
        }
        return null;
    }

    /** Maps a {@code @View.Kind} enum constant (a {@link VariableElement}) to the AST {@link ViewKind}; null when unset. */
    private ViewKind viewKind(Object value) {
        String constant = enumConstantName(value);
        return constant != null ? ViewKind.valueOf(constant) : null;
    }

    /** Maps a {@code @Block.BlockType} enum constant to the AST {@link BlockType}; null when unset. */
    private BlockType blockType(Object value) {
        String constant = enumConstantName(value);
        return constant != null ? BlockType.valueOf(constant) : null;
    }

    /**
     * Maps an {@code @ExerisDomain.DataScope} enum constant to the AST
     * {@link DataScope}, or null when no tier was declared.
     *
     * <p>Null covers two cases the AST expresses identically — the attribute
     * absent, and the attribute written as {@code UNSPECIFIED}. That constant
     * exists only on the annotation side, because an annotation attribute
     * cannot default to {@code null}; the AST expresses "no tier declared" as
     * an absent field and therefore has no {@code UNSPECIFIED} (ADR-059).
     * Everything else round-trips by constant name, the
     * {@code SagaStep.StepKind} precedent.
     */
    private DataScope dataScope(Object value) {
        String constant = enumConstantName(value);
        if (constant == null || "UNSPECIFIED".equals(constant)) {
            return null;
        }
        return DataScope.valueOf(constant);
    }

    /**
     * The tier the deprecated {@code tenantScoped} boolean stands for. The
     * mapping is total, which is what makes the contradiction check exact:
     * declaring a tier and a boolean that means a different tier is an error,
     * never a precedence puzzle.
     */
    private static DataScope fallbackTier(boolean tenantScoped) {
        return tenantScoped ? DataScope.TENANT : DataScope.GLOBAL;
    }

    /**
     * Warns that a build is still resolving its tier through the deprecated
     * boolean. ADR-059 obligation 5 requires the fallback to be audible: a
     * silent equivalence would let the whole deprecation window pass without
     * anyone noticing the attribute is going away at 1.0.0.
     */
    private void warnDeprecatedTenantScoped(TypeElement element, boolean tenantScoped) {
        DataScope tier = fallbackTier(tenantScoped);
        messager.printMessage(
                Diagnostic.Kind.WARNING,
                DIAG_PREFIX + "@ExerisDomain.tenantScoped is deprecated for removal in SDK 1.0.0; "
                        + "declare dataScope = DataScope." + tier + " instead. Reading the boolean "
                        + "as a fallback for this build (tenantScoped = " + tenantScoped + " → "
                        + tier + "). See MIGRATION.md in exeris-sdk.",
                element);
    }

    /**
     * Rejects a declared tier that disagrees with a declared {@code tenantScoped}.
     * ADR-059 rules this a build error rather than something to resolve by
     * precedence: whichever side lost would be a silent tenancy decision, and the
     * author has already said two different things about one entity.
     */
    private void errorContradictingDataScope(
            TypeElement element, DataScope declared, boolean tenantScoped) {
        error(element,
                "@ExerisDomain declares dataScope = DataScope." + declared
                        + " and tenantScoped = " + tenantScoped + ", which contradict each other — "
                        + "tenantScoped = " + tenantScoped + " means DataScope."
                        + fallbackTier(tenantScoped) + ". Declare the tier once: drop tenantScoped, "
                        + "which is deprecated for removal in SDK 1.0.0.");
    }

    /**
     * Refuses a {@code UNIVERSE} declaration at the declaration site (T29).
     *
     * <p>This was a WARNING until 0.8.0, on the reasoning that the emitted TENANT
     * shape is UNIVERSE minus the read-widen — strictly narrower than declared,
     * never wider — so a warning was enough. Failing closed is still the right
     * policy and {@code DataScopeSupport.isTenantPartitioned} keeps it: treating
     * the tier as GLOBAL would drop the owner column and the policy altogether
     * and publish rows the author scoped to an owner.
     *
     * <p>What the warning missed is that the narrowing does not merely
     * under-deliver — on the archetypal UNIVERSE entity it does not build. A
     * shared-world row is precisely one with no tenant property, and the TENANT
     * shape binds {@code entity.getTenantId()} in the emitted repository. So the
     * author's reward for declaring the tier is {@code cannot find symbol}
     * inside a generated file they are told not to edit, pointing at a getter
     * they were never asked to write. A diagnostic at the declaration, naming the
     * tier and the reason, is strictly better than a compile error two artefacts
     * downstream.
     *
     * <p>An emitted RLS policy names a PostgreSQL session variable; it does not call an
     * accessor. At the pinned kernel {@code v0.11.0} {@code exeris.tenant_id} is named in
     * SPI, Core and the TCK, while {@code exeris.shared_scope} is named only in
     * {@code exeris-kernel-community}. The persistence driver is swappable, so a migration
     * the consumer commits may not depend on one driver's internal literal. Kernel 0.12
     * promotes both to constants on {@code ConnectionInterceptor}; this refusal is gated on
     * that pin (B0), and on an SDK carrier naming the field that holds a row's shared-scope
     * value — without one every row keeps the column's {@code ''} default and UNIVERSE is
     * behaviourally TENANT.
     */
    private void errorReservedUniverseTier(TypeElement element) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                DIAG_PREFIX + "@ExerisDomain.dataScope = DataScope.UNIVERSE is reserved and is "
                        + "refused here rather than half-emitted. The session variable a "
                        + "shared-scope policy must read is named only inside the Community "
                        + "driver on this kernel pin, so there is nothing contracted to emit "
                        + "against and this tier would fall back to the TENANT shape: "
                        + "an owner column, an owner-pinned policy, and a repository that binds "
                        + "getTenantId(). A shared-world row has no tenant property, so that build "
                        + "fails with 'cannot find symbol' inside generated code you are told not "
                        + "to edit. Declare dataScope = TENANT if the entity really is partitioned "
                        + "by an owner (and give it a tenant property); there is no way to obtain "
                        + "cross-tenant read-widening from this build yet. "
                        + "See ADR-059 (docs/adr/ADR-059.link.md).",
                element);
    }

    /** Maps a {@code @Bind.Source} enum constant to the AST {@link BindSource}; null when unset. */
    private BindSource bindSource(Object value) {
        String constant = enumConstantName(value);
        return constant != null ? BindSource.valueOf(constant) : null;
    }

    /**
     * The simple constant name of an enum-typed annotation attribute. javac
     * surfaces an enum attribute value as a {@link VariableElement} (the enum
     * constant); its simple name is the constant. The annotation and AST enums
     * share their constant names by contract (the SDK enums "mirror" the
     * annotation ones), so the name round-trips through {@code Enum.valueOf}.
     * Returns null when the attribute was not written (default applies).
     */
    private String enumConstantName(Object value) {
        if (value instanceof VariableElement enumConstant) {
            return enumConstant.getSimpleName().toString();
        }
        return null;
    }

    private void processDomainEntity(TypeElement element) {
        String entityName = element.getSimpleName().toString();
        String packageName = getPackageName(element);
        String fqn = element.getQualifiedName().toString();

        note("Processing domain entity: " + fqn);

        try {
            // Build full metadata using DomainMetadata model
            DomainMetadata metadata = buildFullDomainMetadata(element, entityName, packageName);

            // Write JSON metadata file + ADR-042 baseline-trust sibling fields
            writeDomainMetadataWithTrust(entityName, metadata, element);

            note("Generated metadata for: " + entityName);
        } catch (Exception e) {
            reportProcessingFailure(element, "Failed to process domain entity", e);
        }
    }

    private void processSaga(TypeElement element) {
        String sagaName = element.getSimpleName().toString();
        String packageName = getPackageName(element);

        note("Processing saga: " + sagaName);

        try {
            // Extract saga metadata
            SagaMetadata sagaMetadata = extractSagaMetadata(element);

            // Build domain metadata with saga configuration
            DomainMetadata metadata = DomainMetadata.builder(sagaName, packageName)
                    .sagaMetadata(sagaMetadata)
                    .build();

            // A standalone @Saga still emits a DomainMetadata JSON, so stamp the
            // ADR-042 baseline-trust fields here too — same treatment as @ExerisDomain,
            // so no exeris-metadata/*.json is left without a trust stamp.
            writeDomainMetadataWithTrust(sagaName, metadata, element);
            note("Generated saga metadata for: " + sagaName);
        } catch (Exception e) {
            reportProcessingFailure(element, "Failed to process saga", e);
        }
    }

    private DomainMetadata buildFullDomainMetadata(TypeElement element, String entityName, String packageName) {
        DomainMetadata.Builder builder = DomainMetadata.builder(entityName, packageName);

        // Extract @ExerisDomain annotation values
        AnnotationMirror domainAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.ExerisDomain");
        if (domainAnnotation != null) {
            extractDomainAnnotationValues(domainAnnotation, builder, element);
        }

        // Extract fields with @Field annotations
        List<FieldMetadata> fields = extractFieldsMetadata(element);
        builder.fields(fields);

        // Extract actions with @Action annotations
        List<ActionMetadata> actions = extractActionsMetadata(element);
        builder.actions(actions);

        // Extract events with @DomainEvent annotations
        List<DomainEventMetadata> events = extractEventsMetadata(element);
        builder.events(events);

        // Extract relationships with @Relationship annotations
        List<RelationshipMetadata> relationships = extractRelationshipsMetadata(element);
        builder.relationships(relationships);

        // Extract UI metadata
        UIMetadata uiMetadata = extractUIMetadata(element);
        if (uiMetadata != null) {
            builder.uiMetadata(uiMetadata);
        }

        // Extract graph metadata
        GraphMetadata graphMetadata = extractGraphMetadata(element);
        if (graphMetadata != null) {
            builder.graphMetadata(graphMetadata);
        }

        // Extract event sourcing metadata
        EventSourcedMetadata eventSourced = extractEventSourcedMetadata(element);
        if (eventSourced != null) {
            builder.eventSourced(eventSourced);
        }

        // Check for saga configuration
        SagaMetadata sagaMetadata = extractSagaMetadata(element);
        if (sagaMetadata != null) {
            builder.sagaMetadata(sagaMetadata);
        }

        // Check for internal API configuration
        InternalApiMetadata internalApi = extractInternalApiMetadata(element);
        if (internalApi != null) {
            builder.internalApi(internalApi);
        }

        // T11: under -Aexeris.strict, flag type-level annotations that are
        // extracted above but consumed by no generator (e.g. @EventSourced).
        auditAnnotations(element);

        return builder.build();
    }

    private void extractDomainAnnotationValues(
            AnnotationMirror annotation, DomainMetadata.Builder builder, TypeElement element) {
        Map<String, Object> values = extractAnnotationValues(annotation);
        warnInertAttributes("ExerisDomain", values, element, annotation);

        // Identity
        if (values.containsKey("module")) {
            builder.module((String) values.get("module"));
        }
        if (values.containsKey("path")) {
            builder.path((String) values.get("path"));
        }
        if (values.containsKey("aggregate")) {
            builder.aggregate((String) values.get("aggregate"));
        }
        if (values.containsKey("description")) {
            builder.description((String) values.get("description"));
        }
        if (values.containsKey("apiVersion")) {
            builder.apiVersion((String) values.get("apiVersion"));
        }

        // API Configuration
        if (values.containsKey("restApi")) {
            builder.restApi((Boolean) values.get("restApi"));
        }
        if (values.containsKey("graphqlApi")) {
            builder.graphqlApi((Boolean) values.get("graphqlApi"));
        }
        if (values.containsKey("realTimeApi")) {
            builder.realTimeApi((Boolean) values.get("realTimeApi"));
        }
        if (values.containsKey("internalClient")) {
            builder.internalClient((Boolean) values.get("internalClient"));
        }

        // Data Management
        //
        // ADR-059: `dataScope` is the canonical data-scope tier and
        // `tenantScoped` is its deprecated predecessor. Both are extracted —
        // the AST keeps the raw boolean so a pre-0.10.0 baseline reads back
        // with exactly the meaning it always had — but no generator reads
        // either directly. `DomainMetadata.effectiveDataScope()` is the single
        // canonical read (explicit tier wins, else the boolean's fallback).
        DataScope declaredScope = dataScope(values.get("dataScope"));
        if (declaredScope != null) {
            builder.dataScope(declaredScope);
        }
        boolean contradicted = false;
        if (values.containsKey("tenantScoped")) {
            boolean tenantScoped = (Boolean) values.get("tenantScoped");
            builder.tenantScoped(tenantScoped);
            if (declaredScope == null) {
                warnDeprecatedTenantScoped(element, tenantScoped);
            } else if (declaredScope != fallbackTier(tenantScoped)) {
                errorContradictingDataScope(element, declaredScope, tenantScoped);
                contradicted = true;
            }
        }
        // T29: UNIVERSE is refused at the declaration. Still suppressed when the
        // declaration is already contradicted — two errors about one line, the second
        // describing an emission that a fixed declaration may never reach, is noise on
        // top of an error the author has to fix first.
        if (declaredScope == DataScope.UNIVERSE && !contradicted) {
            errorReservedUniverseTier(element);
        }
        if (values.containsKey("softDelete")) {
            builder.softDelete((Boolean) values.get("softDelete"));
        }
        if (values.containsKey("audited")) {
            builder.audited((Boolean) values.get("audited"));
        }
        if (values.containsKey("versioned")) {
            builder.versioned((Boolean) values.get("versioned"));
        }

        // Security
        if (values.containsKey("sensitive")) {
            builder.sensitive((Boolean) values.get("sensitive"));
        }

        // Caching
        if (values.containsKey("cacheable")) {
            builder.cacheable((Boolean) values.get("cacheable"));
        }
        if (values.containsKey("cacheTtl")) {
            builder.cacheTtl((String) values.get("cacheTtl"));
        }
        if (values.containsKey("cacheRegion")) {
            builder.cacheRegion((String) values.get("cacheRegion"));
        }

        // Search
        if (values.containsKey("fullTextSearch")) {
            builder.fullTextSearch((Boolean) values.get("fullTextSearch"));
        }
        if (values.containsKey("searchConfig")) {
            builder.searchConfig((String) values.get("searchConfig"));
        }

        // @ExerisDomain has no tableName attribute (see exeris-sdk-
        // annotations) — the previous containsKey("tableName") check
        // was unreachable and was removed in PR #45.

        // System fields, from two sources (T5 + C1). Only build a SystemFieldsMetadata when
        // something was actually declared; otherwise leave it null so the default-case JSON is
        // byte-identical to pre-T5 output (determinism invariant).
        SystemFieldsMetadata systemFields = resolveSystemFields(values, element);
        if (systemFields != null) {
            builder.systemFields(systemFields);
        }
    }

    /**
     * One system-field role: the {@code annotation.system} annotation that declares it on a field,
     * and the {@code @ExerisDomain} attribute that names the same field remotely.
     */
    private record SystemFieldRole(String annotation, String overrideAttribute) {
        String fqn() {
            return "eu.exeris.sdk.annotation.system." + annotation;
        }
    }

    /**
     * The nine roles read from {@code annotation.system.*} (C1). Ordered, and iterated in this
     * order, so a diagnostic sequence is stable across runs.
     *
     * <p><b>{@code @PrimaryKey} is deliberately absent.</b> Its component,
     * {@code SystemFieldsMetadata.primaryKeyField}, is the one no generator honours — the schema
     * emits {@code id UUID PRIMARY KEY} unconditionally, the repository identifies rows through
     * {@code " WHERE id = ?"}, and every by-id handler binds {@code {id}}. Extracting it would move
     * the annotation out of the never-read audit while leaving its effect at zero, which is the
     * failure mode this repository spent 0.8.0 removing. It stays unextracted, and C0 keeps
     * reporting it, until the slice that renames the key across the SQL, the repository and the
     * route template lands.
     */
    private static final List<SystemFieldRole> SYSTEM_FIELD_ROLES = List.of(
            new SystemFieldRole("TenantId", "tenantIdField"),
            new SystemFieldRole("Version", "versionField"),
            new SystemFieldRole("SoftDelete", "softDeleteField"),
            new SystemFieldRole("SoftDeleteTimestamp", "softDeleteTimestampField"),
            new SystemFieldRole("SoftDeletedBy", "softDeletedByField"),
            new SystemFieldRole("AuditCreatedAt", "createdAtField"),
            new SystemFieldRole("AuditCreatedBy", "createdByField"),
            new SystemFieldRole("AuditUpdatedAt", "updatedAtField"),
            new SystemFieldRole("AuditUpdatedBy", "updatedByField"));

    /**
     * Which field plays each system role, from the two sources that can say so: a
     * {@code annotation.system} annotation on the field itself (C1) and the matching
     * {@code @ExerisDomain} override attribute (T5).
     *
     * <p>Returns {@code null} when neither source declared anything, so the record stays absent and
     * the emitted JSON is byte-identical to the default case.
     *
     * <p><b>Two refusals, both at the declaration.</b> Several fields carrying one role cannot be
     * compiled — {@code SystemFieldsMetadata} holds one name per role — and neither can an override
     * naming a different field than the annotation does. Accepting either would produce metadata
     * that builds here and contradicts itself downstream, which is the shape S3 refused for a
     * repeated {@code @GraphEdge}.
     */
    private SystemFieldsMetadata resolveSystemFields(Map<String, Object> values, TypeElement element) {
        Map<String, String> declared = new LinkedHashMap<>();

        for (SystemFieldRole role : SYSTEM_FIELD_ROLES) {
            List<VariableElement> carriers = new ArrayList<>();
            for (Element enclosed : element.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) continue;
                AnnotationMirror mirror = findAnnotation(enclosed, role.fqn());
                if (mirror == null) continue;

                warnInertAttributes(role.annotation(), extractAnnotationValues(mirror), enclosed, mirror);
                carriers.add((VariableElement) enclosed);
            }
            if (carriers.isEmpty()) continue;

            if (carriers.size() > 1) {
                // One diagnostic naming the whole set, not one per field past the first. The
                // author has to choose which single field survives, and cannot choose from a
                // list that repeats the same "first" field against every later one.
                VariableElement offender = carriers.get(1);
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        DIAG_PREFIX + "@" + role.annotation() + " is declared on " + carriers.size()
                                + " fields (" + quotedNames(carriers) + "). SystemFieldsMetadata "
                                + "carries one field name per role, so the pipeline cannot express "
                                + "more than one. Declare it once.",
                        offender, findAnnotation(offender, role.fqn()));
                continue;
            }

            VariableElement carrier = carriers.getFirst();
            String annotated = carrier.getSimpleName().toString();
            String override = blankToNull(getString(values, role.overrideAttribute(), null));
            if (override != null && !override.equals(annotated)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        DIAG_PREFIX + "@" + role.annotation() + " is on field '" + annotated
                                + "' while @ExerisDomain(" + role.overrideAttribute() + " = \""
                                + override + "\") names a different one. One role resolves to one "
                                + "field; drop whichever is wrong.",
                        carrier, findAnnotation(carrier, role.fqn()));
                continue;
            }
            declared.put(role.overrideAttribute(), annotated);
        }

        return extractSystemFieldsOverrides(values, declared);
    }

    /** {@code 'a', 'b', 'c'} — the fields carrying one role, in declaration order. */
    private static String quotedNames(List<VariableElement> fields) {
        return fields.stream()
                .map(f -> "'" + f.getSimpleName() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * Builds a {@link SystemFieldsMetadata} from the {@code @ExerisDomain} override attributes and
     * the field roles resolved above. Returns {@code null} when neither source said anything.
     *
     * <p>Unset components are filled from {@link SystemFieldsMetadata#defaults()} so the record is
     * internally complete. {@code primaryKeyField} defaults to {@code "id"} and is carried for
     * completeness; no generator reads it (see {@link #SYSTEM_FIELD_ROLES}).
     */
    private SystemFieldsMetadata extractSystemFieldsOverrides(
            Map<String, Object> values, Map<String, String> declared) {
        SystemFieldsMetadata d = SystemFieldsMetadata.defaults();

        // The annotation default for primaryKeyField is "id"; everything else
        // is "". A blank value is treated as "not overridden".
        String primaryKeyField = nonBlankOr(getString(values, "primaryKeyField", null), d.primaryKeyField());
        String tenantIdField = resolved(declared, values, "tenantIdField", d.tenantIdField());
        String softDeleteField = resolved(declared, values, "softDeleteField", d.softDeleteField());
        String softDeleteTimestampField = resolved(declared, values, "softDeleteTimestampField", d.softDeleteTimestampField());
        String softDeletedByField = resolved(declared, values, "softDeletedByField", d.softDeletedByField());
        String versionField = resolved(declared, values, "versionField", d.versionField());
        String createdAtField = resolved(declared, values, "createdAtField", d.createdAtField());
        String createdByField = resolved(declared, values, "createdByField", d.createdByField());
        String updatedAtField = resolved(declared, values, "updatedAtField", d.updatedAtField());
        String updatedByField = resolved(declared, values, "updatedByField", d.updatedByField());

        // Did the user explicitly override anything (other than the implicit
        // primaryKeyField="id" annotation default)? primaryKeyField counts only
        // if it was written AND differs from the default "id".
        boolean anyOverride =
                isExplicitNonBlank(values, "tenantIdField")
                || isExplicitNonBlank(values, "softDeleteField")
                || isExplicitNonBlank(values, "softDeleteTimestampField")
                || isExplicitNonBlank(values, "softDeletedByField")
                || isExplicitNonBlank(values, "versionField")
                || isExplicitNonBlank(values, "createdAtField")
                || isExplicitNonBlank(values, "createdByField")
                || isExplicitNonBlank(values, "updatedAtField")
                || isExplicitNonBlank(values, "updatedByField")
                || (isExplicitNonBlank(values, "primaryKeyField")
                        && !"id".equals(getString(values, "primaryKeyField", "id")));

        if (!anyOverride && declared.isEmpty()) {
            return null;
        }

        return new SystemFieldsMetadata(
                primaryKeyField, createdAtField, createdByField,
                updatedAtField, updatedByField, tenantIdField,
                versionField, softDeleteField, softDeleteTimestampField, softDeletedByField);
    }

    private static String nonBlankOr(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    /**
     * The field playing one role: what an {@code annotation.system} annotation declared, else the
     * {@code @ExerisDomain} override, else the canonical default. The two sources cannot disagree
     * here — {@link #resolveSystemFields} refuses that at the declaration.
     */
    private static String resolved(Map<String, String> declared, Map<String, Object> values,
                                   String attribute, String fallback) {
        String fromAnnotation = declared.get(attribute);
        return fromAnnotation != null
                ? fromAnnotation
                : nonBlankOr(getString(values, attribute, null), fallback);
    }

    private static boolean isExplicitNonBlank(Map<String, Object> values, String key) {
        Object v = values.get(key);
        return v instanceof String s && !s.isBlank();
    }

    private List<FieldMetadata> extractFieldsMetadata(TypeElement element) {
        List<FieldMetadata> fields = new ArrayList<>();

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) enclosed;

            // Detect and collect enum types
            collectEnumType(field.asType());

            // D6: the inert-annotation sweep sits in the loop, NOT inside
            // extractFieldMetadata — that extractor is reached only when @Field is
            // present, and a field-level inert annotation on a field without @Field
            // is a real shape (@Blob describes a byte carrier, not a column). The
            // else branch below still admits such a field to the AST through
            // FieldMetadata.simple(...), so gating this call on @Field would suppress
            // a warning for a field that is nonetheless in the model.
            auditAnnotations(field);

            AnnotationMirror fieldAnnotation = findAnnotation(field, "eu.exeris.sdk.annotation.Field");

            if (fieldAnnotation != null) {
                fields.add(extractFieldMetadata(field, fieldAnnotation));
            } else {
                // Add basic field metadata even without @Field annotation
                fields.add(FieldMetadata.simple(
                        field.getSimpleName().toString(),
                        field.asType().toString()
                ));
            }
        }

        return fields;
    }

    private void collectEnumType(TypeMirror typeMirror) {
        // Handle declared types (classes, enums, interfaces)
        if (typeMirror instanceof DeclaredType declaredType) {
            Element typeElement = declaredType.asElement();
            if (typeElement.getKind() == ElementKind.ENUM) {
                discoveredEnums.add((TypeElement) typeElement);
            }
            // Also check generic type arguments (e.g., List<Status>)
            for (TypeMirror typeArg : declaredType.getTypeArguments()) {
                collectEnumType(typeArg);
            }
        }
    }

    private FieldMetadata extractFieldMetadata(VariableElement field, AnnotationMirror annotation) {
        String name = field.getSimpleName().toString();
        String type = field.asType().toString();

        FieldMetadata.Builder builder = FieldMetadata.builder(name, type);
        Map<String, Object> values = extractAnnotationValues(annotation);
        warnInertAttributes("Field", values, field, annotation);

        // @Field attribute surface (see exeris-sdk-annotations Field.java).
        // Each check below is verified live against the SDK declaration
        // and exercised by FieldAttributeMatrixTests. Attributes the
        // processor checks elsewhere but @Field does NOT declare
        // (columnName, hidden, minLength, maxLength) remain absent —
        // their containsKey checks were genuinely unreachable and are
        // not restored. The min/max/pattern reads in
        // applyDeprecatedValidationFallbacks come from @Validation,
        // not @Field.
        if (values.containsKey("label")) builder.displayName((String) values.get("label"));
        if (values.containsKey("description")) builder.description((String) values.get("description"));
        if (values.containsKey("required")) builder.required((Boolean) values.get("required"));
        if (values.containsKey("unique")) builder.unique((Boolean) values.get("unique"));
        if (values.containsKey("indexed")) builder.indexed((Boolean) values.get("indexed"));
        if (values.containsKey("searchable")) builder.searchable((Boolean) values.get("searchable"));
        if (values.containsKey("sortable")) builder.sortable((Boolean) values.get("sortable"));
        if (values.containsKey("filterable")) builder.filterable((Boolean) values.get("filterable"));
        if (values.containsKey("readOnly")) builder.readOnly((Boolean) values.get("readOnly"));
        if (values.containsKey("inCreate")) builder.inCreate((Boolean) values.get("inCreate"));
        if (values.containsKey("inUpdate")) builder.inUpdate((Boolean) values.get("inUpdate"));
        // @Field.dataType — the front-presentation type hint (currency/percent/url/…).
        // The builder normalizes blank -> null, so an explicit "" default does not
        // survive on the wire under @JsonInclude(NON_DEFAULT) (determinism-safe).
        if (values.containsKey("dataType")) builder.dataType((String) values.get("dataType"));

        // Computed fields (only computed + computedFrom on @Field).
        if (values.containsKey("computed")) builder.computed((Boolean) values.get("computed"));
        if (values.containsKey("computedFrom")) {
            // extractAnnotationValues unwraps array attributes from
            // List<AnnotationValue> to List<Object>; for String[] each
            // element is already a String, so the cast below is safe.
            // (Previously this site used `instanceof String[]` which never
            // matched the javac-surfaced List form — silently dropping
            // every user-supplied computedFrom value.)
            Object computedFromValue = values.get("computedFrom");
            if (computedFromValue instanceof List<?> list) {
                List<String> strings = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
                builder.computedFrom(strings);
            }
        }

        // Check for validation annotation
        AnnotationMirror validationAnnotation = findAnnotation(field, "eu.exeris.sdk.annotation.Validation");
        if (validationAnnotation != null) {
            Map<String, Object> validationValues = extractAnnotationValues(validationAnnotation);
            if (validationValues.containsKey("min")) builder.min(((Number) validationValues.get("min")).longValue());
            if (validationValues.containsKey("max")) builder.max(((Number) validationValues.get("max")).longValue());
            // Only explicitly-set attributes appear in the values map (annotation
            // defaults are excluded), so reading minLength/maxLength here does NOT
            // flood every @Validation field with the 0 / Integer.MAX_VALUE defaults.
            if (validationValues.containsKey("minLength")) builder.minLength(((Number) validationValues.get("minLength")).intValue());
            if (validationValues.containsKey("maxLength")) builder.maxLength(((Number) validationValues.get("maxLength")).intValue());
            if (validationValues.containsKey("pattern")) builder.pattern((String) validationValues.get("pattern"));

            applyDeprecatedValidationFallbacks(field, values, validationValues, builder);
        }

        return builder.build();
    }

    /**
     * Read-and-warn fallback for the two attributes deprecated in SDK 0.2.0:
     * {@code @Validation.required} and {@code @Validation.validateOn}. Both
     * are scheduled for removal in SDK 1.0.0. During the 0.2.x window we carry
     * the value over to the canonical {@code @Field} attribute when the
     * canonical one is unset, and emit a build warning so users see a
     * mechanical migration path before 1.0.0 turns the silent drop into a
     * footgun.
     *
     * <p><strong>Why {@code @Validation(required = false)} does not warn:</strong>
     * {@code false} is the annotation default — a user who writes it
     * explicitly is using the deprecated attribute meaninglessly (no
     * required-ness is conveyed either way). Warning here would nag without
     * giving the user anything to fix on their side beyond removing a no-op
     * attribute. The {@code forRemoval=true} on the SDK side already produces
     * a javac removal warning at the call site, which is sufficient nudge.
     *
     * <p><strong>Unrecognized {@code validateOn} values:</strong> only
     * {@code "CREATE"} and {@code "UPDATE"} are mapped. Any other non-empty
     * string emits an additional warning so the user sees that their intent
     * is being silently dropped during the deprecation window — not at SDK
     * 1.0.0, when the attribute is gone and the silent drop is permanent.
     */
    private void applyDeprecatedValidationFallbacks(
            VariableElement field,
            Map<String, Object> fieldValues,
            Map<String, Object> validationValues,
            FieldMetadata.Builder builder) {

        if (validationValues.containsKey("required")) {
            Boolean validationRequired = (Boolean) validationValues.get("required");
            if (Boolean.TRUE.equals(validationRequired)) {
                if (!fieldValues.containsKey("required")) {
                    builder.required(true);
                }
                warnDeprecatedValidationAttribute(field, "required", "@Field.required",
                        "required-ness is a field-shape property, not a validation rule");
            }
        }

        if (validationValues.containsKey("validateOn")) {
            String validateOn = (String) validationValues.get("validateOn");
            if (validateOn != null && !validateOn.isEmpty()) {
                boolean recognized = "CREATE".equals(validateOn) || "UPDATE".equals(validateOn);
                if ("CREATE".equals(validateOn) && !fieldValues.containsKey("inUpdate")) {
                    builder.inUpdate(false);
                } else if ("UPDATE".equals(validateOn) && !fieldValues.containsKey("inCreate")) {
                    builder.inCreate(false);
                }
                warnDeprecatedValidationAttribute(field, "validateOn",
                        "@Field.inCreate / @Field.inUpdate",
                        "form-lifecycle scope is a field property, not a validation rule");
                if (!recognized) {
                    messager.printMessage(
                            Diagnostic.Kind.WARNING,
                            DIAG_PREFIX + "@Validation.validateOn = \"" + validateOn + "\" is not a "
                                    + "recognized value (expected \"CREATE\" or \"UPDATE\"); "
                                    + "no fallback applied — your intent is being silently "
                                    + "dropped now and will continue to be when SDK 1.0.0 "
                                    + "removes the attribute. Migrate to @Field.inCreate / "
                                    + "@Field.inUpdate.",
                            field);
                }
            }
        }
    }

    private void warnDeprecatedValidationAttribute(
            VariableElement field, String attribute, String canonical, String reason) {
        messager.printMessage(
                Diagnostic.Kind.WARNING,
                DIAG_PREFIX + "@Validation." + attribute + " is deprecated for removal in SDK 1.0.0; "
                        + "use " + canonical + " instead — " + reason
                        + ". See MIGRATION.md in exeris-sdk.",
                field);
    }

    private List<ActionMetadata> extractActionsMetadata(TypeElement element) {
        List<ActionMetadata> actions = new ArrayList<>();

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;

            ExecutableElement method = (ExecutableElement) enclosed;

            // D6: in the loop rather than in extractActionMetadata, for the reason
            // spelled out at the field sweep — a method-level inert annotation must
            // not have its reachability decided by whether @Action is also present.
            auditAnnotations(method);

            AnnotationMirror actionAnnotation = findAnnotation(method, "eu.exeris.sdk.annotation.Action");

            if (actionAnnotation != null) {
                actions.add(extractActionMetadata(method, actionAnnotation));
            }
        }

        return actions;
    }

    private ActionMetadata extractActionMetadata(ExecutableElement method, AnnotationMirror annotation) {
        Map<String, Object> values = extractAnnotationValues(annotation);
        warnInertAttributes("Action", values, method, annotation);

        // T3: action identity is the @Action(name=…) attribute (required on the SDK
        // annotation, so always present), NOT the method name. Reading the method
        // name made a @Action(name="approve") on a bean-setter-shaped method (e.g.
        // void setFormation(Formation)) collide with the generated setter. Fall back
        // to the method name only defensively, if a blank name ever reaches here.
        String declaredName = getString(values, "name", null);
        String name = (declaredName != null && !declaredName.isBlank())
                ? declaredName
                : method.getSimpleName().toString();

        ActionMetadata.Builder builder = ActionMetadata.builder(name)
                // T1: carry the real JVM method name so the handler generator can emit a
                // server-side dispatch that invokes the actual aggregate method. Distinct
                // from `name` (the @Action(name=…) identity), which T3 decoupled and may
                // differ (e.g. renamed to dodge a bean-accessor collision).
                .methodName(method.getSimpleName().toString());

        // @Action attribute surface (see exeris-sdk-annotations Action.java).
        // Each check below is verified live against the SDK declaration
        // and exercised by ActionAttributeMatrixTests. Attributes the
        // processor checked elsewhere but @Action does NOT declare
        // (displayName, idempotent, dangerous, requiresConfirmation)
        // remain absent — their containsKey checks were genuinely
        // unreachable and are not restored.
        if (values.containsKey("description")) builder.description((String) values.get("description"));
        if (values.containsKey("httpMethod")) builder.httpMethod((String) values.get("httpMethod"));
        if (values.containsKey("async")) builder.async((Boolean) values.get("async"));

        // ADR-044 Slice 2: the per-action streaming driver. @Action(streaming=true)
        // (boolean, default false) + @Action(streamEventType=…) (String, default "")
        // are verified live against exeris-sdk-annotations Action.java, like the
        // attributes above. They drive KernelActionStreamHandlerGenerator (one
        // HttpStreamHandler per streaming action) + the Application generator's
        // streamRoute(POST, {base}/{id}/actions/{kebab}, …) registration, and the
        // TS RxJS streaming-action client.
        if (values.containsKey("streaming")) builder.streaming((Boolean) values.get("streaming"));
        if (values.containsKey("streamEventType")) builder.streamEventType((String) values.get("streamEventType"));
        // NOTE: @Action(realTimeUpdates) is deliberately NOT extracted here. It is a
        // separate "subscribe-to-progress" affordance (response shape vs. progress
        // channel) with no generator consumer in Slice 2 — extracting it would only
        // create an inert ActionMetadata attribute. Out of Slice-2 scope; add the
        // extraction in the same change that introduces its consumer.

        // Extract parameters
        List<ActionParamMetadata> params = new ArrayList<>();
        for (VariableElement param : method.getParameters()) {
            // Outside the @ActionParam gate, for the reason the field and method sweeps give:
            // @QueryParam on an otherwise-unannotated parameter is precisely the shape C0 exists
            // to report, and gating the audit on @ActionParam would hide it.
            auditAnnotations(param);

            AnnotationMirror paramAnnotation = findAnnotation(param, "eu.exeris.sdk.annotation.ActionParam");
            if (paramAnnotation != null) {
                params.add(extractActionParamMetadata(param, paramAnnotation));
            }
        }
        builder.params(params);

        return builder.build();
    }

    private ActionParamMetadata extractActionParamMetadata(VariableElement param, AnnotationMirror annotation) {
        String name = param.getSimpleName().toString();
        String type = param.asType().toString();
        Map<String, Object> values = extractAnnotationValues(annotation);
        warnInertAttributes("ActionParam", values, param, annotation);

        // Default `required = true` mirrors @ActionParam.required's annotation
        // default (verified against exeris-sdk-annotations:ActionParam).
        return ActionParamMetadata.builder(name, type)
                .displayName(getString(values, "displayName", null))
                .description(getString(values, "description", null))
                .required(getBoolean(values, "required", true))
                .build();
    }

    private List<DomainEventMetadata> extractEventsMetadata(TypeElement element) {
        List<DomainEventMetadata> events = new ArrayList<>();

        // Check all annotations on the element
        for (AnnotationMirror am : element.getAnnotationMirrors()) {
            String annotationType = am.getAnnotationType().toString();

            // Check for @DomainEvents container (from @Repeatable)
            if (annotationType.equals("eu.exeris.sdk.annotation.DomainEvent.DomainEvents")) {
                Map<String, Object> containerValues = extractAnnotationValues(am);
                Object valueObj = containerValues.get(VALUE_ELEMENT);
                if (valueObj instanceof List<?> eventAnnotations) {
                    for (Object eventAnnotation : eventAnnotations) {
                        if (eventAnnotation instanceof AnnotationMirror eventAm) {
                            DomainEventMetadata event = extractSingleEventMetadata(eventAm, element);
                            if (event != null) events.add(event);
                        }
                    }
                }
            }

            // Check for single @DomainEvent
            if (annotationType.equals("eu.exeris.sdk.annotation.DomainEvent")) {
                DomainEventMetadata event = extractSingleEventMetadata(am, element);
                if (event != null) events.add(event);
            }
        }

        // Also check nested classes for event definitions (legacy support)
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CLASS) continue;

            TypeElement nestedClass = (TypeElement) enclosed;
            AnnotationMirror eventAnnotation = findAnnotation(nestedClass, "eu.exeris.sdk.annotation.DomainEvent");

            if (eventAnnotation != null) {
                Map<String, Object> values = extractAnnotationValues(eventAnnotation);
                String eventName = nestedClass.getSimpleName().toString();
                String topic = values.containsKey("topic") ? (String) values.get("topic") : null;
                String description = values.containsKey("description") ? (String) values.get("description") : null;
                // EV1: the inner-class event form resolves payload/sensitive fields
                // against the ENCLOSING entity's @Field list, exactly like the
                // class-level form (extractSingleEventMetadata) — it must not silently
                // drop EV1 payloads just because the event is declared as a nested class.
                List<String> payloadFields = resolvePayloadFields(values, element);
                List<String> sensitiveFields = getStringArray(values, "sensitiveFields");
                events.add(DomainEventMetadata.builder(eventName)
                        .topic(topic)
                        .description(description)
                        .aggregateType(element.getSimpleName().toString())
                        .payloadFields(payloadFields)
                        .sensitiveFields(sensitiveFields)
                        .trigger(eventTrigger(values))
                        .actionName(nonBlank(values, "action"))
                        .fieldName(nonBlank(values, "field"))
                        .build());
            }
        }

        return events;
    }

    private DomainEventMetadata extractSingleEventMetadata(AnnotationMirror eventAnnotation, TypeElement element) {
        Map<String, Object> values = extractAnnotationValues(eventAnnotation);

        // TODO(T11-strict): no warnInertAttributes("DomainEvent", values, ...) call yet,
        // so -Aexeris.strict cannot audit unconsumed @DomainEvent attributes (the
        // @Field / @ActionParam paths do). Add one once the consumed-attribute set is
        // settled (name/topic/description/trigger/action/field/includeFields/
        // excludeFields/sensitiveFields).
        String name = values.containsKey("name") ? (String) values.get("name") : null;
        if (name == null || name.isBlank()) {
            // Derive from trigger type
            String trigger = values.containsKey("trigger") ? values.get("trigger").toString() : "CREATE";
            name = element.getSimpleName().toString() + triggerToEventSuffix(trigger);
        }

        String topic = values.containsKey("topic") ? (String) values.get("topic") : null;
        String description = values.containsKey("description") ? (String) values.get("description") : null;

        // EV1: resolve the payload field subset + sensitive fields. Shared semantics
        // with SourceModelReader.resolvePayloadFields (ADR-042 lock-step).
        // TODO(EV1): includeComputed / includePreviousValues are intentionally NOT
        // contributing to payloadFields yet — there is no computed-field source in
        // the persisted @Field list; revisit when the computed-field surface lands.
        List<String> payloadFields = resolvePayloadFields(values, element);
        List<String> sensitiveFields = getStringArray(values, "sensitiveFields");

        return DomainEventMetadata.builder(name)
                .topic(topic)
                .description(description)
                .aggregateType(element.getSimpleName().toString())
                .payloadFields(payloadFields)
                .sensitiveFields(sensitiveFields)
                .trigger(eventTrigger(values))
                .actionName(nonBlank(values, "action"))
                .fieldName(nonBlank(values, "field"))
                .build();
    }

    /**
     * EV2 (T48): {@code @DomainEvent.trigger} as an AST value rather than a suffix input.
     *
     * <p>Note the asymmetry with the name derivation above, which defaults an absent
     * trigger to {@code CREATE} because all it needs is a suffix. Here an absent trigger
     * stays {@code null}, because the AST component makes a claim downstream: {@code null}
     * means "this baseline predates EV2 extraction", which is a different statement from
     * "fires on CREATE". Same rule the {@code -io} reader already applies
     * (ADR-042 lock-step; the reader took this one first).
     *
     * <p>An unrecognised constant also yields {@code null} rather than a guess. The
     * annotation's enum cannot hold a value this switch does not know without an SDK
     * change, so the branch is unreachable from a well-formed source — but silently
     * mapping an unknown trigger onto a known one would place a publish call in the wrong
     * handler method, which is worse than emitting none.
     */
    private DomainEventMetadata.Trigger eventTrigger(Map<String, Object> values) {
        if (!values.containsKey("trigger")) {
            return null;
        }
        String declared = values.get("trigger").toString();
        for (DomainEventMetadata.Trigger candidate : DomainEventMetadata.Trigger.values()) {
            if (candidate.name().equals(declared)) {
                return candidate;
            }
        }
        return null;
    }

    /** An annotation attribute's value, or {@code null} when absent or blank. */
    private String nonBlank(Map<String, Object> values, String attribute) {
        Object value = values.get(attribute);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text;
    }

    /**
     * EV1 payload-field resolution: ({@code @DomainEvent.includeFields} if non-empty,
     * else ALL of the entity's {@code @Field} names) minus
     * {@code @DomainEvent.excludeFields}, preserving entity-declaration order
     * (deterministic). The deterministic mirror of
     * {@code SourceModelReader.resolvePayloadFields} — keep the two in lock-step
     * (ADR-042).
     */
    private List<String> resolvePayloadFields(Map<String, Object> eventValues, TypeElement element) {
        List<String> include = getStringArray(eventValues, "includeFields");
        List<String> base = include.isEmpty() ? entityFieldNames(element) : include;
        Set<String> exclude = new HashSet<>(getStringArray(eventValues, "excludeFields"));
        List<String> resolved = new ArrayList<>(base.size());
        for (String fieldName : base) {
            if (!exclude.contains(fieldName)) {
                resolved.add(fieldName);
            }
        }
        return resolved;
    }

    /**
     * The entity's {@code @Field}-annotated field names in declaration order — the
     * universe EV1 payload resolution selects from. Must agree byte-for-byte with
     * {@code SourceModelReader.entityFieldNames} (ADR-042 lock-step). Fields without
     * {@code @Field} (and {@code @Relationship} fields) are not payload fields.
     */
    private List<String> entityFieldNames(TypeElement element) {
        List<String> names = new ArrayList<>();
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            if (findAnnotation(enclosed, "eu.exeris.sdk.annotation.Field") != null) {
                names.add(enclosed.getSimpleName().toString());
            }
        }
        return names;
    }

    /**
     * A {@code String[]} annotation attribute as an ordered list of its string
     * elements, or an empty list when absent. {@link #extractAnnotationValues}
     * unwraps array attributes from {@code List<AnnotationValue>} to
     * {@code List<Object>}; for {@code String[]} each element is already a
     * {@code String} (the same unwrap path the {@code @Field.computedFrom} read uses).
     */
    private static List<String> getStringArray(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    /**
     * Maps a {@code DomainEvent.Trigger} enum constant name to the suffix
     * appended to the entity name when the user did not supply an explicit
     * event name. Uses exact-string matching: a future or user-extended
     * enum value such as {@code BULK_CREATE} must not silently match
     * {@code CREATE} and produce {@code CreatedEvent}. Triggers without an
     * explicit suffix mapping (e.g., {@code STATE_TRANSITION},
     * {@code SCHEDULED}, {@code MANUAL}, {@code SNAPSHOT}) fall through to
     * the generic {@code "Event"} suffix.
     */
    private String triggerToEventSuffix(String trigger) {
        if (trigger == null) return "Event";
        return switch (trigger) {
            case "CREATE" -> "CreatedEvent";
            case "UPDATE" -> "UpdatedEvent";
            case "DELETE" -> "DeletedEvent";
            case "FIELD_CHANGED" -> "ChangedEvent";
            case "ACTION" -> "ActionEvent";
            default -> "Event";
        };
    }

    private List<RelationshipMetadata> extractRelationshipsMetadata(TypeElement element) {
        List<RelationshipMetadata> relationships = new ArrayList<>();

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;

            VariableElement field = (VariableElement) enclosed;
            AnnotationMirror relAnnotation = findAnnotation(field, "eu.exeris.sdk.annotation.Relationship");

            if (relAnnotation != null) {
                Map<String, Object> values = extractAnnotationValues(relAnnotation);
                String name = field.getSimpleName().toString();
                String targetEntity = resolveTargetEntity(values, field);

                RelationshipMetadata.Builder builder = RelationshipMetadata.builder(name, targetEntity);

                // The SDK attribute is `relationshipType`; `type` is the AST's name for it
                // and never existed on the annotation, so this read always missed and every
                // relationship was recorded with the builder default MANY_TO_ONE. Downstream
                // that is not cosmetic: KernelFlywayGenerator, KernelRepositoryGenerator,
                // KernelServiceGenerator and generateForeignKeys all gate on MANY_TO_ONE, so a
                // ONE_TO_MANY/MANY_TO_MANY side was emitting an FK column, its index, its
                // FOREIGN KEY constraint and a findBy…Id finder that belong on the other side.
                String relationType = enumConstantName(values.get("relationshipType"));
                if (relationType != null) {
                    // The annotation and AST enums mirror each other's constant names by
                    // contract (same four), so a mismatch means SDK/tooling version skew and
                    // must surface as a processing failure, not a silent default.
                    builder.type(RelationshipMetadata.RelationType.valueOf(relationType));
                }
                // @Relationship carries cascade as two booleans; the AST carries a JPA-shaped
                // enum, and KernelApplicationGenerator#deletePolicy reads ALL/REMOVE as
                // ON DELETE CASCADE. Without this the FK constraints emitted by T9 were always
                // RESTRICT and cascadeDelete was a no-op.
                boolean cascadeDelete = Boolean.TRUE.equals(values.get("cascadeDelete"));
                boolean cascadeUpdate = Boolean.TRUE.equals(values.get("cascadeUpdate"));
                if (cascadeDelete || cascadeUpdate) {
                    builder.cascade(cascadeDelete && cascadeUpdate
                            ? RelationshipMetadata.CascadeType.ALL
                            : cascadeDelete
                                    ? RelationshipMetadata.CascadeType.REMOVE
                                    : RelationshipMetadata.CascadeType.MERGE);
                }
                if (values.containsKey("mappedBy")) builder.mappedBy((String) values.get("mappedBy"));
                if (values.containsKey("displayField")) builder.displayField((String) values.get("displayField"));

                relationships.add(builder.build());
            }
        }

        return relationships;
    }

    /**
     * T4: prefer the explicit {@code @Relationship(targetEntity = Foo.class)} over the
     * field's Java type. The attribute is required on the SDK annotation, so it is
     * normally present as a {@link TypeMirror}; reading the field type instead made the
     * annotation only work on entity-typed fields and recorded {@code UUID} as the
     * target for the explicit-UUID-FK style ({@code @Relationship UUID ownerId}). Fall
     * back to the field type only when the attribute is absent or {@code void.class}.
     */
    private String resolveTargetEntity(Map<String, Object> values, VariableElement field) {
        Object declared = values.get("targetEntity");
        if (declared instanceof TypeMirror tm && tm.getKind() != TypeKind.VOID) {
            if (tm instanceof DeclaredType dt) {
                return dt.asElement().getSimpleName().toString();
            }
            return tm.toString();
        }
        return extractTargetEntityFromType(field.asType());
    }

    private String extractTargetEntityFromType(TypeMirror type) {
        if (type instanceof DeclaredType declaredType) {
            List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
            if (!typeArgs.isEmpty()) {
                // LIMITATION: returns the first type argument unconditionally, which
                // is correct for List<Entity>/Set<Entity>/Optional<Entity> but wrong
                // for Map<K,V> (returns the key, not the value-side entity). Acceptable
                // today because @Relationship fields are by convention single-entity
                // references; revisit if Map-valued relationships become a real
                // pattern in user domains.
                return typeArgs.get(0).toString();
            }
            return declaredType.asElement().getSimpleName().toString();
        }
        return type.toString();
    }

    private UIMetadata extractUIMetadata(TypeElement element) {
        AnnotationMirror uiAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.UI");
        if (uiAnnotation == null) return null;

        Map<String, Object> values = extractAnnotationValues(uiAnnotation);

        return UIMetadata.builder()
                .listView(values.containsKey("listView") ? (Boolean) values.get("listView") : true)
                .detailView(values.containsKey("detailView") ? (Boolean) values.get("detailView") : true)
                .createForm(values.containsKey("createForm") ? (Boolean) values.get("createForm") : true)
                .editForm(values.containsKey("editForm") ? (Boolean) values.get("editForm") : true)
                .searchable(values.containsKey("searchable") ? (Boolean) values.get("searchable") : true)
                .filterable(values.containsKey("filterable") ? (Boolean) values.get("filterable") : true)
                .exportable(values.containsKey("exportable") ? (Boolean) values.get("exportable") : false)
                .build();
    }

    private GraphMetadata extractGraphMetadata(TypeElement element) {
        AnnotationMirror graphAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.Graph");
        if (graphAnnotation == null) return null;

        Map<String, Object> values = extractAnnotationValues(graphAnnotation);

        // nodeClass is the label in @Graph annotation
        String label = element.getSimpleName().toString();
        if (values.containsKey("nodeClass")) {
            label = (String) values.get("nodeClass");
        }

        return new GraphMetadata(
                label,
                null,
                graphEdges(element),
                List.of()
        );
    }

    /**
     * The entity's declared graph edges, from {@code @GraphEdge} on its fields.
     *
     * <p><b>S3: this list was hardcoded to {@code List.of()}.</b> {@code GraphEdgeMetadata} exists,
     * and {@code KernelGraphSyncGenerator} iterates {@code graph.edges()} to emit one
     * {@code GraphEdgeDescriptor} constant apiece — so the consumer was ready and the producer did
     * not exist. Every generated graph-sync artefact carried zero edges, in every build, whatever
     * the entity declared.
     *
     * <p>{@code @GraphEdge} is {@code @Repeatable(GraphEdges.class)}, so both the direct mirror and
     * the synthesised container are read — the same shape S2 fixed for {@code @SagaStep}, and the
     * same helper.
     *
     * <p>Order is field declaration order, which decides the order of the emitted constants. That
     * is the assumption every other extraction here already makes.
     */
    private List<GraphEdgeMetadata> graphEdges(TypeElement element) {
        List<GraphEdgeMetadata> edges = new ArrayList<>();

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;

            AnnotationMirror direct = findAnnotation(enclosed, GRAPH_EDGE_FQN);
            if (direct != null) {
                edges.add(graphEdge(direct, enclosed));
            }

            // Repeated on one field: javac replaced the singles with the container. Read it, so
            // the repeat is seen rather than silently dropped — and then refuse it, because
            // GraphEdgeMetadata cannot express it. See refuseRepeatedEdge.
            AnnotationMirror container = findAnnotation(enclosed, GRAPH_EDGES_FQN);
            if (container != null) {
                List<GraphEdgeMetadata> repeated = new ArrayList<>();
                forEachContained(container, contained -> repeated.add(graphEdge(contained, enclosed)));
                if (repeated.size() > 1) {
                    refuseRepeatedEdge(enclosed, container, repeated);
                } else {
                    edges.addAll(repeated);
                }
            }
        }

        return List.copyOf(edges);
    }

    /**
     * Refuses two {@code @GraphEdge}s on one field, at the declaration, with the reason.
     *
     * <p><b>The record cannot express the shape, and the consumer proves it.</b>
     * {@code GraphEdgeMetadata.name} does two jobs in {@code KernelGraphSyncGenerator}: it is the
     * edge's identity — {@code assertDistinctEdgeNames} rejects a repeat outright — and it is how
     * the entity getter is derived, {@code "get" + capitalize(name)}. Two edges on one field need
     * the same getter and different identities, and one {@code String} cannot be both.
     *
     * <p>Accepting the shape here would produce metadata that builds at {@code javac} time and then
     * fails the generator with "Duplicate edge names", two stages away from the declaration — and
     * that generator's message tells the author to "declare a unique name" on an annotation that
     * has no {@code name} attribute at all. Refusing at the field, naming the real limitation, is
     * the better of the three available outcomes; silently dropping the repeats, which is what
     * happened before this change, is the worst.
     *
     * <p>Lifting it is an SDK ask: {@code GraphEdgeMetadata} needs the field and the identity as
     * separate components. Recorded in the ROADMAP.
     */
    private void refuseRepeatedEdge(Element field, AnnotationMirror container,
                                    List<GraphEdgeMetadata> repeated) {
        String types = repeated.stream()
                .map(e -> e.relationType() != null ? e.relationType() : "(no type)")
                .collect(Collectors.joining(", "));
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                DIAG_PREFIX + "@GraphEdge is declared " + repeated.size() + " times on field '"
                        + field.getSimpleName() + "' (" + types + "), and the pipeline cannot carry "
                        + "that. GraphEdgeMetadata identifies an edge by the field it is declared "
                        + "on, and the graph-sync generator derives the entity getter from the same "
                        + "value — so two edges on one field would need one name to be both. "
                        + "Declare each edge on its own field, or open an SDK change giving "
                        + "GraphEdgeMetadata a separate identity component.",
                field, container);
    }

    /**
     * One {@code @GraphEdge} mirror, narrowed to the three components {@link GraphEdgeMetadata}
     * carries: the annotated field's name, the target node label, and the relation type.
     *
     * <p><b>Target-label precedence, and why it is not just {@code targetLabel()}.</b> The
     * annotation offers three ways to say what the edge points at — {@code targetLabel()} (the
     * graph label), {@code target()} (the entity class) and {@code targetName()} (its name when
     * the class is not on the path). The record holds one. Taking only {@code targetLabel()} would
     * mean {@code @GraphEdge(type = "OWNED_BY", target = User.class)} — the obvious way to write
     * one — emitted a descriptor pointing at the generator's {@code "Node"} fallback, which is
     * nobody's label. So an explicit label wins, then the class's simple name, then the name
     * string; this mirrors how the node label above is derived from {@code nodeClass} or the
     * element's own simple name.
     *
     * <p>The annotation's remaining eleven attributes — {@code direction}, {@code bidirectional},
     * {@code inverseType}, {@code weighted}, {@code weightField}, {@code description},
     * {@code properties}, {@code propertyMappings}, {@code staticProperties},
     * {@code computedProperties} and {@code target}/{@code targetName} beyond the label above —
     * have <em>no component in the record to carry them</em>. Extracting them would need an SDK
     * change first; recorded in the ROADMAP rather than half-read here.
     */
    private GraphEdgeMetadata graphEdge(AnnotationMirror mirror, Element field) {
        Map<String, Object> values = extractAnnotationValues(mirror);

        String label = blankToNull(getString(values, "targetLabel", null));
        if (label == null && values.containsKey("target")) {
            label = simpleNameOf(serviceFqn(values.get("target")));
        }
        if (label == null) {
            label = blankToNull(getString(values, "targetName", null));
        }

        return new GraphEdgeMetadata(
                field.getSimpleName().toString(),
                label,
                blankToNull(getString(values, "type", null)));
    }

    /** The simple name of a fully-qualified type, or null for {@code void.class} / an absent one. */
    private static String simpleNameOf(String fqn) {
        if (fqn == null || fqn.isBlank() || "void".equals(fqn)) {
            return null;
        }
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    private EventSourcedMetadata extractEventSourcedMetadata(TypeElement element) {
        AnnotationMirror esAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.EventSourced");
        if (esAnnotation == null) return null;

        Map<String, Object> values = extractAnnotationValues(esAnnotation);

        // Translate from SDK annotation attribute names (streamPrefix /
        // snapshotThreshold — the user-visible surface on @EventSourced)
        // to SDK metadata-model field names (aggregateType / snapshotEvery
        // — the internal AST shape). The two are deliberately misaligned
        // on the SDK side; the processor owns the translation. Previously
        // this method read aggregateType / snapshotEvery directly from the
        // values map — neither of which is a real attribute on the
        // annotation, so every user value was silently dropped and replaced
        // with the class-name fallback / hardcoded default.
        String streamPrefix = values.containsKey("streamPrefix")
                ? (String) values.get("streamPrefix")
                : "";
        String aggregateType = streamPrefix.isEmpty()
                ? element.getSimpleName().toString()
                : streamPrefix;

        return EventSourcedMetadata.builder(aggregateType)
                // SDK @EventSourced.snapshotThreshold default is 50; preserve
                // that as our fallback when the attribute is omitted.
                .snapshotEvery(getInt(values, "snapshotThreshold", 50))
                .build();
    }

    private SagaMetadata extractSagaMetadata(TypeElement element) {
        AnnotationMirror sagaAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.Saga");
        if (sagaAnnotation == null) return null;

        Map<String, Object> values = extractAnnotationValues(sagaAnnotation);
        String name = values.containsKey("name")
                ? (String) values.get("name")
                : element.getSimpleName().toString();

        SagaMetadata.Builder builder = SagaMetadata.builder(name);

        if (values.containsKey("description")) builder.description((String) values.get("description"));
        if (values.containsKey("timeout")) builder.timeout((String) values.get("timeout"));
        if (values.containsKey("maxRetries")) builder.maxRetries(getInt(values, "maxRetries", 0));
        // S1: `version` was never read, so SagaMetadata reported 1 for every saga and
        // `@Saga(version = 3)` produced a metadata document that contradicted its own source.
        // Correcting an existing field, not adding one — the record already declares it and the
        // TypeScript schema already mirrors it. What no generator can do with it yet is a separate,
        // kernel-gated question; see the ROADMAP entry.
        if (values.containsKey(VERSION_ATTRIBUTE)) builder.version(getInt(values, VERSION_ATTRIBUTE, 1));

        // Extract saga steps from methods
        List<SagaStepMetadata> steps = extractSagaSteps(element);
        builder.steps(steps);

        return builder.build();
    }

    /**
     * The saga's steps, in {@code order}.
     *
     * <p><b>S2: a repeated {@code @SagaStep} used to contribute nothing.</b> The annotation is
     * {@code @Repeatable(SagaSteps.class)} and the container is public precisely so a step can be
     * repeated from any package — so repeating one is a supported authoring shape. But {@code javac}
     * replaces the repeats with the synthesised container, and a lookup for the exact type
     * {@code eu.exeris.sdk.annotation.SagaStep} then finds nothing: every step on that method was
     * dropped, silently, and the emitted flow was short by however many the author wrote. The SDK's
     * own {@code SagaSteps} javadoc records the same finding — "repeating a step compiles, and is
     * then dropped". Both shapes are read here now, through the container helper the capability
     * extraction already uses for {@code @Provides.List}.
     */
    private List<SagaStepMetadata> extractSagaSteps(TypeElement element) {
        List<SagaStepMetadata> steps = new ArrayList<>();

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;

            ExecutableElement method = (ExecutableElement) enclosed;

            AnnotationMirror stepAnnotation = findAnnotation(method, SAGA_STEP_FQN);
            if (stepAnnotation != null) {
                steps.add(sagaStep(stepAnnotation, method));
            }

            // Repeated: javac put the singles inside the container and left no direct mirror.
            AnnotationMirror container = findAnnotation(method, SAGA_STEPS_FQN);
            if (container != null) {
                forEachContained(container, contained -> steps.add(sagaStep(contained, method)));
            }
        }

        // Sort by order
        steps.sort(Comparator.comparingInt(SagaStepMetadata::order));

        return steps;
    }

    /** One {@code @SagaStep} mirror, whether it stood alone or came out of the container. */
    private SagaStepMetadata sagaStep(AnnotationMirror stepAnnotation, ExecutableElement method) {
        Map<String, Object> values = extractAnnotationValues(stepAnnotation);
        String name = getString(values, "name", method.getSimpleName().toString());
        int order = getInt(values, "order", 1);

        SagaStepMetadata.Builder builder = SagaStepMetadata.builder(name, order);

        if (values.containsKey("description")) builder.description((String) values.get("description"));
        if (values.containsKey("service")) builder.service((String) values.get("service"));
        if (values.containsKey("command")) builder.command((String) values.get("command"));
        if (values.containsKey("compensation")) builder.compensation((String) values.get("compensation"));
        if (values.containsKey("timeout")) builder.timeout((String) values.get("timeout"));
        if (values.containsKey("parallel")) builder.parallel((Boolean) values.get("parallel"));

        return builder.build();
    }

    /**
     * KNOWN SDK ↔ AST DRIFT: the SDK {@code @InternalApi} annotation
     * (consumers, rateLimit, requireMtls, timeout, documented) and the AST
     * {@code InternalApiMetadata} record (hidden, readOnly, internal, reason,
     * since, disabledActions, allowedRoles) describe two different concepts.
     * Until the SDK side is reconciled, the only signal we can extract from
     * {@code @InternalApi} is its presence — which we map to
     * {@code internal = true}, matching the {@code InternalApiMetadata.internal()}
     * static factory's intent. The other AST fields stay at their defaults
     * (all false / null / empty); reading them would be a noop today since
     * those attributes don't exist on the annotation.
     */
    private InternalApiMetadata extractInternalApiMetadata(TypeElement element) {
        AnnotationMirror internalAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.InternalApi");
        if (internalAnnotation == null) return null;

        return InternalApiMetadata.builder()
                .internal(true)
                .build();
    }

    /**
     * Under {@code -Aexeris.strict}, emits a WARNING for every attribute the
     * author set explicitly on {@code annotationSimpleName} that no generator
     * consumes (per {@link #INERT_ATTRIBUTES}). No-op unless strict mode is on.
     *
     * <p>{@code values} must be the explicit-only map from
     * {@link #extractAnnotationValues} — defaults are absent there, so a warning
     * fires only for attributes the author actually wrote, never for an
     * annotation default the author never touched. {@code mirror} is the
     * annotation mirror the values came from, so the diagnostic anchors on the
     * annotation itself (IDE click-through) rather than the enclosing element.
     */
    private void warnInertAttributes(String annotationSimpleName,
                                     Map<String, Object> values,
                                     Element element,
                                     AnnotationMirror mirror) {
        if (!strict) {
            return;
        }
        // Linear scan over a tiny, ordered List — deterministic and allocation-free.
        for (InertAttribute inert : INERT_ATTRIBUTES) {
            if (inert.annotation().equals(annotationSimpleName)
                    && values.containsKey(inert.attribute())) {
                messager.printMessage(
                        Diagnostic.Kind.WARNING,
                        DIAG_PREFIX + "@" + annotationSimpleName + "." + inert.attribute()
                                + " is set but no code generator consumes it — "
                                + inert.note() + STRICT_SUFFIX,
                        element, mirror);
            }
        }
    }

    /**
     * Under {@code -Aexeris.strict}, audits every SDK annotation on {@code element}. No-op unless
     * strict mode is on. Reported once per element, not per attribute; each diagnostic anchors on
     * the offending annotation mirror.
     *
     * <p><strong>Two passes, and the second one is C0.</strong> The first reports the
     * <em>extracted but unconsumed</em> annotations somebody registered in
     * {@link #INERT_ANNOTATIONS}. The second reports every SDK annotation this processor never
     * reads at all, driven from {@link #EXTRACTED_ANNOTATIONS} — which needs no registration,
     * and so is not blind to the case nobody has noticed yet. Before it existed, an unread
     * annotation could not produce a warning by any path: no extraction means no call site, and
     * the audit was driven from call sites.
     *
     * <p>The passes cannot double-report: the second skips everything the first can see, because
     * an annotation is in {@link #EXTRACTED_ANNOTATIONS} exactly when the processor reads it.
     */
    private void auditAnnotations(Element element) {
        if (!strict) {
            return;
        }
        for (InertAnnotation inert : INERT_ANNOTATIONS) {
            AnnotationMirror mirror = findAnnotation(element, inert.fqn());
            if (mirror != null) {
                messager.printMessage(
                        Diagnostic.Kind.WARNING,
                        DIAG_PREFIX + "@" + inert.display()
                                + " is set but no code generator consumes it — "
                                + inert.note() + STRICT_SUFFIX,
                        element, mirror);
            }
        }
        warnUnreadAnnotations(element);
    }

    /**
     * C0's half of {@link #auditAnnotations}: every {@code eu.exeris.sdk.annotation} mirror on
     * {@code element} whose simple name is absent from {@link #EXTRACTED_ANNOTATIONS}.
     *
     * <p>Iterates the element's own mirrors rather than a registry, which is what makes the audit
     * complete: an annotation nobody has classified still shows up, with a generic reason. A
     * {@code @Repeatable} container is reported under its member's name — the container is
     * synthesised by {@code javac} and the author never wrote it — and is skipped entirely when
     * that member is one the processor reads. Both the SDK's plain-plural containers
     * ({@code @SagaSteps}) and its nested ones ({@code @DomainEvent.DomainEvents},
     * {@code @Provides.List}) are covered, because the test is structural rather than by name;
     * the two {@code .List} containers share a simple name, which no name-keyed scheme could
     * have told apart.
     *
     * <p>Order is source order ({@code getAnnotationMirrors}), so a build's diagnostics are stable
     * across runs on the same input.
     */
    private void warnUnreadAnnotations(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String display = unreadNameOf(mirror);
            if (display != null) {
                messager.printMessage(
                        Diagnostic.Kind.WARNING,
                        DIAG_PREFIX + "@" + display
                                + " is set but this processor never reads it, so no generator can "
                                + "consume it and the annotation has no effect on emitted output — "
                                + noteFor(display) + STRICT_SUFFIX,
                        element, mirror);
            }
        }
    }

    /**
     * The name to warn about for {@code mirror}, or {@code null} when there is nothing to say —
     * it is not an SDK annotation, the processor reads it, or the first pass already covers it.
     *
     * <p>Split out of the loop so the decision reads as one expression with one exit rather than
     * a chain of {@code continue}s, and so each reason for staying quiet can carry its own line.
     */
    private static String unreadNameOf(AnnotationMirror mirror) {
        String fqn = mirror.getAnnotationType().toString();
        if (!fqn.startsWith(SDK_ANNOTATION_PACKAGE)) {
            return null;
        }
        String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
        if (isAlreadyAudited(simpleName)) {
            return null;
        }
        // A repeatable member the processor DOES read — several @DomainEvent or @SagaStep on one
        // class synthesise their container. The container is an artefact of repetition, not an
        // unread annotation, so it reports under the member's name or not at all.
        String contained = containedAnnotationName(mirror);
        if (contained != null) {
            return isAlreadyAudited(contained) ? null : contained;
        }
        return simpleName;
    }

    /**
     * Whether the first pass already covers {@code simpleName} — either the processor extracts it,
     * or {@link #INERT_ANNOTATIONS} carries an entry for it.
     *
     * <p>The registry half is derived rather than restated. {@code @Blob} and {@code @Schedule} are
     * the live case: both are unextracted, so on name alone this pass would report them — and both
     * already have a registry entry whose note is far better than the generic sentence. Listing
     * them in {@link #EXTRACTED_ANNOTATIONS} would silence the duplicate but make that set claim
     * something false about them, and the next reader would believe it.
     */
    private static boolean isAlreadyAudited(String simpleName) {
        if (EXTRACTED_ANNOTATIONS.contains(simpleName)) {
            return true;
        }
        for (InertAnnotation inert : INERT_ANNOTATIONS) {
            if (inert.display().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The simple name of the annotation a {@code @Repeatable} container holds, or {@code null}
     * when {@code mirror} is not a container.
     *
     * <p><strong>Detected structurally, not from a list.</strong> A hand-maintained container
     * registry was the first attempt and it was wrong in the way this whole change is about: it
     * covered the SDK's plain-plural idiom ({@code @Rules}, {@code @SagaSteps}) and silently
     * missed the nested one, so a perfectly ordinary entity with two {@code @DomainEvent}
     * triggers drew a false "never read" warning about {@code @DomainEvent.DomainEvents} — a type
     * the author never wrote, naming an annotation the processor plainly does read. Fixing that
     * by adding one entry would have left the next repeatable annotation to rediscover it.
     *
     * <p>JLS 9.6.3: a containing annotation type declares a {@code value()} element whose type is
     * an array of the repeatable annotation type, and every other element has a default. That is
     * checkable here, so the container class is closed rather than enumerated.
     */
    private static String containedAnnotationName(AnnotationMirror mirror) {
        Element annotationType = mirror.getAnnotationType().asElement();
        ExecutableElement valueElement = null;
        for (ExecutableElement method : ElementFilter.methodsIn(annotationType.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals(VALUE_ELEMENT)) {
                valueElement = method;
            } else if (method.getDefaultValue() == null) {
                // An element without a default that is not `value` — not a containing type.
                return null;
            }
        }
        if (valueElement == null || valueElement.getReturnType().getKind() != TypeKind.ARRAY) {
            return null;
        }
        TypeMirror component = ((ArrayType) valueElement.getReturnType()).getComponentType();
        if (component.getKind() != TypeKind.DECLARED) {
            return null;
        }
        Element componentElement = ((DeclaredType) component).asElement();
        if (componentElement.getKind() != ElementKind.ANNOTATION_TYPE) {
            return null;
        }
        return componentElement.getSimpleName().toString();
    }

    /** The registered reason for an unread annotation, or the generic one when none is registered. */
    private static String noteFor(String display) {
        for (UnreadAnnotation unread : UNREAD_NOTES) {
            if (unread.display().equals(display)) {
                return unread.note();
            }
        }
        return "no extraction exists for it in ExerisDomainProcessor. Every SDK annotation is "
                + "@Retention(SOURCE), so the build-time pipeline is the only possible consumer "
                + "and an unextracted annotation is erased with nothing having read it";
    }

    private AnnotationMirror findAnnotation(Element element, String annotationFqn) {
        for (AnnotationMirror am : element.getAnnotationMirrors()) {
            if (am.getAnnotationType().toString().equals(annotationFqn)) {
                return am;
            }
        }
        return null;
    }

    private Map<String, Object> extractAnnotationValues(AnnotationMirror annotation) {
        Map<String, Object> values = new HashMap<>();

        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : annotation.getElementValues().entrySet()) {
            String key = entry.getKey().getSimpleName().toString();
            Object value = entry.getValue().getValue();
            // Array-typed annotation attributes (String[], Class[], nested
            // @Annotation[]) come back from javac as List<? extends
            // AnnotationValue> — every element is BOXED in an AnnotationValue
            // wrapper. Unwrap once here so call sites can cast the elements
            // to their concrete types (String, TypeMirror, AnnotationMirror)
            // directly. Previously each call site that needed the array form
            // either had to unwrap manually or, worse, used `instanceof
            // String[]` and silently dropped the value when the cast failed
            // (the @Field.computedFrom bug fixed alongside this change).
            if (value instanceof List<?> rawList) {
                value = rawList.stream()
                        .map(v -> v instanceof AnnotationValue av ? av.getValue() : v)
                        .toList();
            }
            values.put(key, value);
        }

        return values;
    }

    // ---------------------------------------------------------------------
    // Typed accessors over the raw `Map<String, Object>` returned by
    // extractAnnotationValues. The map only contains keys for attributes
    // the user wrote *explicitly* — the JSR 269 API exposes defaults
    // separately, and we deliberately ignore them so callers can distinguish
    // "user wrote this" from "annotation default" (the warn-and-read fallback
    // for deprecated @Validation attributes depends on that distinction).
    //
    // Direct casts at call sites are fragile: a numeric attribute that the
    // SDK declares as `int` arrives as `Integer`, but `long` arrives as
    // `Long` — a cross-cast (`(Long) values.get("count")` when the attribute
    // is declared `int`) blows up the user's `mvn compile` with a
    // ClassCastException, not a useful error. These helpers do the typed
    // extraction once, with consistent default handling, so the rest of the
    // processor reads cleanly and fails predictably.
    // ---------------------------------------------------------------------

    private static String getString(Map<String, Object> values, String key, String fallback) {
        Object v = values.get(key);
        return v instanceof String s ? s : fallback;
    }

    private static boolean getBoolean(Map<String, Object> values, String key, boolean fallback) {
        Object v = values.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    private static int getInt(Map<String, Object> values, String key, int fallback) {
        Object v = values.get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /**
     * Writes {@code exeris-metadata/<entity>.json} with the two ADR-042 baseline-trust
     * sibling fields ({@code sourceDigest} + {@code schemaVersion}) stamped alongside
     * the serialized {@link DomainMetadata} in the same JSON object — not a wrapper, so
     * a plain {@code DomainMetadata} read still works (it ignores unknown fields) and a
     * {@link BaselineTrust} read of the same file picks up just these two.
     *
     * <p>{@code sourceDigest} is {@link SourceDigest#of} over the entity's raw source
     * file text — the identical input the {@code -io} layer recomputes against, so the
     * concurrency token agrees byte-for-byte. When the source text is unavailable (no
     * javac Tree API), the digest is {@code null} (NON_NULL-omitted) and only
     * {@code schemaVersion} is stamped.
     */
    private void writeDomainMetadataWithTrust(String entityName, DomainMetadata metadata,
                                              TypeElement element) throws IOException {
        writeMetadata(entityName, buildMetadataNode(objectMapper, metadata, sourceTextOf(element)));
    }

    /**
     * Builds the entity JSON tree with the two ADR-042 baseline-trust sibling fields.
     * Package-private + static so the degraded path (null {@code source} → no
     * {@code sourceDigest}) is unit-testable without a real javac.
     *
     * <p>Fields are stamped individually by their {@link BaselineTrust} contract names
     * (no cast of {@code valueToTree} to {@code ObjectNode}; a future serializer change
     * can't blow up the user's build with a {@code ClassCastException}). {@code schemaVersion}
     * is read off {@link BaselineTrust#current} (an SDK method) rather than referenced as
     * the {@code SchemaVersion.CURRENT} compile-time constant, so the processor never inlines
     * a stale value. A {@code null} digest is omitted (not written as JSON {@code null}),
     * independent of any {@code @JsonInclude} on the SDK type.
     */
    static ObjectNode buildMetadataNode(ObjectMapper mapper, DomainMetadata metadata, String source) {
        ObjectNode node = mapper.valueToTree(metadata);
        BaselineTrust trust = BaselineTrust.current(source != null ? SourceDigest.of(source) : null);
        node.put("schemaVersion", trust.schemaVersion());
        if (trust.sourceDigest() != null) {
            node.put("sourceDigest", trust.sourceDigest());
        }
        return node;
    }

    /**
     * The entity's raw source-file text via the javac Compiler Tree API, or {@code null}
     * when unavailable. {@link SourceDigest#of} normalizes (line endings / trailing
     * whitespace), so the raw text is the correct input — do not pre-normalize here.
     */
    private String sourceTextOf(TypeElement element) {
        if (trees == null) {
            return null;
        }
        try {
            var path = trees.getPath(element);
            if (path == null) {
                return null;
            }
            CharSequence content = path.getCompilationUnit().getSourceFile().getCharContent(true);
            return content == null ? null : content.toString();
        } catch (IOException | RuntimeException e) {
            note("could not read source for ADR-042 digest (" + element.getSimpleName() + "): " + e);
            return null;
        }
    }

    private void writeMetadata(String entityName, Object metadata) throws IOException {
        String jsonFileName = METADATA_DIR + "/" + entityName + ".json";

        FileObject resource = filer.createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                jsonFileName
        );

        try (Writer writer = resource.openWriter()) {
            objectMapper.writeValue(writer, metadata);
        }
    }

    private String getPackageName(TypeElement element) {
        return processingEnv.getElementUtils()
                .getPackageOf(element)
                .getQualifiedName()
                .toString();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    /**
     * Emits a NOTE diagnostic, but only when {@code -Aexeris.verbose=true}
     * is set. Per-entity progress chatter pollutes downstream build output
     * (one line per processed entity is amplified by IDE incremental builds)
     * — opt-in keeps the default build clean while preserving the trail when
     * users need to debug processor behaviour.
     */
    private void note(String message) {
        if (verbose) {
            messager.printMessage(Diagnostic.Kind.NOTE, DIAG_PREFIX + message);
        }
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, DIAG_PREFIX + message, element);
    }

    /**
     * Surface a processing failure to the user. Always includes
     * {@code e.toString()} (class + message) rather than {@code e.getMessage()}
     * — many JDK exceptions return {@code null} from {@code getMessage()},
     * which would have produced "Failed to process …: null" with no signal
     * about what actually went wrong. Under {@code -Aexeris.verbose=true},
     * also dumps the stack trace.
     */
    private void reportProcessingFailure(Element element, String prefix, Exception e) {
        StringBuilder message = new StringBuilder(DIAG_PREFIX)
                .append(prefix)
                .append(": ")
                .append(e);
        if (verbose) {
            message.append(System.lineSeparator());
            for (StackTraceElement frame : e.getStackTrace()) {
                message.append("    at ").append(frame).append(System.lineSeparator());
            }
        }
        messager.printMessage(Diagnostic.Kind.ERROR, message.toString(), element);
    }
}

