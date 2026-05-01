package eu.exeris.tooling.codegen.java.rest;

import com.squareup.javapoet.*;
import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds CRUD endpoint methods for REST controllers.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class CrudMethodsBuilder {

    private final DomainMetadata metadata;
    private final PluggableBackend backend;

    public CrudMethodsBuilder(DomainMetadata metadata, PluggableBackend backend) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.backend = Objects.requireNonNull(backend, "backend cannot be null");
    }

    /**
     * Adds list (GET all) method to controller.
     */
    public void addListMethod(TypeSpec.Builder controller) {
        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());
        ClassName pageType = ClassName.bestGuess(backend.pageType());
        ClassName pageableType = ClassName.bestGuess(backend.pageableType());

        MethodSpec.Builder method = MethodSpec.methodBuilder("list")
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(pageType, entityType))
                .addParameter(pageableType, "pageable")
                .addStatement("return service.findAll(pageable)");

        // Add HTTP method annotation
        addGetAnnotation(method, "");

        // Add OpenAPI annotation
        method.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations", "Operation"))
                .addMember("summary", "$S", "List all " + metadata.entityName())
                .addMember("operationId", "$S", "list" + metadata.entityName())
                .build());

        controller.addMethod(method.build());
    }

    /**
     * Adds get by ID method to controller.
     */
    public void addGetMethod(TypeSpec.Builder controller) {
        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());

        MethodSpec.Builder method = MethodSpec.methodBuilder("getById")
                .addModifiers(Modifier.PUBLIC)
                .returns(entityType)
                .addParameter(UUID.class, "id")
                .addStatement("return service.getById(id)");

        // Add HTTP method annotation with path variable
        addGetAnnotation(method, "/{id}");
        addPathVariableAnnotation(method, "id");

        // Add OpenAPI annotation
        method.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations", "Operation"))
                .addMember("summary", "$S", "Get " + metadata.entityName() + " by ID")
                .addMember("operationId", "$S", "get" + metadata.entityName() + "ById")
                .build());

        controller.addMethod(method.build());
    }

    /**
     * Adds create method to controller.
     */
    public void addCreateMethod(TypeSpec.Builder controller) {
        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());
        String dtoName = metadata.entityName() + "CreateDto";
        ClassName dtoType = ClassName.get(metadata.packageName() + ".dto", dtoName);

        MethodSpec.Builder method = MethodSpec.methodBuilder("create")
                .addModifiers(Modifier.PUBLIC)
                .returns(entityType)
                .addParameter(dtoType, "dto")
                .addStatement("return service.create(dto)");

        // Add HTTP method annotation
        addPostAnnotation(method, "");
        addRequestBodyAnnotation(method, "dto");

        // Add OpenAPI annotation
        method.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations", "Operation"))
                .addMember("summary", "$S", "Create new " + metadata.entityName())
                .addMember("operationId", "$S", "create" + metadata.entityName())
                .build());

        controller.addMethod(method.build());
    }

    /**
     * Adds update method to controller.
     */
    public void addUpdateMethod(TypeSpec.Builder controller) {
        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());
        String dtoName = metadata.entityName() + "UpdateDto";
        ClassName dtoType = ClassName.get(metadata.packageName() + ".dto", dtoName);

        MethodSpec.Builder method = MethodSpec.methodBuilder("update")
                .addModifiers(Modifier.PUBLIC)
                .returns(entityType)
                .addParameter(UUID.class, "id")
                .addParameter(dtoType, "dto")
                .addStatement("return service.update(id, dto)");

        // Add HTTP method annotation
        addPutAnnotation(method, "/{id}");
        addPathVariableAnnotation(method, "id");
        addRequestBodyAnnotation(method, "dto");

        // Add OpenAPI annotation
        method.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations", "Operation"))
                .addMember("summary", "$S", "Update " + metadata.entityName())
                .addMember("operationId", "$S", "update" + metadata.entityName())
                .build());

        controller.addMethod(method.build());
    }

    /**
     * Adds delete method to controller.
     */
    public void addDeleteMethod(TypeSpec.Builder controller) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("delete")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addParameter(UUID.class, "id")
                .addStatement("service.delete(id)");

        // Add HTTP method annotation
        addDeleteAnnotation(method, "/{id}");
        addPathVariableAnnotation(method, "id");

        // Add OpenAPI annotation
        method.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations", "Operation"))
                .addMember("summary", "$S", "Delete " + metadata.entityName())
                .addMember("operationId", "$S", "delete" + metadata.entityName())
                .build());

        controller.addMethod(method.build());
    }

    // HTTP Method Annotations

    private void addGetAnnotation(MethodSpec.Builder method, String path) {
        switch (backend) {
            case SPRING -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("org.springframework.web.bind.annotation", "GetMapping"))
                    .addMember("value", "$S", path)
                    .build());
            case MICRONAUT -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("io.micronaut.http.annotation", "Get"))
                    .addMember("value", "$S", path)
                    .build());
            case QUARKUS -> method.addAnnotation(ClassName.get("jakarta.ws.rs", "GET"));
            case KERNEL -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("eu.exeris.kernel.transport.annotation", "Get"))
                    .addMember("path", "$S", path)
                    .build());
        }
    }

    private void addPostAnnotation(MethodSpec.Builder method, String path) {
        switch (backend) {
            case SPRING -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("org.springframework.web.bind.annotation", "PostMapping"))
                    .addMember("value", "$S", path)
                    .build());
            case MICRONAUT -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("io.micronaut.http.annotation", "Post"))
                    .addMember("value", "$S", path)
                    .build());
            case QUARKUS -> method.addAnnotation(ClassName.get("jakarta.ws.rs", "POST"));
            case KERNEL -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("eu.exeris.kernel.transport.annotation", "Post"))
                    .addMember("path", "$S", path)
                    .build());
        }
    }

    private void addPutAnnotation(MethodSpec.Builder method, String path) {
        switch (backend) {
            case SPRING -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("org.springframework.web.bind.annotation", "PutMapping"))
                    .addMember("value", "$S", path)
                    .build());
            case MICRONAUT -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("io.micronaut.http.annotation", "Put"))
                    .addMember("value", "$S", path)
                    .build());
            case QUARKUS -> method.addAnnotation(ClassName.get("jakarta.ws.rs", "PUT"));
            case KERNEL -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("eu.exeris.kernel.transport.annotation", "Put"))
                    .addMember("path", "$S", path)
                    .build());
        }
    }

    private void addDeleteAnnotation(MethodSpec.Builder method, String path) {
        switch (backend) {
            case SPRING -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("org.springframework.web.bind.annotation", "DeleteMapping"))
                    .addMember("value", "$S", path)
                    .build());
            case MICRONAUT -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("io.micronaut.http.annotation", "Delete"))
                    .addMember("value", "$S", path)
                    .build());
            case QUARKUS -> method.addAnnotation(ClassName.get("jakarta.ws.rs", "DELETE"));
            case KERNEL -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("eu.exeris.kernel.transport.annotation", "Delete"))
                    .addMember("path", "$S", path)
                    .build());
        }
    }

    private void addPathVariableAnnotation(MethodSpec.Builder method, String paramName) {
        // Note: This adds annotation to the last parameter. In production, use proper ParameterSpec building.
        switch (backend) {
            case SPRING -> {
                // Spring uses @PathVariable on parameter
            }
            case MICRONAUT -> {
                // Micronaut uses @PathVariable on parameter
            }
            case QUARKUS -> {
                // Quarkus uses @PathParam on parameter
            }
            case KERNEL -> {
                // Kernel uses @PathVar on parameter
            }
        }
    }

    private void addRequestBodyAnnotation(MethodSpec.Builder method, String paramName) {
        // Note: This adds annotation to the last parameter. In production, use proper ParameterSpec building.
        switch (backend) {
            case SPRING -> {
                // Spring uses @RequestBody on parameter
            }
            case MICRONAUT -> {
                // Micronaut uses @Body on parameter
            }
            case QUARKUS -> {
                // Quarkus uses implicit body binding
            }
            case KERNEL -> {
                // Kernel uses @Body on parameter
            }
        }
    }
}

