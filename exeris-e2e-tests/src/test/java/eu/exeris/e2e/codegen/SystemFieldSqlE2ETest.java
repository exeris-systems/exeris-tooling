package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Annotation → SQL for {@code annotation.system.*} (C1): the field an annotation is declared on is
 * the column the schema names.
 *
 * <p>Sibling of {@link RelationshipSqlE2ETest} and written for the same reason. Until C1 the nine
 * field-level system annotations reached nothing: {@code DomainMetadata.systemFields} was populated
 * only from {@code @ExerisDomain}'s remote override attributes, so annotating a field said nothing
 * about the emitted schema. Processor tests assert the metadata JSON and generator tests run on
 * hand-built metadata, which leaves this seam covered by neither.
 *
 * <p><b>The annotations rename a column; they do not add one.</b> Whether the audit, soft-delete
 * and version columns exist at all is decided by {@code @ExerisDomain(audited/softDelete/versioned)}
 * — so the fixture sets those flags, and the assertions are about which names the columns get.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("System fields → SQL e2e: @TenantId / @Audit* / @SoftDelete* name the columns")
class SystemFieldSqlE2ETest {

    @TempDir
    static Path workspace;

    private static String createInvoices;

    @BeforeAll
    static void generateTheSchema() throws IOException {
        Path classes = workspace.resolve("target/classes");
        Path generated = workspace.resolve("src/main/generated/java");

        ProcessorCompiler.compile(workspace.resolve("src"), classes, null, sources());
        CodegenPipeline.createDefault()
                .run(classes.resolve("exeris-metadata"), generated, "com.shop");

        createInvoices = migration(generated, "invoices");
    }

    @Test
    @DisplayName("the audit columns take the names of the annotated fields")
    void auditColumnsFollowTheAnnotatedFields() {
        assertThat(createInvoices)
                .contains("born_at TIMESTAMPTZ")
                .contains("born_by VARCHAR(255)")
                .contains("touched_at TIMESTAMPTZ")
                .contains("touched_by VARCHAR(255)")
                // The canonical names are what the schema emitted before C1. Every one of the
                // four is excluded: the four roles resolve independently, so a rename that
                // works for one says nothing about the next.
                .doesNotContain("created_at TIMESTAMPTZ")
                .doesNotContain("created_by VARCHAR(255)")
                .doesNotContain("updated_at TIMESTAMPTZ")
                .doesNotContain("updated_by VARCHAR(255)");
    }

    @Test
    @DisplayName("the soft-delete trio and the version column follow theirs")
    void softDeleteAndVersionFollowTheirFields() {
        assertThat(createInvoices)
                .contains("archived BOOLEAN")
                .contains("archived_at TIMESTAMPTZ")
                .contains("archived_by VARCHAR(255)")
                .contains("rev BIGINT")
                .doesNotContain("deleted BOOLEAN")
                .doesNotContain("deleted_at TIMESTAMPTZ")
                .doesNotContain("deleted_by VARCHAR(255)")
                .doesNotContain("version BIGINT");
    }

    @Test
    @DisplayName("the tenant column follows @TenantId — column, RLS predicate and index alike")
    void tenantColumnFollowsItsField() {
        assertThat(createInvoices)
                .contains("org_id UUID NOT NULL")
                // The RLS predicate and the index are generated from the same resolved name.
                // A rename that reached only the column list would leave a policy filtering on
                // a column the table does not have.
                .contains("USING (org_id = NULLIF(")
                .contains("(org_id);")
                // Excluded in its column form only: 'exeris.tenant_id' is the session key the
                // policy reads, is not a column, and is correctly unaffected by the rename.
                .doesNotContain("tenant_id UUID");
    }

    /** Reads the one emitted migration whose file name contains {@code fragment}. */
    private static String migration(Path generated, String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .filter(p -> p.toString().endsWith(".sql"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/shop/domain/Invoice.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.system.AuditCreatedAt;
                import eu.exeris.sdk.annotation.system.AuditCreatedBy;
                import eu.exeris.sdk.annotation.system.AuditUpdatedAt;
                import eu.exeris.sdk.annotation.system.AuditUpdatedBy;
                import eu.exeris.sdk.annotation.system.SoftDelete;
                import eu.exeris.sdk.annotation.system.SoftDeleteTimestamp;
                import eu.exeris.sdk.annotation.system.SoftDeletedBy;
                import eu.exeris.sdk.annotation.system.TenantId;
                import eu.exeris.sdk.annotation.system.Version;

                @ExerisDomain(module = "billing", path = "/invoices",
                              audited = true, softDelete = true, versioned = true,
                              dataScope = ExerisDomain.DataScope.TENANT)
                public class Invoice {

                    @Field(label = "Number")
                    private String number;

                    @TenantId private String orgId;
                    @Version private long rev;
                    @SoftDelete private boolean archived;
                    @SoftDeleteTimestamp private String archivedAt;
                    @SoftDeletedBy private String archivedBy;
                    @AuditCreatedAt private String bornAt;
                    @AuditCreatedBy private String bornBy;
                    @AuditUpdatedAt private String touchedAt;
                    @AuditUpdatedBy private String touchedBy;
                }
                """);
        return sources;
    }
}
