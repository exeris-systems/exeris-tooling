package eu.exeris.e2e.codegen;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.MetadataLoader;
import eu.exeris.tooling.codegen.java.openapi.OpenApiComponentsBuilder;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The property sets of the entity schema, {@code <Entity>CreateDto} and {@code <Entity>UpdateDto}
 * that {@code OpenApiComponentsBuilder} produces, held against {@code contract/update-parity/}.
 *
 * <p>Each {@code java-*-properties.json} maps an entity name to the sorted property names of one
 * schema, over every metadata file in {@code contract/update-parity/exeris-metadata/}. The
 * TypeScript build reads the same files ({@code server-owned-fields.spec.ts}) and holds its emitted
 * types and Zod schemas to them, so this test is the Java half of that contract: a change to which
 * fields the entity, the create or the update carries moves a file here and fails the TypeScript
 * side until it follows.
 *
 * <p>To rewrite the three files after an intended change, run
 * {@code mvn -B -o -pl exeris-e2e-tests -am verify -Dtest=UpdateSchemaParityE2ETest
 * -Dexeris.parity.update=true -Dsurefire.failIfNoSpecifiedTests=false} from the repository root,
 * then review the diff like any other change. The property is refused when {@code CI} is set, so a
 * CI run can only compare.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Update parity contract: OpenApiComponentsBuilder schemas == contract/update-parity")
class UpdateSchemaParityE2ETest {

    private static final String CONTRACT_DIR = "/contract/update-parity";
    private static final String SOURCE_DIR = "src/test/resources/contract/update-parity";
    private static final String UPDATE_PROPERTY = "exeris.parity.update";
    private static final String ENTITY_FILE = "java-entity-properties.json";
    private static final String CREATE_FILE = "java-create-dto-properties.json";
    private static final String UPDATE_FILE = "java-update-dto-properties.json";

    @Test
    @DisplayName("the entity, create and update property sets of every metadata shape equal the contract files")
    void propertySetsMatchTheContract() throws IOException, URISyntaxException {
        Map<String, List<String>> entity = new TreeMap<>();
        Map<String, List<String>> create = new TreeMap<>();
        Map<String, List<String>> update = new TreeMap<>();
        for (DomainMetadata metadata : metadata()) {
            String name = metadata.entityName();
            Map<String, Schema> schemas = OpenApiComponentsBuilder.buildComponents(metadata).getSchemas();
            entity.put(name, properties(schemas.get(name)));
            create.put(name, properties(schemas.get(name + "CreateDto")));
            update.put(name, properties(schemas.get(name + "UpdateDto")));
        }
        assertThat(entity).as("metadata shapes in the contract directory").hasSizeGreaterThanOrEqualTo(30);

        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            rewrite(entity, create, update);
            return;
        }
        verify(ENTITY_FILE, entity);
        verify(CREATE_FILE, create);
        verify(UPDATE_FILE, update);
    }

    private static List<DomainMetadata> metadata() throws URISyntaxException, IOException {
        URL dir = UpdateSchemaParityE2ETest.class.getResource(CONTRACT_DIR + "/" + MetadataLoader.METADATA_DIR);
        assertThat(dir).as(CONTRACT_DIR + "/" + MetadataLoader.METADATA_DIR + " on the test classpath")
                .isNotNull();
        // MetadataLoader reads a field with no inCreate / inUpdate key as offered by both bodies,
        // which the files rely on; the fixtures state only the flags that differ from the default.
        return new MetadataLoader(Path.of(dir.toURI()).getParent()).loadAll(DomainMetadata.class).stream()
                .sorted(Comparator.comparing(DomainMetadata::entityName))
                .toList();
    }

    private static List<String> properties(Schema<?> schema) {
        assertThat(schema).isNotNull();
        return List.copyOf(new TreeSet<String>(schema.getProperties().keySet()));
    }

    private static void verify(String file, Map<String, List<String>> actual) throws IOException {
        String expected = read(file);
        if (!expected.equals(render(actual))) {
            fail(mismatch(file, parse(expected), actual));
        }
    }

    private static String mismatch(String file, Map<String, List<String>> expected,
                                   Map<String, List<String>> actual) {
        StringBuilder message = new StringBuilder("OpenApiComponentsBuilder disagrees with ")
                .append(CONTRACT_DIR).append('/').append(file).append(":\n");
        TreeSet<String> entities = new TreeSet<>(expected.keySet());
        entities.addAll(actual.keySet());
        for (String entity : entities) {
            List<String> contract = expected.get(entity);
            List<String> built = actual.get(entity);
            if (contract == null) {
                message.append("  ").append(entity).append(": not in the contract, Java builds ")
                        .append(built).append('\n');
            } else if (built == null) {
                message.append("  ").append(entity).append(": in the contract, no metadata file\n");
            } else if (!contract.equals(built)) {
                message.append("  ").append(entity).append(": contract ").append(contract)
                        .append(" but Java builds ").append(built).append('\n');
            }
        }
        return message.append("If the change is intended, rewrite the contract with -D").append(UPDATE_PROPERTY)
                .append("=true (see this class's Javadoc) and update the TypeScript emitters to match.")
                .toString();
    }

    private static String read(String file) throws IOException {
        try (var in = UpdateSchemaParityE2ETest.class.getResourceAsStream(CONTRACT_DIR + "/" + file)) {
            assertThat(in).as(CONTRACT_DIR + "/" + file + " on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, List<String>> parse(String json) {
        return JsonMapper.builder().build().readValue(json, new TypeReference<TreeMap<String, List<String>>>() { });
    }

    /** Two-space indent, one array per line, keys sorted, a trailing newline: the shape the files are committed in. */
    private static String render(Map<String, List<String>> properties) {
        return properties.entrySet().stream()
                .map(e -> "  \"" + e.getKey() + "\": " + e.getValue().stream()
                        .map(p -> "\"" + p + "\"").collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining(",\n", "{\n", "\n}\n"));
    }

    private static void rewrite(Map<String, List<String>> entity, Map<String, List<String>> create,
                                Map<String, List<String>> update) throws IOException {
        if (System.getenv("CI") != null) {
            fail("-D" + UPDATE_PROPERTY + "=true is refused when CI is set: a CI run compares, "
                    + "it never rewrites the contract files.");
        }
        Path dir = Path.of(SOURCE_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ENTITY_FILE), render(entity), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(CREATE_FILE), render(create), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(UPDATE_FILE), render(update), StandardCharsets.UTF_8);
        fail("Rewrote the three files in " + dir.toAbsolutePath() + " because -D" + UPDATE_PROPERTY
                + "=true was set. Review their diff, then run again without the property.");
    }
}
