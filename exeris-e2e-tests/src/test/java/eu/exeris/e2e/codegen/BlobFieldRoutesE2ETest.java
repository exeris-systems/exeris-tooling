package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.BlobFacet;
import eu.exeris.e2e.codegen.compile.GeneratedTree;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixture;
import eu.exeris.kernel.community.testkit.persistence.EmbeddedPersistenceEngineFixtures;
import eu.exeris.kernel.core.persistence.TransactionOrchestrator;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.persistence.TransactionalExecutor;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import eu.exeris.kernel.spi.storage.blob.BlobStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generated handler of an entity with a {@code @Blob} field, compiled and driven (ADR-108 and
 * its Amendment 1): the upload stores the body under the row's key and refuses an empty body and a
 * media type the field does not accept, {@code GET} answers the bytes and their type, {@code HEAD}
 * the type and length with no body, the entity's own JSON carries the field as {@code null}, the
 * field's {@code DELETE} removes the object, and a hard delete of the row removes it too.
 *
 * <p>The row lives in in-memory H2 over the emitted migration, through the generated repository
 * and service; the store is {@link InMemoryBlobStore}. The entity is {@code GLOBAL} so the
 * migration runs on H2 without row-level security: refusing a {@code @Blob} on such an entity is
 * the processor's, and the facet here is written onto the metadata ({@link BlobFacet}), which the
 * generators take as given.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("@Blob field routes: the generated handler stores, serves, describes and deletes the object")
class BlobFieldRoutesE2ETest {

    private static final String BASE_PACKAGE = "com.till";
    private static final byte[] PDF = "%PDF-1.7 receipt body".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static EmbeddedPersistenceEngineFixture database;
    private static MemoryAllocator allocator;
    private static Object repository;
    private static Class<?> entityType;
    private static Class<?> handlerType;

    private InMemoryBlobStore store;
    private Object handler;

    @BeforeAll
    static void generateCompileAndMigrate() throws Exception {
        app = GeneratedTree.build(workspace.resolve("app"), BASE_PACKAGE,
                Map.of("com/till/domain/Invoice.java", invoiceSource()), Map.of(),
                BlobFacet.on("Invoice", "receipt", List.of("application/pdf")));
        String migration = migration(app.generatedRoot());
        assertThat(migration).as("the blob field is no column").doesNotContain("receipt");

        database = EmbeddedPersistenceEngineFixtures.inMemoryH2();
        database.start();
        TransactionalExecutor executor = new TransactionOrchestrator(database.engine());
        String h2Migration = migration
                .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
                .replace("PRIMARY KEY DEFAULT gen_random_uuid()", "DEFAULT RANDOM_UUID() PRIMARY KEY");
        executor.executeManaged(connection -> statements(h2Migration).forEach(connection::executeUpdate));

        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        entityType = app.loader().loadClass(BASE_PACKAGE + ".domain.Invoice");
        handlerType = app.loader().loadClass(BASE_PACKAGE + ".handler.InvoiceHandler");
        repository = app.loader().loadClass(BASE_PACKAGE + ".repository.InvoiceRepository")
                .getConstructor(TransactionalExecutor.class).newInstance(executor);
    }

    @AfterAll
    static void close() throws IOException {
        if (allocator != null) {
            allocator.close();
        }
        if (database != null) {
            database.close();
        }
        if (app != null) {
            app.close();
        }
    }

    @BeforeEach
    void newHandler() throws ReflectiveOperationException {
        store = new InMemoryBlobStore();
        Class<?> serviceType = app.loader().loadClass(BASE_PACKAGE + ".service.InvoiceService");
        Object service = serviceType.getConstructor(repository.getClass()).newInstance(repository);
        handler = handlerType.getConstructor(serviceType, MemoryAllocator.class, BlobStore.class)
                .newInstance(service, allocator, store);
    }

    @Test
    @DisplayName("an upload stores the body under (container, id/field) with its Content-Type, and answers 204")
    void uploadStoresTheBody() throws Exception {
        UUID id = saveRow();

        Recorded upload = call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", PDF));

        assertThat(upload.status()).isEqualTo(204);
        InMemoryBlobStore.Stored stored = store.objects().get(new BlobRef("invoices", id + "/receipt"));
        assertThat(stored).isNotNull();
        assertThat(stored.bytes()).isEqualTo(PDF);
        assertThat(stored.contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("a media type the field does not accept answers 415, and an empty body 400; neither writes")
    void uploadRefusals() throws Exception {
        UUID id = saveRow();

        assertThat(call("handleUploadReceipt", request(HttpMethod.PUT, id, "image/png", PDF)).status())
                .isEqualTo(415);
        assertThat(call("handleUploadReceipt", request(HttpMethod.PUT, id, null, PDF)).status())
                .isEqualTo(415);
        assertThat(call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", new byte[0]))
                .status()).isEqualTo(400);
        assertThat(call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", null)).status())
                .isEqualTo(400);
        assertThat(store.objects()).isEmpty();
        assertThat(call("handleUploadReceipt", request(HttpMethod.PUT, id, "Application/PDF; version=1.7", PDF))
                .status()).as("the media type is compared without parameters, case-insensitively").isEqualTo(204);
    }

    @Test
    @DisplayName("an upload for an absent row answers 404 and writes nothing")
    void uploadForAnAbsentRow() throws Exception {
        assertThat(call("handleUploadReceipt", request(HttpMethod.PUT, UUID.randomUUID(), "application/pdf", PDF))
                .status()).isEqualTo(404);
        assertThat(store.objects()).isEmpty();
    }

    @Test
    @DisplayName("GET answers the stored bytes with their type; HEAD the type and length with no body")
    void downloadAndHead() throws Exception {
        UUID id = saveRow();
        call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", PDF));

        Recorded get = call("handleDownloadReceipt", request(HttpMethod.GET, id, null, null));
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(PDF);
        assertThat(get.header("Content-Type")).contains("application/pdf");

        Recorded head = call("handleDownloadReceipt", request(HttpMethod.HEAD, id, null, null));
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isNull();
        assertThat(head.header("Content-Type")).contains("application/pdf");
        assertThat(head.header("Content-Length")).contains(Integer.toString(PDF.length));
    }

    @Test
    @DisplayName("GET and HEAD answer 404 when nothing is stored")
    void downloadOfNothing() throws Exception {
        UUID id = saveRow();

        assertThat(call("handleDownloadReceipt", request(HttpMethod.GET, id, null, null)).status()).isEqualTo(404);
        assertThat(call("handleDownloadReceipt", request(HttpMethod.HEAD, id, null, null)).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the entity's JSON carries the field as null after an upload")
    void entityCarriesTheFieldAsNull() throws Exception {
        UUID id = saveRow();
        call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", PDF));

        Recorded get = call("handleGetById", request(HttpMethod.GET, id, null, null));

        assertThat(get.status()).isEqualTo(200);
        assertThat(get.payload()).isInstanceOf(entityType);
        assertThat(entityType.getMethod("getReceipt").invoke(get.payload())).isNull();
        assertThat(entityType.getMethod("getNumber").invoke(get.payload())).isEqualTo("INV-1");
    }

    @Test
    @DisplayName("the field's DELETE removes the object and answers 204, stored or not")
    void fieldDelete() throws Exception {
        UUID id = saveRow();
        call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", PDF));

        assertThat(call("handleDeleteReceipt", request(HttpMethod.DELETE, id, null, null)).status()).isEqualTo(204);
        assertThat(store.objects()).isEmpty();
        assertThat(call("handleDeleteReceipt", request(HttpMethod.DELETE, id, null, null)).status()).isEqualTo(204);
        assertThat(call("handleDeleteReceipt", request(HttpMethod.DELETE, UUID.randomUUID(), null, null)).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a hard delete of the row removes its object")
    void rowDeleteRemovesTheObject() throws Exception {
        UUID id = saveRow();
        call("handleUploadReceipt", request(HttpMethod.PUT, id, "application/pdf", PDF));
        assertThat(store.objects()).hasSize(1);

        assertThat(call("handleDelete", request(HttpMethod.DELETE, id, null, null)).status()).isEqualTo(204);

        assertThat(store.objects()).isEmpty();
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    /** What the handler answered: a raw response, or a typed one the kernel would encode. */
    record Recorded(int status, List<HttpHeader> headers, byte[] body, Object payload) {

        Optional<String> header(String name) {
            return headers.stream().filter(h -> h.nameEqualsIgnoreCase(name)).map(HttpHeader::value).findFirst();
        }
    }

    private static UUID saveRow() throws ReflectiveOperationException {
        Object invoice = entityType.getConstructor().newInstance();
        entityType.getMethod("setNumber", String.class).invoke(invoice, "INV-1");
        Object saved = repository.getClass().getMethod("save", entityType).invoke(repository, invoice);
        return (UUID) entityType.getMethod("getId").invoke(saved);
    }

    private static Request request(HttpMethod method, UUID id, String contentType, byte[] body) {
        return new Request(method, id, contentType, body);
    }

    private record Request(HttpMethod method, UUID id, String contentType, byte[] body) {
    }

    private Recorded call(String method, Request request) throws Exception {
        List<HttpHeader> headers = new ArrayList<>();
        if (request.contentType() != null) {
            headers.add(new HttpHeader("Content-Type", request.contentType()));
        }
        LoanedBuffer body = null;
        if (request.body() != null) {
            body = allocator.allocateNetwork(Math.max(request.body().length, 1));
            MemorySegment.copy(request.body(), 0, body.segment(), ValueLayout.JAVA_BYTE, 0, request.body().length);
            body.setSize(request.body().length);
        }
        String path = "/invoices/" + request.id();
        HttpRequest httpRequest = new HttpRequest(request.method(), path, HttpVersion.HTTP_1_1, List.copyOf(headers),
                body);
        List<Recorded> answers = new ArrayList<>();
        HttpExchange exchange = new HttpExchange() {
            @Override
            public HttpRequest request() {
                return httpRequest;
            }

            @Override
            public Map<String, String> pathParams() {
                return Map.of("id", request.id().toString());
            }

            @Override
            public void respond(HttpResponse response) {
                byte[] bytes = null;
                if (response.hasBody()) {
                    try (LoanedBuffer answered = response.body()) {
                        bytes = answered.segment().asSlice(0, answered.size()).toArray(ValueLayout.JAVA_BYTE);
                    }
                }
                answers.add(new Recorded(response.status().code(), response.headers(), bytes, null));
            }

            @Override
            public void respond(HttpTypedResponse response) {
                answers.add(new Recorded(response.status().code(), response.headers(), null, response.payload()));
            }
        };
        try {
            handlerType.getMethod(method, HttpExchange.class).invoke(handler, exchange);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } finally {
            if (body != null) {
                body.close();
            }
        }
        assertThat(answers).as("%s answers once", method).hasSize(1);
        return answers.getFirst();
    }

    /** The migration's statements, its comment lines dropped. */
    private static List<String> statements(String sql) {
        String code = sql.lines().filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        return Stream.of(code.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String migration(Path generated) throws IOException {
        try (Stream<Path> files = Files.walk(generated.resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains("__create_invoices"))
                    .filter(p -> p.toString().endsWith(".sql"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration creating invoices"));
            return Files.readString(file);
        }
    }

    private static String invoiceSource() {
        return """
                package com.till.domain;

                import eu.exeris.kernel.spi.storage.blob.BlobRef;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import java.util.UUID;

                @ExerisDomain(module = "till", path = "/invoices", dataScope = ExerisDomain.DataScope.GLOBAL)
                public class Invoice {

                    private UUID id;
                    private String number;
                    private BlobRef receipt;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public String getNumber() { return number; }
                    public void setNumber(String number) { this.number = number; }
                    public BlobRef getReceipt() { return receipt; }
                    public void setReceipt(BlobRef receipt) { this.receipt = receipt; }
                }
                """;
    }
}
