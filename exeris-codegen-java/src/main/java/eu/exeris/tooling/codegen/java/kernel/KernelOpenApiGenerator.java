package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.tooling.codegen.core.generator.BackendGenerator;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.openapi.OpenApiGenerator;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.io.IOException;

/**
 * KernelOpenApiGenerator - Adapter dla OpenApiGenerator zgodny z BackendGenerator.
 *
 * <p>Generuje specyfikację OpenAPI 3.1 dla każdej domeny.
 * Wynikowy plik YAML może być:
 * <ul>
 *   <li>Serwowany przez Kernel jako `/api/docs/openapi.yaml`</li>
 *   <li>Używany przez Swagger UI</li>
 *   <li>Importowany do Postman/Insomnia</li>
 *   <li>Używany do generowania klientów w innych językach</li>
 * </ul>
 *
 * @see OpenApiGenerator
 */
public class KernelOpenApiGenerator implements BackendGenerator {

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

            // Zwracamy jako GeneratedFile z rozszerzeniem .yaml
            return new GeneratedFile(
                packageName,
                fileName,
                yamlContent,
                ArtifactType.OPENAPI_SPEC,
                "yaml"  // Rozszerzenie pliku
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate OpenAPI spec for " + metadata.entityName(), e);
        }
    }

    private String toKebabCase(String str) {
        return str.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    @Override
    public PluggableBackend backend() {
        return PluggableBackend.KERNEL;
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.OPENAPI_SPEC;
    }
}

