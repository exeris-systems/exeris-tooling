package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.openapi.OpenApiGenerator;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.io.IOException;

/**
 * Kernel OpenAPI Generator.
 *
 * <p>Adapter for {@link OpenApiGenerator} that conforms to the
 * {@link KernelArtifactGenerator} contract. Generates an OpenAPI 3.1
 * specification per domain. The resulting YAML file can be:
 * <ul>
 *   <li>served by the Kernel as {@code /api/docs/openapi.yaml}</li>
 *   <li>consumed by Swagger UI</li>
 *   <li>imported into Postman / Insomnia</li>
 *   <li>used to generate clients in other languages</li>
 * </ul>
 *
 * @see OpenApiGenerator
 */
public class KernelOpenApiGenerator implements KernelArtifactGenerator {

    private final OpenApiGenerator openApiGenerator;

    public KernelOpenApiGenerator() {
        this.openApiGenerator = new OpenApiGenerator();
    }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String packageName = "openapi";
        String fileName = toKebabCase(metadata.entityName()) + "-api";

        try {
            String yamlContent = openApiGenerator.generateYaml(metadata);

            return new GeneratedFile(
                packageName,
                fileName,
                yamlContent,
                ArtifactType.OPENAPI_SPEC,
                "yaml"
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate OpenAPI spec for " + metadata.entityName(), e);
        }
    }

    private String toKebabCase(String str) {
        return str.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.OPENAPI_SPEC;
    }
}

