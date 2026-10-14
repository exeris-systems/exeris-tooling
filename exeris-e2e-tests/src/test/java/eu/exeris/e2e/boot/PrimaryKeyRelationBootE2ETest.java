package eu.exeris.e2e.boot;

import eu.exeris.e2e.codegen.compile.GeneratedTree;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entity whose primary key is renamed ({@code primaryKeyField = "invoiceNo"}, ADR-104) is
 * created, read, updated, deleted, spectated, acted on and referenced on a <b>real</b> kernel boot.
 *
 * <pre>
 *   @ExerisDomain(primaryKeyField, realTimeApi) + @Action(streaming) + @Relationship source
 *        → javac + ExerisDomainProcessor      (real metadata)
 *        → CodegenPipeline.run                (repository, handler, publisher, stream handlers, router)
 *        → javac over the emitted tree + a consumer-style Application subclass
 *        → KernelBootstrap                    (http + persistence on in-memory H2, PostgreSQL mode)
 *        → a real socket                      (CRUD, GET /invoices/{id}/stream, POST …/actions/settle)
 * </pre>
 *
 * <p>The entity is {@code GLOBAL}: the processor refuses {@code realTimeApi} on a
 * tenant-partitioned one ({@code EXT-PROC-1014}). The route path variable stays {@code {id}}
 * (ADR-104 obligation 4); the body, the SQL and the stream id use the renamed key.
 *
 * <p>The tables are created by the harness, as in {@link ListRouteBootE2ETest}: the kernel applies
 * its own migrations only, and the emitted ones carry PostgreSQL row-level security that H2 does not
 * implement. The harness's key column and foreign-key clauses are the ones the emitted migrations
 * declare, which {@link #theHarnessSchemaIsTheEmittedSchema()} asserts.
 */
@Tag("e2e")
@Tag("boot")
@DisplayName("ADR-104 — a renamed primary key and the relations into it on a real kernel boot")
class PrimaryKeyRelationBootE2ETest {

    private static final String BASE_PACKAGE = "eu.exeris.e2e.billing";
    private static final String ALPHA = "00000000-0000-4000-8000-00000000000a";
    private static final String BRAVO = "00000000-0000-4000-8000-00000000000b";
    private static final String MISSING = "00000000-0000-4000-8000-0000000000ff";
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern UUID_VALUE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ID_MEMBER = Pattern.compile("\"id\"\\s*:");

    @TempDir
    static Path workspace;

    private static GeneratedTree app;
    private static BootedApplication booted;

    /** Makes each customer a PUT writes distinct, so a frame names the update that produced it. */
    private static final AtomicInteger REVISION = new AtomicInteger();

    @BeforeAll
    static void bootWithSeededRows() throws IOException {
        app = GeneratedTree.build(workspace, BASE_PACKAGE, domainSources(), harnessSources());
        booted = BootedApplication.start(app.loader(), BASE_PACKAGE + ".BillingApplication");
        assertThat(RawHttp.request(booted.port(), "POST", "/seed")).startsWith("HTTP/1.1 204");
    }

    @AfterAll
    static void stop() throws Exception {
        if (booted != null) {
            booted.close();
        }
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("the harness's key column and foreign keys are the emitted migrations' ones")
    void theHarnessSchemaIsTheEmittedSchema() throws IOException {
        assertThat(migration("create_invoices"))
                .contains("invoice_no UUID PRIMARY KEY DEFAULT gen_random_uuid()")
                .contains("corrects_id UUID");
        assertThat(migration("foreign_keys"))
                .contains("FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_no)")
                .contains("FOREIGN KEY (corrects_id) REFERENCES invoices(invoice_no)");
    }

    @Test
    @DisplayName("create assigns the renamed key; the body carries it and no id; GET, PUT and DELETE "
            + "address the row by it through the {id} path variable")
    void crudUsesTheRenamedKey() {
        String created = RawHttp.request(booted.port(), "POST", "/invoices", "{\"customer\":\"acme\"}");
        assertThat(created).startsWith("HTTP/1.1 201");
        String key = member(created, "invoiceNo");
        assertThat(key).as("the key the server assigned").matches("\"" + UUID_VALUE.pattern() + "\"");
        assertThat(body(created)).doesNotContainPattern(ID_MEMBER);
        String path = "/invoices/" + unquote(key);

        String read = RawHttp.request(booted.port(), "GET", path);
        assertThat(read).startsWith("HTTP/1.1 200");
        assertThat(member(read, "invoiceNo")).isEqualTo(key);
        assertThat(member(read, "customer")).isEqualTo("\"acme\"");
        assertThat(body(read)).doesNotContainPattern(ID_MEMBER);

        String put = RawHttp.request(booted.port(), "PUT", path,
                "{\"customer\":\"globex\",\"invoiceNo\":\"" + MISSING + "\"}");
        assertThat(put).startsWith("HTTP/1.1 200");
        assertThat(member(put, "invoiceNo")).as("a body naming another key does not move the row").isEqualTo(key);
        assertThat(member(put, "customer")).isEqualTo("\"globex\"");
        assertThat(member(RawHttp.request(booted.port(), "GET", path), "customer")).isEqualTo("\"globex\"");

        assertThat(RawHttp.request(booted.port(), "GET", "/invoices")).contains("\"invoiceNo\":" + key);
        assertThat(RawHttp.request(booted.port(), "DELETE", path)).startsWith("HTTP/1.1 204");
        assertThat(RawHttp.request(booted.port(), "GET", path)).startsWith("HTTP/1.1 404");
        assertThat(RawHttp.request(booted.port(), "DELETE", path)).startsWith("HTTP/1.1 404");
        assertThat(RawHttp.request(booted.port(), "PUT", path, "{\"customer\":\"x\"}")).startsWith("HTTP/1.1 404");
    }

    @Test
    @DisplayName("a row references another row of the same entity by its renamed key")
    void selfReferenceResolvesThroughTheRenamedKey() {
        String original = RawHttp.request(booted.port(), "POST", "/invoices", "{\"customer\":\"original\"}");
        String originalKey = unquote(member(original, "invoiceNo"));

        String credit = RawHttp.request(booted.port(), "POST", "/invoices",
                "{\"customer\":\"credit note\",\"correctsId\":\"" + originalKey + "\"}");
        assertThat(credit).startsWith("HTTP/1.1 201");
        assertThat(member(credit, "correctsId")).isEqualTo("\"" + originalKey + "\"");
        assertThat(member(credit, "invoiceNo")).isNotEqualTo("\"" + originalKey + "\"");

        String read = RawHttp.request(booted.port(), "GET", "/invoices/" + unquote(member(credit, "invoiceNo")));
        assertThat(member(read, "correctsId")).isEqualTo("\"" + originalKey + "\"");
    }

    @Test
    @DisplayName("a row of another entity references the renamed key; an unknown or still-referenced "
            + "key is refused by the foreign key: 422 naming the relationship, 409 on the delete")
    void foreignKeyTargetsTheRenamedKey() {
        String invoice = RawHttp.request(booted.port(), "POST", "/invoices", "{\"customer\":\"payer\"}");
        String key = unquote(member(invoice, "invoiceNo"));

        String payment = RawHttp.request(booted.port(), "POST", "/payments",
                "{\"amount\":12.5,\"invoiceId\":\"" + key + "\"}");
        assertThat(payment).startsWith("HTTP/1.1 201");
        assertThat(member(payment, "invoiceId")).isEqualTo("\"" + key + "\"");
        String paymentId = unquote(member(payment, "id"));
        assertThat(RawHttp.request(booted.port(), "GET", "/payments/" + paymentId))
                .startsWith("HTTP/1.1 200")
                .contains("\"invoiceId\":\"" + key + "\"");

        // ADR-076 Amendment 1: a missing referenced row is the caller's to correct (422, naming the
        // relationship), and a row other rows reference cannot be deleted until they go (409).
        assertThat(RawHttp.request(booted.port(), "POST", "/payments",
                "{\"amount\":1,\"invoiceId\":\"" + MISSING + "\"}"))
                .startsWith("HTTP/1.1 422")
                .contains("\"field\":\"invoiceId\"");
        assertThat(RawHttp.request(booted.port(), "POST", "/invoices",
                "{\"customer\":\"dangling\",\"correctsId\":\"" + MISSING + "\"}"))
                .startsWith("HTTP/1.1 422")
                .contains("\"field\":\"correctsId\"");
        assertThat(RawHttp.request(booted.port(), "DELETE", "/invoices/" + key)).startsWith("HTTP/1.1 409");
        assertThat(RawHttp.request(booted.port(), "GET", "/invoices/" + key)).startsWith("HTTP/1.1 200");

        assertThat(RawHttp.request(booted.port(), "DELETE", "/payments/" + paymentId)).startsWith("HTTP/1.1 204");
        assertThat(RawHttp.request(booted.port(), "DELETE", "/invoices/" + key)).startsWith("HTTP/1.1 204");
    }

    @Test
    @DisplayName("each spectator receives its own row's update, published after another row's")
    void spectatorReceivesOnlyItsRow() throws Exception {
        try (SseConnection alpha = SseConnection.open(booted.port(), "/invoices/" + ALPHA + "/stream");
             SseConnection bravo = SseConnection.open(booted.port(), "/invoices/" + BRAVO + "/stream")) {
            assertThat(alpha.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
            assertThat(bravo.head()).startsWith("HTTP/1.1 200");

            // The handler subscribes after the head is on the wire, so one update could race the
            // subscription: update until a frame arrives. Bravo is updated first each time, so a
            // stream that forwarded another row's event would see bravo's before its own.
            List<String> alphaFrame = alpha.awaitFrame(() -> {
                int revision = REVISION.incrementAndGet();
                assertThat(put(BRAVO, "bravo-" + revision)).startsWith("HTTP/1.1 200");
                assertThat(put(ALPHA, "alpha-" + revision)).startsWith("HTTP/1.1 200");
            }, FRAME_TIMEOUT);
            List<String> bravoFrame = bravo.awaitFrame(() -> { }, FRAME_TIMEOUT);

            assertThat(alphaFrame).hasSize(2);
            assertThat(alphaFrame.get(0)).isEqualTo("event: InvoiceRevised");
            assertThat(alphaFrame.get(1)).startsWith("data: {\"customer\":\"alpha-");
            assertThat(bravoFrame).hasSize(2);
            assertThat(bravoFrame.get(1)).startsWith("data: {\"customer\":\"bravo-");
        }
    }

    @Test
    @DisplayName("spectate refuses a missing row with the by-id GET's status")
    void spectateRefusalsAreStreamErrorFrames() throws Exception {
        try (SseConnection sse = SseConnection.open(booted.port(), "/invoices/" + MISSING + "/stream")) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200");
            assertThat(sse.awaitServerClose(CLOSE_TIMEOUT))
                    .containsExactly("event: stream-error",
                            "data: {\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}", "");
        }
    }

    @Test
    @DisplayName("a streaming action runs on the row its path names; the event published for another "
            + "row while it ran is filtered out")
    void actionStreamAddressesTheRowByItsRenamedKey() throws Exception {
        List<String> lines;
        try (SseConnection sse = SseConnection.open(booted.port(), "POST",
                "/invoices/" + ALPHA + "/actions/settle", null)) {
            assertThat(sse.head()).startsWith("HTTP/1.1 200").containsIgnoringCase("text/event-stream");
            lines = sse.awaitServerClose(CLOSE_TIMEOUT);
        }

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("event: InvoiceSettled");
        assertThat(lines.get(1)).startsWith("data: {\"customer\":\"").endsWith(" settled\"}");
        assertThat(lines).noneMatch(line -> line.contains("foreign"));
        assertThat(RawHttp.request(booted.port(), "GET", "/invoices/" + ALPHA))
                .startsWith("HTTP/1.1 200")
                .contains(" settled\"");
    }

    private static String put(String key, String customer) {
        return RawHttp.request(booted.port(), "PUT", "/invoices/" + key, "{\"customer\":\"" + customer + "\"}");
    }

    private static String migration(String fragment) throws IOException {
        try (Stream<Path> files = Files.walk(app.generatedRoot().resolve("db/migration"))) {
            Path file = files.filter(p -> p.getFileName().toString().contains(fragment))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no migration matching '" + fragment + "'"));
            return Files.readString(file);
        }
    }

    private static String body(String response) {
        return response.substring(response.indexOf("\r\n\r\n") + 4);
    }

    /** The raw JSON value of a top-level member of the response body: a quoted string, or a literal. */
    private static String member(String response, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\"[^\"]*\"|[^,}]*)").matcher(body(response));
        assertThat(m.find()).as("member %s in %s", name, body(response)).isTrue();
        return m.group(1);
    }

    private static String unquote(String json) {
        return json.substring(1, json.length() - 1);
    }

    // ------------------------------------------------------------------ harness

    private static Map<String, String> domainSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/billing/domain/Invoice.java",
                """
                package eu.exeris.e2e.billing.domain;

                import eu.exeris.sdk.annotation.Action;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.DomainEvent.Trigger;
                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/invoices", primaryKeyField = "invoiceNo",
                        realTimeApi = true)
                @DomainEvent(name = "InvoiceRevised", trigger = Trigger.UPDATE, topic = "invoices.revised",
                        includeFields = {"customer"})
                @DomainEvent(name = "InvoiceSettled", trigger = Trigger.ACTION, action = "settle",
                        topic = "invoices.settled", includeFields = {"customer"})
                public class Invoice {

                    private UUID invoiceNo;

                    @Field(label = "Customer", required = true)
                    private String customer;

                    @Relationship(targetEntity = Invoice.class, displayField = "customer")
                    private UUID correctsId;

                    public UUID getInvoiceNo() { return invoiceNo; }
                    public void setInvoiceNo(UUID invoiceNo) { this.invoiceNo = invoiceNo; }
                    public String getCustomer() { return customer; }
                    public void setCustomer(String customer) { this.customer = customer; }
                    public UUID getCorrectsId() { return correctsId; }
                    public void setCorrectsId(UUID correctsId) { this.correctsId = correctsId; }

                    @Action(name = "settle", label = "Settle", streaming = true)
                    public void settle() { this.customer = this.customer + " settled"; }
                }
                """);
        sources.put("eu/exeris/e2e/billing/domain/Payment.java",
                """
                package eu.exeris.e2e.billing.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.Relationship;

                import java.math.BigDecimal;
                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/payments")
                public class Payment {

                    private UUID id;

                    @Field(label = "Amount")
                    private BigDecimal amount;

                    @Relationship(targetEntity = Invoice.class, displayField = "customer")
                    private UUID invoiceId;

                    public UUID getId() { return id; }
                    public void setId(UUID id) { this.id = id; }
                    public BigDecimal getAmount() { return amount; }
                    public void setAmount(BigDecimal amount) { this.amount = amount; }
                    public UUID getInvoiceId() { return invoiceId; }
                    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }
                }
                """);
        return sources;
    }

    /**
     * {@code POST /seed} creates both tables with the emitted key column and foreign keys and writes
     * two invoices through the emitted repository. The service publishes an {@code InvoiceSettled}
     * for the bravo row whenever the alpha row is updated, so the alpha action stream's
     * subscription sees an event of its type under a foreign stream id before its own.
     */
    private static Map<String, String> harnessSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("eu/exeris/e2e/billing/BillingApplication.java",
                """
                package eu.exeris.e2e.billing;

                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                public class BillingApplication extends Application {

                    @Override
                    protected RuntimeComponents components(TransactionalExecutor transactionalExecutor) {
                        return new BillingComponents(transactionalExecutor);
                    }
                }
                """);
        sources.put("eu/exeris/e2e/billing/BillingComponents.java",
                """
                package eu.exeris.e2e.billing;

                import eu.exeris.e2e.billing.domain.Invoice;
                import eu.exeris.e2e.billing.repository.InvoiceRepository;
                import eu.exeris.e2e.billing.service.InvoiceService;
                import eu.exeris.kernel.core.http.routing.HttpRouter;
                import eu.exeris.kernel.spi.http.HttpMethod;
                import eu.exeris.kernel.spi.http.HttpStatus;
                import eu.exeris.kernel.spi.persistence.TransactionalExecutor;

                import java.util.UUID;

                public class BillingComponents extends RuntimeComponents {

                    static final UUID ALPHA = UUID.fromString("%s");
                    static final UUID BRAVO = UUID.fromString("%s");

                    private final TransactionalExecutor executor;

                    public BillingComponents(TransactionalExecutor transactionalExecutor) {
                        super(transactionalExecutor);
                        this.executor = transactionalExecutor;
                    }

                    @Override
                    protected InvoiceService createInvoiceService() {
                        return new ForeignPublishingService(invoiceRepository(), this);
                    }

                    @Override
                    public void configureRoutes(HttpRouter.Builder routes) {
                        routes.route(HttpMethod.GET, "/probe", exchange -> exchange.respond(HttpStatus.OK));
                        routes.route(HttpMethod.POST, "/seed", exchange -> {
                            executor.executeManaged(conn -> {
                                conn.executeUpdate("CREATE TABLE invoices (invoice_no UUID PRIMARY KEY, "
                                        + "customer VARCHAR(255), corrects_id UUID)");
                                conn.executeUpdate("CREATE TABLE payments (id UUID PRIMARY KEY, "
                                        + "amount DECIMAL(19, 4), invoice_id UUID)");
                                conn.executeUpdate("ALTER TABLE payments ADD CONSTRAINT fk_payments_invoice_id "
                                        + "FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_no)");
                                conn.executeUpdate("ALTER TABLE invoices ADD CONSTRAINT fk_invoices_corrects_id "
                                        + "FOREIGN KEY (corrects_id) REFERENCES invoices(invoice_no)");
                            });
                            seed(ALPHA, "alpha");
                            seed(BRAVO, "bravo");
                            exchange.respond(HttpStatus.NO_CONTENT);
                        });
                    }

                    private void seed(UUID key, String customer) {
                        Invoice invoice = new Invoice();
                        invoice.setInvoiceNo(key);
                        invoice.setCustomer(customer);
                        invoiceRepository().save(invoice);
                    }

                    static final class ForeignPublishingService extends InvoiceService {

                        private final BillingComponents components;

                        ForeignPublishingService(InvoiceRepository repository, BillingComponents components) {
                            super(repository);
                            this.components = components;
                        }

                        @Override
                        public Invoice update(UUID id, Invoice invoice) {
                            if (ALPHA.equals(id)) {
                                Invoice foreign = new Invoice();
                                foreign.setInvoiceNo(BRAVO);
                                foreign.setCustomer("foreign");
                                components.invoiceEventPublisher().publishInvoiceSettledEvent(BRAVO, foreign);
                            }
                            return super.update(id, invoice);
                        }
                    }
                }
                """.formatted(ALPHA, BRAVO));
        return sources;
    }
}
