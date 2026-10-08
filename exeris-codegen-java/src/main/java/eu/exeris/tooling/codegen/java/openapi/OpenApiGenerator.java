package eu.exeris.tooling.codegen.java.openapi;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates OpenAPI 3.1 specifications from domain metadata.
 *
 * @since 0.1
 */
public class OpenApiGenerator {

    private static final String DEFAULT_OUTPUT_DIR = "target/generated-openapi";
    private static final String OPENAPI_VERSION = "3.1.0";
    private static final String API_VERSION = "1.0.0";

    private final ObjectMapper yamlMapper;
    private Path outputDirectory;
    private String baseUrl = "http://localhost:8080";
    private String apiTitle = "Exeris API";
    private Contact contact;
    private License license;

    /**
     * The writer is swagger's own 3.1 mapper rather than a hand-configured one.
     *
     * <p>A plain {@code ObjectMapper} over a YAML factory writes every unset field of the swagger
     * model: an emitted single-entity spec was 1664 lines, 1479 of them {@code : null}. Setting
     * {@code NON_NULL} removes those but not {@code exampleSetFlag}, a swagger-model bookkeeping
     * boolean that is not an OpenAPI field at all and that the 3.1 schema's
     * {@code unevaluatedProperties: false} rejects. {@link Yaml31#mapper()} handles both, because
     * it is the serializer the model was written for.
     */
    public OpenApiGenerator() {
        this.yamlMapper = Yaml31.mapper();
        this.outputDirectory = Path.of(DEFAULT_OUTPUT_DIR);
    }

    /**
     * Builds the OpenAPI 3.1 specification for one entity and writes it as YAML to
     * {@code <outputDirectory>/<entity>-api.yaml}, creating the directory if needed.
     *
     * @param metadata the entity to describe
     * @return the specification that was written
     * @throws IOException if the directory or the file cannot be written
     * @throws IllegalArgumentException if {@code metadata} is {@code null} or has no entity name
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
     * Builds the OpenAPI 3.1 specification for one entity and returns it as YAML, without
     * touching the file system.
     *
     * @param metadata the entity to describe
     * @return the specification as YAML text
     * @throws IOException if serialization fails
     * @throws IllegalArgumentException if {@code metadata} is {@code null} or has no entity name
     */
    public String generateYaml(DomainMetadata metadata) throws IOException {
        validateMetadata(metadata);
        OpenAPI openAPI = buildOpenAPI(metadata);
        return yamlMapper.writeValueAsString(openAPI);
    }

    /**
     * Builds one OpenAPI 3.1 specification covering every given entity and writes it as YAML to
     * {@code <outputDirectory>/<moduleName>-api.yaml}, creating the directory if needed. Tags,
     * paths and schemas are merged in list order; a later entity's schema replaces an earlier
     * one of the same name.
     *
     * @param metadataList the entities to describe; must not be empty
     * @param moduleName   the module name used in the title and the file name
     * @return the specification that was written
     * @throws IOException if the directory or the file cannot be written
     * @throws IllegalArgumentException if {@code metadataList} is {@code null} or empty
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
        openAPI.setComponents(allComponents);

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
        return openAPI;
    }

    private Info buildInfo(DomainMetadata metadata) {
        Info info = new Info();
        info.setTitle(apiTitle + " - " + capitalize(metadata.entityName()) + " API");
        info.setDescription(metadata.description() != null && !metadata.description().isBlank()
            ? metadata.description()
            : "REST API for " + metadata.entityName() + " management");
        info.setVersion(API_VERSION);
        if (contact != null) {
            info.setContact(contact);
        }
        if (license != null) {
            info.setLicense(license);
        }
        return info;
    }

    private Info buildAggregatedInfo(String moduleName) {
        Info info = new Info();
        info.setTitle(apiTitle + " - " + capitalize(moduleName) + " Module");
        info.setDescription("REST API for " + moduleName + " module");
        info.setVersion(API_VERSION);
        if (contact != null) {
            info.setContact(contact);
        }
        if (license != null) {
            info.setLicense(license);
        }
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
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Sets the directory {@link #generate} and {@link #generateAggregated} write into.
     *
     * @param outputDirectory the output directory; {@code target/generated-openapi} by default
     */
    public void setOutputDirectory(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    /**
     * Returns the directory {@link #generate} and {@link #generateAggregated} write into.
     *
     * @return the output directory
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Sets the URL of the single server entry every specification lists.
     *
     * @param baseUrl the server URL; {@code http://localhost:8080} by default
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Sets the title prefix of every specification's {@code info.title}.
     *
     * @param apiTitle the title prefix; {@code Exeris API} by default
     */
    public void setApiTitle(String apiTitle) {
        this.apiTitle = apiTitle;
    }

    /**
     * Sets the contact placed in every specification's {@code info}.
     *
     * @param contact the contact, or {@code null} for none
     */
    public void setContact(Contact contact) {
        this.contact = contact;
    }

    /**
     * Sets the licence placed in every specification's {@code info}.
     *
     * @param license the licence, or {@code null} for none
     */
    public void setLicense(License license) {
        this.license = license;
    }
}
