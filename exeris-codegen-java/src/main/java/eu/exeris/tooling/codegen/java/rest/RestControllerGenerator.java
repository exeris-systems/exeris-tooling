package eu.exeris.tooling.codegen.java.rest;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.InternalApiMetadata;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates REST controller classes with framework-specific annotations.
 * Supports:
 * - Read-only mode (disables create/update/delete)
 * - Soft delete toggle
 * - API versioning (path-based)
 * - Security annotations (@PreAuthorize)
 * - Action-level permission checks
 * - Internal API hiding
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class RestControllerGenerator {

    private static final String DEFAULT_API_VERSION = "v1";

    private final PluggableBackend backend;
    private final String apiVersion;
    private final boolean generateSecurityAnnotations;

    public RestControllerGenerator() {
        this(PluggableBackend.SPRING, DEFAULT_API_VERSION, true);
    }

    public RestControllerGenerator(PluggableBackend backend) {
        this(backend, DEFAULT_API_VERSION, true);
    }

    public RestControllerGenerator(PluggableBackend backend, String apiVersion, boolean generateSecurityAnnotations) {
        this.backend = Objects.requireNonNull(backend);
        this.apiVersion = apiVersion != null ? apiVersion : DEFAULT_API_VERSION;
        this.generateSecurityAnnotations = generateSecurityAnnotations;
    }

    public String generate(DomainMetadata metadata) {
        // Skip generation for internal/hidden APIs
        if (metadata.isInternal()) {
            return generateInternalPlaceholder(metadata);
        }

        String entity = metadata.entityName();
        String pkg = buildControllerPackage(metadata.packageName());
        String controllerName = entity + "Controller";
        String serviceName = entity + "Service";
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append(buildImports(metadata));
        sb.append(buildHeader(controllerName, metadata.fullyQualifiedName(), now));
        sb.append(backend.controllerAnnotation()).append("\n");
        sb.append(backend.requestMappingAnnotation(buildVersionedApiPath(metadata))).append("\n");

        // Add OpenAPI documentation annotation if needed
        sb.append(buildOpenApiAnnotation(metadata));

        sb.append("public class ").append(controllerName).append(" {\n\n");
        sb.append(buildFields(serviceName));
        sb.append(buildConstructor(controllerName, serviceName));

        // Build CRUD methods based on domain configuration
        sb.append(buildCrudMethods(entity, metadata));

        // Build custom action methods
        sb.append(buildActionMethods(entity, metadata));

        sb.append("}\n");
        return sb.toString();
    }

    private String generateInternalPlaceholder(DomainMetadata metadata) {
        String pkg = buildControllerPackage(metadata.packageName());
        return """
                package %s;
                
                /**
                 * Internal API - no public controller generated.
                 * Entity: %s
                 * This entity is marked as internal and should only be accessed via internal services.
                 */
                // No public REST controller generated for internal entity
                """.formatted(pkg, metadata.entityName());
    }

    private String buildVersionedApiPath(DomainMetadata metadata) {
        String basePath = metadata.effectivePath();
        // Add version prefix if not already present
        if (!basePath.startsWith("/v") && !basePath.contains("/" + apiVersion + "/")) {
            return "/api/" + apiVersion + basePath;
        }
        return basePath;
    }

    private String buildControllerPackage(String domainPackage) {
        int lastDot = domainPackage.lastIndexOf('.');
        return lastDot <= 0 ? "controller" : domainPackage.substring(0, lastDot) + ".controller";
    }

    private String buildImports(DomainMetadata metadata) {
        String entityFqn = metadata.fullyQualifiedName();
        String basePkg = buildBasePackage(metadata.packageName());
        StringBuilder sb = new StringBuilder();

        sb.append("import ").append(entityFqn).append(";\n");
        sb.append("import ").append(basePkg).append(".service.").append(metadata.entityName()).append("Service;\n");
        sb.append("import java.util.UUID;\n");
        sb.append("import java.util.List;\n");

        // Security imports
        if (generateSecurityAnnotations && backend == PluggableBackend.SPRING) {
            sb.append("import org.springframework.security.access.prepost.PreAuthorize;\n");
        }

        // Pagination imports
        if (backend == PluggableBackend.SPRING) {
            sb.append("import org.springframework.data.domain.Page;\n");
            sb.append("import org.springframework.data.domain.Pageable;\n");
            sb.append("import org.springframework.http.ResponseEntity;\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    private String buildBasePackage(String domainPackage) {
        int lastDot = domainPackage.lastIndexOf('.');
        return lastDot <= 0 ? domainPackage : domainPackage.substring(0, lastDot);
    }

    private String buildHeader(String controllerName, String sourceFqn, String timestamp) {
        return """
                /**
                 * %s - generated REST controller.
                 * Source: %s
                 * Generated: %s
                 * Backend: %s
                 * API Version: %s
                 */
                """.formatted(controllerName, sourceFqn, timestamp, backend.name(), apiVersion);
    }

    private String buildOpenApiAnnotation(DomainMetadata metadata) {
        if (backend != PluggableBackend.SPRING) return "";
        return """
                @io.swagger.v3.oas.annotations.tags.Tag(name = "%s", description = "%s API")
                """.formatted(metadata.entityName(),
                metadata.description() != null ? metadata.description() : metadata.entityName());
    }

    private String buildFields(String serviceName) {
        return "    private final " + serviceName + " service;\n\n";
    }

    private String buildConstructor(String controllerName, String serviceName) {
        return """
                    public %s(%s service) {
                        this.service = service;
                    }
                
                """.formatted(controllerName, serviceName);
    }

    private String buildCrudMethods(String entity, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        String entityLower = entity.toLowerCase();
        String entityUpper = entity.toUpperCase();

        // Determine what operations are allowed
        boolean readOnly = isReadOnly(metadata);
        boolean softDelete = metadata.softDelete();

        // GET by ID - always generated
        sb.append(buildSecurityAnnotation(entityLower + ":read"));
        sb.append("    ").append(backend.getMappingAnnotation("/{id}")).append("\n");
        sb.append("    public ResponseEntity<").append(entity).append("> getById(");
        sb.append(backend.pathVariableAnnotation()).append(" UUID id) {\n");
        sb.append("        return ResponseEntity.ok(service.getById(id));\n    }\n\n");

        // LIST - always generated
        sb.append(buildSecurityAnnotation(entityLower + ":list"));
        sb.append("    ").append(backend.getMappingAnnotation("")).append("\n");
        sb.append("    public ResponseEntity<Page<").append(entity).append(">> list(");
        sb.append("Pageable pageable) {\n");
        sb.append("        return ResponseEntity.ok(service.findAll(pageable));\n    }\n\n");

        // CREATE - only if not read-only
        if (!readOnly) {
            sb.append(buildSecurityAnnotation(entityLower + ":create"));
            sb.append("    ").append(backend.postMappingAnnotation("")).append("\n");
            sb.append("    public ResponseEntity<").append(entity).append("> create(");
            sb.append(backend.requestBodyAnnotation()).append(" @jakarta.validation.Valid ").append(entity).append(" entity) {\n");
            sb.append("        return ResponseEntity.status(201).body(service.save(entity));\n    }\n\n");
        }

        // UPDATE - only if not read-only
        if (!readOnly) {
            sb.append(buildSecurityAnnotation(entityLower + ":update"));
            sb.append("    ").append(backend.putMappingAnnotation("/{id}")).append("\n");
            sb.append("    public ResponseEntity<").append(entity).append("> update(");
            sb.append(backend.pathVariableAnnotation()).append(" UUID id, ");
            sb.append(backend.requestBodyAnnotation()).append(" @jakarta.validation.Valid ").append(entity).append(" entity) {\n");
            sb.append("        return ResponseEntity.ok(service.update(id, entity));\n    }\n\n");
        }

        // DELETE - only if not read-only
        if (!readOnly) {
            sb.append(buildSecurityAnnotation(entityLower + ":delete"));
            sb.append("    ").append(backend.deleteMappingAnnotation("/{id}")).append("\n");
            if (softDelete) {
                // Soft delete returns updated entity
                sb.append("    public ResponseEntity<").append(entity).append("> delete(");
                sb.append(backend.pathVariableAnnotation()).append(" UUID id) {\n");
                sb.append("        return ResponseEntity.ok(service.softDelete(id));\n    }\n\n");
            } else {
                sb.append("    public ResponseEntity<Void> delete(");
                sb.append(backend.pathVariableAnnotation()).append(" UUID id) {\n");
                sb.append("        service.delete(id);\n");
                sb.append("        return ResponseEntity.noContent().build();\n    }\n\n");
            }
        }

        // SEARCH endpoint
        sb.append(buildSecurityAnnotation(entityLower + ":search"));
        sb.append("    ").append(backend.getMappingAnnotation("/search")).append("\n");
        sb.append("    public ResponseEntity<Page<").append(entity).append(">> search(\n");
        sb.append("            @org.springframework.web.bind.annotation.RequestParam(required = false) String query,\n");
        sb.append("            Pageable pageable) {\n");
        sb.append("        return ResponseEntity.ok(service.search(query, pageable));\n    }\n\n");

        return sb.toString();
    }

    private boolean isReadOnly(DomainMetadata metadata) {
        // Check if entity is marked as read-only via InternalApi or other means
        InternalApiMetadata internalApi = metadata.internalApi();
        if (internalApi != null && internalApi.readOnly()) {
            return true;
        }
        // Could also check for @DisableAction annotations in future
        return false;
    }

    private String buildSecurityAnnotation(String permission) {
        if (!generateSecurityAnnotations) return "";
        if (backend == PluggableBackend.SPRING) {
            return "    @PreAuthorize(\"hasAuthority('" + permission + "') or hasRole('ADMIN')\")\n";
        }
        return "";
    }

    private String buildActionMethods(String entity, DomainMetadata metadata) {
        List<ActionMetadata> actions = metadata.actions();
        if (actions == null || actions.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("    // ==================== Custom Actions ====================\n\n");

        for (ActionMetadata action : actions) {
            sb.append(buildActionMethod(entity, action, metadata));
        }
        return sb.toString();
    }

    private String buildActionMethod(String entity, ActionMetadata action, DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        String method = toCamel(action.name());
        String entityLower = entity.toLowerCase();

        // Build permission check
        if (action.hasPermissions()) {
            String perms = action.permissions().stream()
                    .map(p -> "hasAuthority('" + p + "')")
                    .collect(Collectors.joining(" or "));
            sb.append("    @PreAuthorize(\"").append(perms).append(" or hasRole('ADMIN')\")\n");
        } else {
            sb.append(buildSecurityAnnotation(entityLower + ":" + action.name()));
        }

        // HTTP method annotation
        String httpMethod = action.httpMethod();
        String path = "/{id}/actions/" + toKebab(action.name());
        if ("GET".equalsIgnoreCase(httpMethod)) {
            sb.append("    ").append(backend.getMappingAnnotation(path)).append("\n");
        } else if ("PUT".equalsIgnoreCase(httpMethod)) {
            sb.append("    ").append(backend.putMappingAnnotation(path)).append("\n");
        } else if ("DELETE".equalsIgnoreCase(httpMethod)) {
            sb.append("    ").append(backend.deleteMappingAnnotation(path)).append("\n");
        } else {
            sb.append("    ").append(backend.postMappingAnnotation(path)).append("\n");
        }

        // OpenAPI description
        if (action.description() != null && !action.description().isBlank()) {
            sb.append("    @io.swagger.v3.oas.annotations.Operation(summary = \"")
                    .append(action.name()).append("\")\n");
        }

        // Method signature
        String returnType = action.resultType() != null ? action.resultType() : entity;
        if (action.async()) {
            returnType = "java.util.concurrent.CompletableFuture<" + returnType + ">";
        }

        sb.append("    public ResponseEntity<").append(returnType).append("> ").append(method).append("(\n");
        sb.append("            ").append(backend.pathVariableAnnotation()).append(" UUID id");

        // Add action parameters if any
        if (action.hasParams()) {
            sb.append(",\n            ").append(backend.requestBodyAnnotation())
                    .append(" ").append(entity).append(toPascal(action.name())).append("Request request");
        }
        sb.append(") {\n");

        // Method body
        if (action.async()) {
            sb.append("        return ResponseEntity.accepted().body(service.").append(method).append("Async(id");
        } else {
            sb.append("        return ResponseEntity.ok(service.").append(method).append("(id");
        }
        if (action.hasParams()) {
            sb.append(", request");
        }
        sb.append("));\n    }\n\n");

        return sb.toString();
    }

    private String toCamel(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        for (char c : input.toCharArray()) {
            if (c == '_' || c == '-') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(result.isEmpty() ? Character.toLowerCase(c) : c);
            }
        }
        return result.toString();
    }

    private String toPascal(String input) {
        String camel = toCamel(input);
        if (camel == null || camel.isEmpty()) return camel;
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    private String toKebab(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }
}
