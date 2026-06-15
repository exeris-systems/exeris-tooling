package eu.exeris.tooling.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.auto.service.AutoService;
import eu.exeris.sdk.sourcemodel.ast.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
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
        "eu.exeris.sdk.annotation.Saga"
})
@SupportedSourceVersion(SourceVersion.RELEASE_26)
@SupportedOptions(ExerisDomainProcessor.OPTION_VERBOSE)
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

    /** Diagnostic prefix prepended to every NOTE/WARNING/ERROR this processor emits. */
    private static final String DIAG_PREFIX = "[Exeris] ";

    private ObjectMapper objectMapper;
    private Messager messager;
    private Filer filer;
    private boolean verbose;

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
        this.verbose = Boolean.parseBoolean(
                processingEnv.getOptions().getOrDefault(OPTION_VERBOSE, "false"));
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

    private void processDomainEntity(TypeElement element) {
        String entityName = element.getSimpleName().toString();
        String packageName = getPackageName(element);
        String fqn = element.getQualifiedName().toString();

        note("Processing domain entity: " + fqn);

        try {
            // Build full metadata using DomainMetadata model
            DomainMetadata metadata = buildFullDomainMetadata(element, entityName, packageName);

            // Write JSON metadata file
            writeMetadata(entityName, metadata);

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

            writeMetadata(sagaName, metadata);
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
        String name = method.getSimpleName().toString();
        Map<String, Object> values = extractAnnotationValues(annotation);

        ActionMetadata.Builder builder = ActionMetadata.builder(name);

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
                events.add(DomainEventMetadata.withTopic(eventName, topic));
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

        return new DomainEventMetadata(name, topic, description, element.getSimpleName().toString());
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
                String targetEntity = extractTargetEntityFromType(field.asType());

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

