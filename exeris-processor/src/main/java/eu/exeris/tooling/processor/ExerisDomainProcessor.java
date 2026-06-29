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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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
@SupportedSourceVersion(SourceVersion.RELEASE_26)
@SupportedOptions({ExerisDomainProcessor.OPTION_VERBOSE, ExerisDomainProcessor.OPTION_STRICT})
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CouplingBetweenObjects"
})
public class ExerisDomainProcessor extends AbstractProcessor {

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
     * A type-level SDK annotation the processor extracts into metadata but that
     * <em>no</em> generator consumes — so applying it has no effect on emitted
     * output. Distinct from {@link InertAttribute}: here the <em>whole</em>
     * annotation is inert (a missing-generator gap), so strict mode reports it
     * once per entity rather than per attribute.
     *
     * @param fqn     the annotation's fully-qualified name (for mirror lookup)
     * @param display the simple name shown in the warning
     * @param note    why it is inert today — surfaced verbatim in the warning
     */
    private record InertAnnotation(String fqn, String display, String note) {}

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
     * <p>NB: an AST record component (e.g. {@code RelationshipMetadata.valueField()})
     * is NOT an annotation attribute — several such accessors exist with no
     * matching annotation element, and they are deliberately absent here.
     *
     * <p>When a generator starts consuming one of these, DELETE its entry in the
     * same change — a stale entry produces a false "no effect" warning on an
     * attribute that now matters. Surfaced only under {@code -Aexeris.strict}.
     */
    private static final List<InertAttribute> INERT_ATTRIBUTES = List.of(
            new InertAttribute("ActionParam", "description",
                    "the value reaches ActionParamMetadata in the JSON, but no emitter renders "
                            + "it — action-parameter generation reads only the parameter name and type"),
            new InertAttribute("ActionParam", "required",
                    "the value reaches ActionParamMetadata in the JSON, but no emitter renders "
                            + "it — action-parameter generation reads only the parameter name and type"));

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
                    "event-sourcing emission is not yet implemented (blocked on a kernel "
                            + "aggregate-event-store SPI), so the extracted EventSourcedMetadata "
                            + "reaches no generator (see ROADMAP EV2)"));

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
        Object value = extractAnnotationValues(container).get("value");
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
        String version = getString(values, "version", null);
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
            warnInertAnnotations(element);

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
            extractDomainAnnotationValues(domainAnnotation, builder);
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
        warnInertAnnotations(element);

        return builder.build();
    }

    private void extractDomainAnnotationValues(AnnotationMirror annotation, DomainMetadata.Builder builder) {
        Map<String, Object> values = extractAnnotationValues(annotation);

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
        if (values.containsKey("tenantScoped")) {
            builder.tenantScoped((Boolean) values.get("tenantScoped"));
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

        // System-field overrides (T5). Only build a SystemFieldsMetadata when
        // the user explicitly wrote at least one override attribute; otherwise
        // leave systemFields null so the default-case JSON is byte-identical
        // to pre-T5 output (determinism invariant).
        SystemFieldsMetadata systemFields = extractSystemFieldsOverrides(values);
        if (systemFields != null) {
            builder.systemFields(systemFields);
        }
    }

    /**
     * Builds a {@link SystemFieldsMetadata} from explicitly-written
     * {@code @ExerisDomain} override attributes. Returns {@code null} when no
     * relevant override was set, so the metadata record stays absent and the
     * emitted JSON is unchanged for the default case.
     *
     * <p>Unset components are filled from {@link SystemFieldsMetadata#defaults()}
     * so the record is internally complete. {@code primaryKeyField} defaults to
     * {@code "id"} (annotation default) and is included for completeness, but
     * generators leave the primary key as the literal {@code "id"} (out of T5
     * scope).
     */
    private SystemFieldsMetadata extractSystemFieldsOverrides(Map<String, Object> values) {
        SystemFieldsMetadata d = SystemFieldsMetadata.defaults();

        // The annotation default for primaryKeyField is "id"; everything else
        // is "". A blank value is treated as "not overridden".
        String primaryKeyField = nonBlankOr(getString(values, "primaryKeyField", null), d.primaryKeyField());
        String tenantIdField = nonBlankOr(getString(values, "tenantIdField", null), d.tenantIdField());
        String softDeleteField = nonBlankOr(getString(values, "softDeleteField", null), d.softDeleteField());
        String softDeleteTimestampField = nonBlankOr(getString(values, "softDeleteTimestampField", null), d.softDeleteTimestampField());
        String softDeletedByField = nonBlankOr(getString(values, "softDeletedByField", null), d.softDeletedByField());
        String versionField = nonBlankOr(getString(values, "versionField", null), d.versionField());
        String createdAtField = nonBlankOr(getString(values, "createdAtField", null), d.createdAtField());
        String createdByField = nonBlankOr(getString(values, "createdByField", null), d.createdByField());
        String updatedAtField = nonBlankOr(getString(values, "updatedAtField", null), d.updatedAtField());
        String updatedByField = nonBlankOr(getString(values, "updatedByField", null), d.updatedByField());

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

        if (!anyOverride) {
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
            AnnotationMirror actionAnnotation = findAnnotation(method, "eu.exeris.sdk.annotation.Action");

            if (actionAnnotation != null) {
                actions.add(extractActionMetadata(method, actionAnnotation));
            }
        }

        return actions;
    }

    private ActionMetadata extractActionMetadata(ExecutableElement method, AnnotationMirror annotation) {
        Map<String, Object> values = extractAnnotationValues(annotation);

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
                Object valueObj = containerValues.get("value");
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
                        .build());
            }
        }

        return events;
    }

    private DomainEventMetadata extractSingleEventMetadata(AnnotationMirror eventAnnotation, TypeElement element) {
        Map<String, Object> values = extractAnnotationValues(eventAnnotation);

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
                .build();
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

                if (values.containsKey("type")) {
                    String typeStr = values.get("type").toString();
                    try {
                        builder.type(RelationshipMetadata.RelationType.valueOf(typeStr));
                    } catch (IllegalArgumentException ignored) {}
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
                List.of(),
                List.of()
        );
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

        // Extract saga steps from methods
        List<SagaStepMetadata> steps = extractSagaSteps(element);
        builder.steps(steps);

        return builder.build();
    }

    private List<SagaStepMetadata> extractSagaSteps(TypeElement element) {
        List<SagaStepMetadata> steps = new ArrayList<>();

        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;

            ExecutableElement method = (ExecutableElement) enclosed;
            AnnotationMirror stepAnnotation = findAnnotation(method, "eu.exeris.sdk.annotation.SagaStep");

            if (stepAnnotation != null) {
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

                steps.add(builder.build());
            }
        }

        // Sort by order
        steps.sort(Comparator.comparingInt(SagaStepMetadata::order));

        return steps;
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
                                + inert.note() + ". (reported because -Aexeris.strict is enabled)",
                        element, mirror);
            }
        }
    }

    /**
     * Under {@code -Aexeris.strict}, emits a WARNING for every whole annotation
     * on {@code element} that no generator consumes (per {@link #INERT_ANNOTATIONS}).
     * No-op unless strict mode is on. Reported once per entity, not per attribute;
     * the diagnostic anchors on the offending annotation mirror.
     */
    private void warnInertAnnotations(TypeElement element) {
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
                                + inert.note() + ". (reported because -Aexeris.strict is enabled)",
                        element, mirror);
            }
        }
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

