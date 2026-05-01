package eu.exeris.tooling.codegen.java.rest;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.Objects;

/**
 * Builds the controller class structure with proper annotations for the target backend.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class ControllerClassBuilder {

    private final DomainMetadata metadata;
    private final PluggableBackend backend;

    public ControllerClassBuilder(DomainMetadata metadata, PluggableBackend backend) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.backend = Objects.requireNonNull(backend, "backend cannot be null");
    }

    /**
     * Creates the controller class builder with framework-specific annotations.
     *
     * @return TypeSpec.Builder for the controller
     */
    public TypeSpec.Builder createControllerClass() {
        String controllerName = metadata.entityName() + "Controller";
        String basePath = "/" + toKebabCase(metadata.entityName());

        TypeSpec.Builder builder = TypeSpec.classBuilder(controllerName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        // Add framework-specific controller annotation
        addControllerAnnotation(builder, basePath);

        // Add OpenAPI tag annotation (framework-agnostic)
        addOpenApiTagAnnotation(builder);

        // Add service field
        addServiceField(builder);

        // Add constructor
        addConstructor(builder);

        return builder;
    }

    private void addControllerAnnotation(TypeSpec.Builder builder, String basePath) {
        switch (backend) {
            case SPRING -> {
                builder.addAnnotation(ClassName.get("org.springframework.web.bind.annotation", "RestController"));
                builder.addAnnotation(AnnotationSpec.builder(
                                ClassName.get("org.springframework.web.bind.annotation", "RequestMapping"))
                        .addMember("value", "$S", basePath)
                        .build());
            }
            case MICRONAUT -> {
                builder.addAnnotation(AnnotationSpec.builder(
                                ClassName.get("io.micronaut.http.annotation", "Controller"))
                        .addMember("value", "$S", basePath)
                        .build());
            }
            case QUARKUS -> {
                builder.addAnnotation(AnnotationSpec.builder(
                                ClassName.get("jakarta.ws.rs", "Path"))
                        .addMember("value", "$S", basePath)
                        .build());
                builder.addAnnotation(ClassName.get("jakarta.ws.rs", "Produces"));
                builder.addAnnotation(ClassName.get("jakarta.ws.rs", "Consumes"));
            }
            case KERNEL -> {
                // Kernel uses its own transport annotations (defined in Kernel module)
                builder.addAnnotation(AnnotationSpec.builder(
                                ClassName.get("eu.exeris.kernel.transport.annotation", "HttpEndpoint"))
                        .addMember("path", "$S", basePath)
                        .build());
            }
        }
    }

    private void addOpenApiTagAnnotation(TypeSpec.Builder builder) {
        // Add OpenAPI Tag annotation (common across backends)
        builder.addAnnotation(AnnotationSpec.builder(
                        ClassName.get("io.swagger.v3.oas.annotations.tags", "Tag"))
                .addMember("name", "$S", metadata.entityName())
                .addMember("description", "$S", "Operations for " + metadata.entityName())
                .build());
    }

    private void addServiceField(TypeSpec.Builder builder) {
        String serviceName = metadata.entityName() + "Service";
        ClassName serviceType = ClassName.get(
                metadata.packageName() + ".service",
                serviceName
        );

        FieldSpec serviceField = FieldSpec.builder(serviceType, "service", Modifier.PRIVATE, Modifier.FINAL)
                .build();

        builder.addField(serviceField);
    }

    private void addConstructor(TypeSpec.Builder builder) {
        String serviceName = metadata.entityName() + "Service";
        ClassName serviceType = ClassName.get(
                metadata.packageName() + ".service",
                serviceName
        );

        var constructorBuilder = com.squareup.javapoet.MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(serviceType, "service")
                .addStatement("this.service = service");

        // Add DI annotation based on backend
        switch (backend) {
            case SPRING -> constructorBuilder.addAnnotation(
                    ClassName.get("org.springframework.beans.factory.annotation", "Autowired"));
            case MICRONAUT, QUARKUS -> constructorBuilder.addAnnotation(
                    ClassName.get("jakarta.inject", "Inject"));
            case KERNEL -> {
                // Kernel uses constructor injection without annotations
            }
        }

        builder.addMethod(constructorBuilder.build());
    }

    private String toKebabCase(String input) {
        return input.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }
}

