package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.SystemFieldsMetadata;
import eu.exeris.tooling.codegen.java.support.PrimaryKeys;
import eu.exeris.tooling.codegen.java.support.SystemColumns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration and the repository agree on the table: every column the repository's SQL names is
 * a column the migration creates, every domain column the migration creates is one the repository
 * reads and writes, and the migration defines no column twice.
 *
 * <p>Run over every combination of the tenant, audit, soft-delete and version flags, on an entity
 * that declares a field for each system role under its canonical name, and again with the roles
 * renamed through {@code systemFields}, so a field in a role whose flag is off is exercised
 * alongside the same field with the flag on.
 */
@DisplayName("Migration ↔ repository column seam")
class MigrationRepositoryColumnSeamTest {

    private static final String TABLE = "orders";
    private static final Pattern COLUMN_DEFINITION = Pattern.compile("^ {4}([a-z_][a-z0-9_]*) ", Pattern.MULTILINE);
    private static final Pattern INSERT = Pattern.compile("INSERT INTO " + TABLE + " \\(([^)]*)\\)");
    private static final Pattern SELECT = Pattern.compile("SELECT ([a-z_][a-z0-9_, ]*) FROM " + TABLE);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SQL_FRAGMENT =
            Pattern.compile("(?:INSERT INTO|UPDATE|FROM) " + TABLE + "\\b|^ (?:WHERE|AND) |^[a-z_][a-z0-9_]* = ");
    private static final Pattern COMPARED = Pattern.compile("([a-z_][a-z0-9_]*) = ");

    private static List<FieldMetadata> canonicalFields() {
        return List.of(
                FieldMetadata.builder("title", "String").required(true).build(),
                FieldMetadata.builder("tenantId", "UUID").build(),
                FieldMetadata.builder("createdAt", "Instant").build(),
                FieldMetadata.builder("createdBy", "String").build(),
                FieldMetadata.builder("updatedAt", "Instant").build(),
                FieldMetadata.builder("updatedBy", "String").build(),
                FieldMetadata.builder("deleted", "boolean").build(),
                FieldMetadata.builder("deletedAt", "Instant").build(),
                FieldMetadata.builder("deletedBy", "String").build(),
                FieldMetadata.builder("version", "Long").build());
    }

    private static List<FieldMetadata> renamedFields() {
        List<FieldMetadata> fields = new ArrayList<>(canonicalFields());
        fields.add(FieldMetadata.builder("orgId", "UUID").build());
        fields.add(FieldMetadata.builder("bornAt", "Instant").build());
        fields.add(FieldMetadata.builder("archived", "boolean").build());
        fields.add(FieldMetadata.builder("rev", "Long").build());
        return fields;
    }

    private static SystemFieldsMetadata renamedRoles() {
        return SystemFieldsMetadata.builder()
                .tenantIdField("orgId")
                .createdAtField("bornAt")
                .softDeleteField("archived")
                .versionField("rev")
                .build();
    }

    static Stream<Arguments> flagMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (int bits = 0; bits < 16; bits++) {
            cases.add(Arguments.of((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, false));
            cases.add(Arguments.of((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0, true));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "tenant={0} audited={1} softDelete={2} versioned={3} renamed={4}")
    @MethodSource("flagMatrix")
    @DisplayName("the repository names only columns the migration creates, and reads every domain column it creates")
    void repositoryAndMigrationNameTheSameColumns(boolean tenant, boolean audited, boolean softDelete,
                                                  boolean versioned, boolean renamed) {
        DomainMetadata metadata = DomainMetadata.builder("Order", "eu.exeris.app.domain")
                .tenantScoped(tenant).audited(audited).softDelete(softDelete).versioned(versioned)
                .systemFields(renamed ? renamedRoles() : null)
                .fields(renamed ? renamedFields() : canonicalFields())
                .build();

        List<String> defined = migrationColumns(metadata);
        Set<String> created = new TreeSet<>(defined);
        assertThat(defined).as("no column is defined twice").hasSameSizeAs(created);

        Set<String> layout = new TreeSet<>();
        KernelRepositoryGenerator.columnLayout(metadata).forEach(column -> layout.add(column.sqlName()));
        assertThat(created).as("the migration creates every column of the repository's layout")
                .containsAll(layout);
        assertThat(created).as("the migration creates every column the repository's SQL names")
                .containsAll(repositorySqlColumns(metadata));

        Set<String> domain = new TreeSet<>(created);
        domain.removeAll(SystemColumns.activeColumns(metadata));
        domain.remove(PrimaryKeys.column(metadata));
        assertThat(layout).as("the repository reads and writes every domain column the migration creates")
                .containsAll(domain);
    }

    /** The column definitions of the migration's CREATE TABLE, in order. */
    private static List<String> migrationColumns(DomainMetadata metadata) {
        String sql = new KernelFlywayGenerator().generate(metadata).content();
        String body = sql.substring(sql.indexOf('(') + 1, sql.indexOf("\n);"));
        List<String> columns = new ArrayList<>();
        Matcher matcher = COLUMN_DEFINITION.matcher(body);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }

    /** Every column an INSERT, SELECT, UPDATE or WHERE of the emitted repository names. */
    private static Set<String> repositorySqlColumns(DomainMetadata metadata) {
        String source = new KernelRepositoryGenerator().generate(metadata).content();
        Set<String> columns = new TreeSet<>();
        Matcher literal = STRING_LITERAL.matcher(source);
        while (literal.find()) {
            String text = literal.group(1);
            if (!SQL_FRAGMENT.matcher(text).find()) {
                continue;
            }
            addListed(columns, INSERT.matcher(text));
            addListed(columns, SELECT.matcher(text));
            Matcher compared = COMPARED.matcher(text);
            while (compared.find()) {
                columns.add(compared.group(1));
            }
        }
        assertThat(columns).as("the repository's SQL was found").contains(PrimaryKeys.column(metadata), "title");
        return columns;
    }

    private static void addListed(Set<String> columns, Matcher matcher) {
        while (matcher.find()) {
            for (String column : matcher.group(1).split(",")) {
                columns.add(column.trim());
            }
        }
    }
}
