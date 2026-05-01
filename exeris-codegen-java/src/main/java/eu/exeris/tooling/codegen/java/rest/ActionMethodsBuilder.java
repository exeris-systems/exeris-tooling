package eu.exeris.tooling.codegen.java.rest;

import com.squareup.javapoet.*;
import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds custom action endpoint methods for REST controllers.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class ActionMethodsBuilder {

    private final DomainMetadata metadata;
    private final PluggableBackend backend;

    public ActionMethodsBuilder(DomainMetadata metadata, PluggableBackend backend) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.backend = Objects.requireNonNull(backend, "backend cannot be null");
    }

    /**
     * Adds all custom action methods to the controller.
     */
    public void addActionMethods(TypeSpec.Builder controller) {
        if (metadata.actions() == null || metadata.actions().isEmpty()) {
            return;
        }

        for (ActionMetadata action : metadata.actions()) {
            addActionMethod(controller, action);
        }
    }

    private void addActionMethod(TypeSpec.Builder controller, ActionMetadata action) {
        String methodName = toCamelCase(action.name());
        String path = "/{id}/actions/" + toKebabCase(action.name());

        ClassName entityType = ClassName.get(metadata.packageName(), metadata.entityName());

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(entityType)
                .addParameter(UUID.class, "id");

        // Add action-specific parameters if any
        if (action.params() != null && !action.params().isEmpty()) {
            String dtoName = metadata.entityName() + capitalize(action.name()) + "Dto";
            ClassName dtoType = ClassName.get(metadata.packageName() + ".dto", dtoName);
            method.addParameter(dtoType, "request");
            method.addStatement("return service.$L(id, request)", methodName);
        } else {
            method.addStatement("return service.$L(id)", methodName);
        }

        // Add HTTP POST annotation (actions are typically POST)
        addPostAnnotation(method, path);

        // Add OpenAPI annotation
        method.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations", "Operation"))
                .addMember("summary", "$S", action.description() != null ? action.description() : "Execute " + action.name())
                .addMember("operationId", "$S", methodName + metadata.entityName())
                .build());

        controller.addMethod(method.build());
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
            case QUARKUS -> {
                method.addAnnotation(ClassName.get("jakarta.ws.rs", "POST"));
                method.addAnnotation(AnnotationSpec.builder(
                                ClassName.get("jakarta.ws.rs", "Path"))
                        .addMember("value", "$S", path)
                        .build());
            }
            case KERNEL -> method.addAnnotation(AnnotationSpec.builder(
                            ClassName.get("eu.exeris.kernel.transport.annotation", "Post"))
                    .addMember("path", "$S", path)
                    .build());
        }
    }

    private String toCamelCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        // Convert SCREAMING_SNAKE_CASE or kebab-case to camelCase
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;

        for (char c : input.toCharArray()) {
            if (c == '_' || c == '-') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }

        return result.toString();
    }

    private String toKebabCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("_", "-")
                .toLowerCase();
    }

    private String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return Character.toUpperCase(input.charAt(0)) + input.substring(1);
    }
}

