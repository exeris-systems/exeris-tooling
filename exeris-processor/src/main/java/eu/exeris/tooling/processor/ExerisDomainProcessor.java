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
 * <h2>Migration from Corelio</h2>
 * This processor replaces the legacy {@code CorelioDomainProcessor}.
 * Key changes:
 * <ul>
 *   <li>Rebranded package: {@code com.corelio.sdk.processor} → {@code eu.exeris.tooling.processor}</li>
 *   <li>New annotations: {@code @CorelioDomain} → {@code @ExerisDomain}</li>
 *   <li>New metadata directory: {@code corelio-metadata} → {@code exeris-metadata}</li>
 * </ul>
 *
 * @author Exeris Team
 * @version 0.1.0
 * @since 0.1.0
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "eu.exeris.sdk.annotation.ExerisDomain",
        "eu.exeris.sdk.annotation.Saga"
})
@SupportedSourceVersion(SourceVersion.RELEASE_26)
@SuppressWarnings({
        "PMD.TooManyMethods",
        "PMD.CouplingBetweenObjects"
})
public class ExerisDomainProcessor extends AbstractProcessor {

    /**
     * Metadata output directory name.
     */
    public static final String METADATA_DIR = "exeris-metadata";

    private ObjectMapper objectMapper;
    private Messager messager;
    private Filer filer;

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
                error(enumElement, "Failed to process enum: " + e.getMessage());
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
            error(element, "Failed to process domain entity: " + e.getMessage());
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
            error(element, "Failed to process saga: " + e.getMessage());
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

        // Database
        if (values.containsKey("tableName")) {
            builder.tableName((String) values.get("tableName"));
        }
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

        if (values.containsKey("columnName")) builder.columnName((String) values.get("columnName"));
        if (values.containsKey("label")) builder.displayName((String) values.get("label"));
        if (values.containsKey("description")) builder.description((String) values.get("description"));
        if (values.containsKey("required")) builder.required((Boolean) values.get("required"));
        if (values.containsKey("unique")) builder.unique((Boolean) values.get("unique"));
        if (values.containsKey("indexed")) builder.indexed((Boolean) values.get("indexed"));
        if (values.containsKey("searchable")) builder.searchable((Boolean) values.get("searchable"));
        if (values.containsKey("sortable")) builder.sortable((Boolean) values.get("sortable"));
        if (values.containsKey("filterable")) builder.filterable((Boolean) values.get("filterable"));
        if (values.containsKey("readOnly")) builder.readOnly((Boolean) values.get("readOnly"));
        if (values.containsKey("hidden")) builder.hidden((Boolean) values.get("hidden"));
        if (values.containsKey("inCreate")) builder.inCreate((Boolean) values.get("inCreate"));
        if (values.containsKey("inUpdate")) builder.inUpdate((Boolean) values.get("inUpdate"));
        if (values.containsKey("minLength")) builder.minLength((Integer) values.get("minLength"));
        if (values.containsKey("maxLength")) builder.maxLength((Integer) values.get("maxLength"));

        // Handle computed fields
        if (values.containsKey("computed")) builder.computed((Boolean) values.get("computed"));
        if (values.containsKey("computedFrom")) {
            Object computedFromValue = values.get("computedFrom");
            if (computedFromValue instanceof String[] array) {
                builder.computedFrom(java.util.Arrays.asList(array));
            }
        }

        // Check for validation annotation
        AnnotationMirror validationAnnotation = findAnnotation(field, "eu.exeris.sdk.annotation.Validation");
        if (validationAnnotation != null) {
            Map<String, Object> validationValues = extractAnnotationValues(validationAnnotation);
            if (validationValues.containsKey("min")) builder.min(((Number) validationValues.get("min")).longValue());
            if (validationValues.containsKey("max")) builder.max(((Number) validationValues.get("max")).longValue());
            if (validationValues.containsKey("pattern")) builder.pattern((String) validationValues.get("pattern"));
        }

        return builder.build();
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

        if (values.containsKey("displayName")) builder.displayName((String) values.get("displayName"));
        if (values.containsKey("description")) builder.description((String) values.get("description"));
        if (values.containsKey("httpMethod")) builder.httpMethod((String) values.get("httpMethod"));
        if (values.containsKey("async")) builder.async((Boolean) values.get("async"));
        if (values.containsKey("idempotent")) builder.idempotent((Boolean) values.get("idempotent"));
        if (values.containsKey("dangerous")) builder.dangerous((Boolean) values.get("dangerous"));
        if (values.containsKey("requiresConfirmation")) builder.requiresConfirmation((Boolean) values.get("requiresConfirmation"));

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

        return ActionParamMetadata.builder(name, type)
                .displayName(values.containsKey("displayName") ? (String) values.get("displayName") : null)
                .description(values.containsKey("description") ? (String) values.get("description") : null)
                .required(values.containsKey("required") ? (Boolean) values.get("required") : true)
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

    private String triggerToEventSuffix(String trigger) {
        if (trigger == null) return "Event";
        if (trigger.contains("CREATE")) return "CreatedEvent";
        if (trigger.contains("UPDATE")) return "UpdatedEvent";
        if (trigger.contains("DELETE")) return "DeletedEvent";
        if (trigger.contains("FIELD_CHANGED")) return "ChangedEvent";
        if (trigger.contains("ACTION")) return "ActionEvent";
        return "Event";
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
                // For List<Entity>, Set<Entity>, etc.
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
        String aggregateType = values.containsKey("aggregateType")
                ? (String) values.get("aggregateType")
                : element.getSimpleName().toString();

        return EventSourcedMetadata.builder(aggregateType)
                .snapshotEvery(values.containsKey("snapshotEvery") ? (Integer) values.get("snapshotEvery") : 100)
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
        if (values.containsKey("maxRetries")) builder.maxRetries((Integer) values.get("maxRetries"));

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
                String name = values.containsKey("name")
                        ? (String) values.get("name")
                        : method.getSimpleName().toString();
                int order = values.containsKey("order") ? (Integer) values.get("order") : 1;

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

    private InternalApiMetadata extractInternalApiMetadata(TypeElement element) {
        AnnotationMirror internalAnnotation = findAnnotation(element, "eu.exeris.sdk.annotation.InternalApi");
        if (internalAnnotation == null) return null;

        Map<String, Object> values = extractAnnotationValues(internalAnnotation);

        return InternalApiMetadata.builder()
                .hidden(values.containsKey("hidden") ? (Boolean) values.get("hidden") : false)
                .readOnly(values.containsKey("readOnly") ? (Boolean) values.get("readOnly") : false)
                .internal(values.containsKey("internal") ? (Boolean) values.get("internal") : true)
                .reason(values.containsKey("reason") ? (String) values.get("reason") : null)
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
            values.put(key, value);
        }

        return values;
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

    private void note(String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, "[Exeris] " + message);
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, "[Exeris] " + message, element);
    }
}

