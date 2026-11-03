package eu.exeris.tooling.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two refusals only the generated kernel application needs: an entity package that does not
 * end in {@code .domain} ({@code EXT-PROC-1033}) and an accessor the generated code calls but the
 * entity lacks ({@code EXT-PROC-1034}), and {@code -Aexeris.metadataOnly}, which skips both.
 */
@DisplayName("ExerisDomainProcessor — the generated application's entity layout and accessors")
class KernelApplicationLayoutTest {

    private static final String PACKAGE_REFUSED = "EXT-PROC-1033";
    private static final String ACCESSOR_REFUSED = "EXT-PROC-1034";
    private static final String METADATA_ONLY = "-A" + ExerisDomainProcessor.OPTION_METADATA_ONLY + "=true";

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern PUBLIC_TYPE = Pattern.compile("^public\\s+(?:abstract\\s+)?class\\s+(\\w+)",
            Pattern.MULTILINE);

    @Nested
    @DisplayName("EXT-PROC-1033 — the entity package")
    class EntityPackage {

        @Test
        @DisplayName("an entity outside a .domain package is refused on the type, naming the package and the fix")
        void refusesPackageWithoutDomainSuffix() {
            Compilation compilation = compile("""
                    package com.shop.order;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import java.util.UUID;

                    @ExerisDomain(module = "shop", path = "/orders")
                    public class Order {
                        private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    }
                    """);

            assertThat(compilation).failed();
            List<String> refusals = errors(compilation, PACKAGE_REFUSED);
            assertThat(refusals).singleElement().satisfies(message -> assertThat(message)
                    .contains("'Order'")
                    .contains("package 'com.shop.order'")
                    .contains("does not end in '.domain'")
                    .contains("'com.shop.order.domain'")
                    .contains("'com.shop.order.repository'"));
            assertThat(errors(compilation, ACCESSOR_REFUSED)).isEmpty();
            assertThat(compilation).hadErrorContaining(PACKAGE_REFUSED).inFile(source(compilation))
                    .onLineContaining("@ExerisDomain(");
        }

        @Test
        @DisplayName("a package ending in a segment that only ends with 'domain' is refused")
        void refusesSegmentEndingInDomain() {
            Compilation compilation = compile("""
                    package com.shop.subdomain;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import java.util.UUID;

                    @ExerisDomain(module = "shop", path = "/orders")
                    public class Order {
                        private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    }
                    """);

            assertThat(errors(compilation, PACKAGE_REFUSED)).hasSize(1);
        }

        @Test
        @DisplayName("an entity in a .domain package with its accessors compiles")
        void acceptsDomainPackage() {
            Compilation compilation = compile("""
                    package com.shop.order.domain;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import java.util.UUID;

                    @ExerisDomain(module = "shop", path = "/orders")
                    public class Order {
                        private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    }
                    """);

            assertThat(compilation).succeededWithoutWarnings();
        }
    }

    @Nested
    @DisplayName("EXT-PROC-1034 — the accessors the generated code calls")
    class Accessors {

        @Test
        @DisplayName("a field without accessors is refused on the field, naming the entity, the methods and the fix")
        void refusesFieldWithoutAccessors() {
            Compilation compilation = compile(entity("", """
                    private String title;
                    """));

            assertThat(compilation).failed();
            assertThat(errors(compilation, ACCESSOR_REFUSED)).singleElement().satisfies(message -> assertThat(message)
                    .contains("getTitle() and setTitle(…)")
                    .contains("@ExerisDomain entity 'Note'")
                    .contains("its field 'title'")
                    .contains("'public String getTitle()' and 'public void setTitle(String title)'"));
            assertThat(compilation).hadErrorContaining(ACCESSOR_REFUSED).inFile(source(compilation))
                    .onLineContaining("private String title;");
        }

        @Test
        @DisplayName("only the missing half is named: a field with a getter needs its setter")
        void namesOnlyTheMissingAccessor() {
            Compilation compilation = compile(entity("", """
                    private String title;
                    public String getTitle() { return title; }
                    """));

            assertThat(errors(compilation, ACCESSOR_REFUSED)).singleElement().satisfies(message -> assertThat(message)
                    .startsWith("[Exeris] " + ACCESSOR_REFUSED + ": The generated application calls setTitle(…) on")
                    .doesNotContain("getTitle"));
        }

        @Test
        @DisplayName("a primitive boolean column is read through is<Name>(), so get<Name>() does not satisfy it")
        void primitiveBooleanNeedsIsGetter() {
            Compilation refused = compile(entity("", """
                    private boolean active;
                    public boolean getActive() { return active; }
                    public void setActive(boolean active) { this.active = active; }
                    """));
            assertThat(errors(refused, ACCESSOR_REFUSED)).singleElement().satisfies(message -> assertThat(message)
                    .contains("isActive()").contains("'public boolean isActive()'"));

            Compilation accepted = compile(entity("", """
                    private boolean active;
                    private Boolean archived;
                    public boolean isActive() { return active; }
                    public void setActive(boolean active) { this.active = active; }
                    public Boolean getArchived() { return archived; }
                    public void setArchived(Boolean archived) { this.archived = archived; }
                    """));
            assertThat(accepted).succeededWithoutWarnings();
        }

        @Test
        @DisplayName("static and transient fields need no accessors")
        void staticAndTransientFieldsNeedNothing() {
            Compilation compilation = compile(entity("", """
                    public static final String KIND = "note";
                    private static int created;
                    private transient String rendered;
                    """));

            assertThat(compilation).succeededWithoutWarnings();
        }

        @Test
        @DisplayName("a @Blob field is no column: it needs the setter the handlers reset it through, and no getter")
        void blobFieldNeedsOnlySetter() {
            Compilation accepted = compile(entity(", dataScope = ExerisDomain.DataScope.TENANT", """
                    @eu.exeris.sdk.annotation.Blob
                    private eu.exeris.kernel.spi.storage.blob.BlobRef scan;
                    public void setScan(eu.exeris.kernel.spi.storage.blob.BlobRef scan) { this.scan = scan; }
                    """ + TENANT_ACCESSORS));
            assertThat(accepted).succeededWithoutWarnings();

            Compilation refused = compile(entity(", dataScope = ExerisDomain.DataScope.TENANT", """
                    @eu.exeris.sdk.annotation.Blob
                    private eu.exeris.kernel.spi.storage.blob.BlobRef scan;
                    """ + TENANT_ACCESSORS));
            assertThat(errors(refused, ACCESSOR_REFUSED)).singleElement().satisfies(message -> assertThat(message)
                    .contains("calls setScan(…) on").doesNotContain("getScan"));
        }

        @Test
        @DisplayName("accessors inherited from a superclass count")
        void inheritedAccessorsCount() {
            Compilation compilation = compile("""
                    package com.example.notes.domain;

                    import java.util.UUID;

                    public abstract class Identified {
                        private UUID id;
                        public UUID getId() { return id; }
                        public void setId(UUID id) { this.id = id; }
                    }
                    """, entity("", "").replace("public class Note {\n    private UUID id;\n"
                    + "    public UUID getId() { return id; }\n    public void setId(UUID id) { this.id = id; }\n",
                    "public class Note extends Identified {\n"));

            assertThat(compilation).succeededWithoutWarnings();
        }

        @Test
        @DisplayName("a package-private or static accessor does not count: the generated code calls it from "
                + "another package, on an instance")
        void nonPublicOrStaticAccessorRefused() {
            Compilation compilation = compile(entity("", """
                    private String title;
                    String getTitle() { return title; }
                    public static void setTitle(String title) { }
                    """));

            assertThat(errors(compilation, ACCESSOR_REFUSED)).singleElement().satisfies(message -> assertThat(message)
                    .contains("getTitle() and setTitle(…)"));
        }

        @Test
        @DisplayName("a TENANT entity needs the owner column's accessors even without a field, and names them "
                + "on the type")
        void tenantColumnNeedsAccessors() {
            Compilation refused = compile(entity(", dataScope = ExerisDomain.DataScope.TENANT", ""));
            assertThat(errors(refused, ACCESSOR_REFUSED)).singleElement().satisfies(message -> assertThat(message)
                    .contains("getTenantId() and setTenantId(…)")
                    .contains("the property 'tenantId', which 'Note' declares no field for")
                    .contains("usually by declaring the field 'tenantId' with its getter and setter"));
            assertThat(refused).hadErrorContaining(ACCESSOR_REFUSED).inFile(source(refused))
                    .onLineContaining("@ExerisDomain(");

            Compilation accepted = compile(entity(", dataScope = ExerisDomain.DataScope.TENANT", TENANT_ACCESSORS));
            assertThat(accepted).succeededWithoutWarnings();
        }

        @Test
        @DisplayName("audited, soft-deleted and versioned entities need their system columns' accessors, the "
                + "soft-delete flag as is<Name>()")
        void systemColumnsNeedAccessors() {
            String flags = ", audited = true, softDelete = true, versioned = true";
            Compilation refused = compile(entity(flags, ""));
            assertThat(errors(refused, ACCESSOR_REFUSED))
                    .anySatisfy(message -> assertThat(message).contains("getCreatedAt() and setCreatedAt(…)"))
                    .anySatisfy(message -> assertThat(message).contains("getUpdatedAt() and setUpdatedAt(…)"))
                    .anySatisfy(message -> assertThat(message).contains("isDeleted() and setDeleted(…)"))
                    .anySatisfy(message -> assertThat(message).contains("getVersion() and setVersion(…)"))
                    .hasSize(4);

            Compilation accepted = compile(entity(flags, """
                    private java.time.Instant createdAt;
                    private java.time.Instant updatedAt;
                    private boolean deleted;
                    private Long version;
                    public java.time.Instant getCreatedAt() { return createdAt; }
                    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
                    public java.time.Instant getUpdatedAt() { return updatedAt; }
                    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }
                    public boolean isDeleted() { return deleted; }
                    public void setDeleted(boolean deleted) { this.deleted = deleted; }
                    public Long getVersion() { return version; }
                    public void setVersion(Long version) { this.version = version; }
                    """));
            assertThat(accepted).succeededWithoutWarnings();
        }

        @Test
        @DisplayName("a missing key field is EXT-PROC-1015 alone: its accessors are not reported again")
        void missingKeyIsNotReportedTwice() {
            Compilation compilation = compile("""
                    package com.example.notes.domain;

                    import eu.exeris.sdk.annotation.ExerisDomain;

                    @ExerisDomain(module = "notes", path = "/notes")
                    public class Note {
                    }
                    """);

            assertThat(compilation).hadErrorContaining("EXT-PROC-1015");
            assertThat(errors(compilation, ACCESSOR_REFUSED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("-Aexeris.metadataOnly")
    class MetadataOnly {

        @TempDir
        Path out;

        @Test
        @DisplayName("skips both refusals and writes the same metadata")
        void skipsBothRefusalsAndKeepsTheMetadata() throws IOException {
            String source = """
                    package com.example;

                    import eu.exeris.sdk.annotation.ExerisDomain;
                    import eu.exeris.sdk.annotation.Field;
                    import java.util.UUID;

                    @ExerisDomain(module = "notes", path = "/notes", dataScope = ExerisDomain.DataScope.TENANT)
                    public class Note {
                        private UUID id;
                        @Field(label = "Title", required = true)
                        private String title;
                    }
                    """;
            Compilation refused = compile(source);
            assertThat(errors(refused, PACKAGE_REFUSED)).hasSize(1);
            assertThat(errors(refused, ACCESSOR_REFUSED)).hasSize(3);

            Compilation metadataOnly = javac()
                    .withOptions(METADATA_ONLY)
                    .withProcessors(new ExerisDomainProcessor())
                    .compile(sourceFile(source));
            assertThat(metadataOnly).succeededWithoutWarnings();
            assertThat(metadataOnly.generatedFile(StandardLocation.CLASS_OUTPUT, "exeris-metadata/Note.json")
                    .orElseThrow().getCharContent(true).toString())
                    .isEqualTo(metadataWritten(source, List.of(), out.resolve("refused")))
                    .isEqualTo(metadataWritten(source, List.of(METADATA_ONLY), out.resolve("metadata-only")));
        }

        /** The metadata a run with {@code options} writes, whether or not it reports errors. */
        private String metadataWritten(String source, List<String> options, Path dir) throws IOException {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Files.createDirectories(dir);
            try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(dir));
                List<String> arguments = new ArrayList<>(List.of("-proc:only", "-classpath",
                        System.getProperty("java.class.path")));
                arguments.addAll(options);
                JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostic -> { },
                        arguments, null, List.of(sourceFile(source)));
                task.setProcessors(List.of(new ExerisDomainProcessor()));
                task.call();
            }
            return Files.readString(dir.resolve("exeris-metadata").resolve("Note.json"));
        }
    }

    /** The owner column's accessors a TENANT entity without a {@code tenantId} field still needs. */
    private static final String TENANT_ACCESSORS = """
            public UUID getTenantId() { return null; }
            public void setTenantId(UUID tenantId) { }
            """;

    /** {@code com.example.notes.domain.Note} with its key and accessors, the extra attributes and members. */
    private static String entity(String attributes, String members) {
        return """
                package com.example.notes.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import java.util.UUID;

                @ExerisDomain(module = "notes", path = "/notes"%s)
                public class Note {
                    private UUID id;
                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                %s}
                """.formatted(attributes, members.indent(4));
    }

    private static Compilation compile(String... sources) {
        List<JavaFileObject> files = new ArrayList<>();
        for (String source : sources) {
            files.add(sourceFile(source));
        }
        return javac().withProcessors(new ExerisDomainProcessor()).compile(files);
    }

    private static JavaFileObject sourceFile(String source) {
        Matcher pkg = PACKAGE.matcher(source);
        Matcher type = PUBLIC_TYPE.matcher(source);
        if (!pkg.find() || !type.find()) {
            throw new IllegalArgumentException("a fixture declares a package and a public class");
        }
        return JavaFileObjects.forSourceString(pkg.group(1) + "." + type.group(1), source);
    }

    /** The messages of the compilation's errors carrying {@code id}. */
    private static List<String> errors(Compilation compilation, String id) {
        return compilation.errors().stream()
                .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                .filter(message -> message.contains("[Exeris] " + id + ":"))
                .toList();
    }

    /** The source of the first error, the entity the refusals point into. */
    private static JavaFileObject source(Compilation compilation) {
        Diagnostic<? extends JavaFileObject> first = compilation.errors().getFirst();
        return first.getSource();
    }
}
