package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Generates OpenAPI 3.1 specifications from domain metadata.
 * <p>
 * Migrated from {@code com.corelio.sdk.generator.openapi.OpenApiGenerator}.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class OpenApiGenerator {

    private static final String DEFAULT_OUTPUT_DIR = "target/generated-openapi";
    private static final String OPENAPI_VERSION = "3.1.0";
    private static final String API_VERSION = "1.0.0";
    private static final String SECURITY_BEARER_AUTH = "bearerAuth";

    private final ObjectMapper yamlMapper;
    private Path outputDirectory;
    private String baseUrl = "http://localhost:8080";
    private String apiTitle = "Exeris API";
    private Contact contact;
    private License license;

    public OpenApiGenerator() {
        this.yamlMapper = new ObjectMapper(
            YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
                .build()
        );
        this.outputDirectory = Path.of(DEFAULT_OUTPUT_DIR);
    }

    /**
     * Generates OpenAPI specification from domain metadata.
     */
    public OpenAPI generate(DomainMetadata metadata) throws IOException {
        validateMetadata(metadata);
        Files.createDirectories(outputDirectory);

        OpenAPI openAPI = buildOpenAPI(metadata);

        Path outputFile = outputDirectory
            .resolve(metadata.entityName().toLowerCase(Locale.ROOT) + "-api.yaml");

        yamlMapper.writeValue(outputFile.toFile(), openAPI);
        return openAPI;
    }

    /**
     * Generates OpenAPI specification as YAML string (no file I/O).
     */
    public String generateYaml(DomainMetadata metadata) throws IOException {
        validateMetadata(metadata);
        OpenAPI openAPI = buildOpenAPI(metadata);
        return yamlMapper.writeValueAsString(openAPI);
    }

    /**
     * Generates aggregated OpenAPI specification for multiple entities.
     */
    public OpenAPI generateAggregated(List<DomainMetadata> metadataList, String moduleName) throws IOException {
        if (metadataList == null || metadataList.isEmpty()) {
            throw new IllegalArgumentException("Metadata list cannot be empty");
        }

        Files.createDirectories(outputDirectory);

        OpenAPI openAPI = new OpenAPI();
        openAPI.setOpenapi(OPENAPI_VERSION);
        openAPI.setInfo(buildAggregatedInfo(moduleName));
        openAPI.setServers(buildServers());

        List<io.swagger.v3.oas.models.tags.Tag> allTags = new ArrayList<>();
        io.swagger.v3.oas.models.Paths allPaths = new io.swagger.v3.oas.models.Paths();
        Components allComponents = new Components();
        Map<String, io.swagger.v3.oas.models.media.Schema> allSchemas = new LinkedHashMap<>();

        for (DomainMetadata metadata : metadataList) {
            allTags.addAll(OpenApiTagsBuilder.buildTags(metadata));
            io.swagger.v3.oas.models.Paths entityPaths = OpenApiPathsBuilder.buildPaths(metadata);
            entityPaths.forEach(allPaths::addPathItem);
            Components entityComponents = OpenApiComponentsBuilder.buildComponents(metadata);
            if (entityComponents.getSchemas() != null) {
                allSchemas.putAll(entityComponents.getSchemas());
            }
        }

        openAPI.setTags(allTags);
        openAPI.setPaths(allPaths);
        allComponents.setSchemas(allSchemas);
        allComponents.setSecuritySchemes(OpenApiSecurityBuilder.buildSecuritySchemes());
        openAPI.setComponents(allComponents);
        openAPI.setSecurity(List.of(new SecurityRequirement().addList(SECURITY_BEARER_AUTH)));

        Path outputFile = outputDirectory.resolve(moduleName + "-api.yaml");
        yamlMapper.writeValue(outputFile.toFile(), openAPI);

        return openAPI;
    }

    private OpenAPI buildOpenAPI(DomainMetadata metadata) {
        OpenAPI openAPI = new OpenAPI();
        openAPI.setOpenapi(OPENAPI_VERSION);
        openAPI.setInfo(buildInfo(metadata));
        openAPI.setServers(buildServers());
        openAPI.setTags(OpenApiTagsBuilder.buildTags(metadata));
        openAPI.setPaths(OpenApiPathsBuilder.buildPaths(metadata));
        openAPI.setComponents(OpenApiComponentsBuilder.buildComponents(metadata));
        openAPI.setSecurity(OpenApiSecurityBuilder.buildSecurity(metadata));
        return openAPI;
    }

    private Info buildInfo(DomainMetadata metadata) {
        Info info = new Info();
        info.setTitle(apiTitle + " - " + capitalize(metadata.entityName()) + " API");
        info.setDescription(metadata.description() != null && !metadata.description().isBlank()
            ? metadata.description()
            : "REST API for " + metadata.entityName() + " management");
        info.setVersion(API_VERSION);
        if (contact != null) info.setContact(contact);
        if (license != null) info.setLicense(license);
        return info;
    }

    private Info buildAggregatedInfo(String moduleName) {
        Info info = new Info();
        info.setTitle(apiTitle + " - " + capitalize(moduleName) + " Module");
        info.setDescription("REST API for " + moduleName + " module");
        info.setVersion(API_VERSION);
        if (contact != null) info.setContact(contact);
        if (license != null) info.setLicense(license);
        return info;
    }

    private List<Server> buildServers() {
        Server server = new Server();
        server.setUrl(baseUrl);
        server.setDescription("Development server");
        return List.of(server);
    }

    private void validateMetadata(DomainMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("Domain metadata cannot be null");
        }
        if (metadata.entityName() == null || metadata.entityName().isEmpty()) {
            throw new IllegalArgumentException("Entity name cannot be empty");
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    // Setters
    public void setOutputDirectory(Path outputDirectory) { this.outputDirectory = outputDirectory; }
    public Path getOutputDirectory() { return outputDirectory; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiTitle(String apiTitle) { this.apiTitle = apiTitle; }
    public void setContact(Contact contact) { this.contact = contact; }
    public void setLicense(License license) { this.license = license; }
}
