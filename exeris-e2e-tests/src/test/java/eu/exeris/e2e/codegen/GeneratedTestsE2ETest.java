package eu.exeris.e2e.codegen;

import eu.exeris.e2e.codegen.compile.ProcessorCompiler;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The T2 gate: the generated tests are compiled <b>and executed</b> against the generated code
 * they cover.
 *
 * <p>Compiling them would only prove they are syntactically valid against the current emitters. A
 * test emitter whose output is never run is the inert-output failure mode this repo refuses
 * everywhere else — a green build would say nothing about whether {@code OrderHandlerTest} passes,
 * and a wrong expected status would ship silently. So this walks the whole path:
 *
 * <pre>
 *   @ExerisDomain source
 *        → javac + processor            (real metadata)
 *        → CodegenPipeline.run          (handler + service + repository + …)
 *        → CodegenPipeline.runTests     (the generated tests, ADR-058)
 *        → javac over BOTH trees        (they must compile together)
 *        → JUnit Platform launcher      (they must pass)
 * </pre>
 *
 * <p>It also pins the ADR-058 dependency contract in the only way that cannot drift: the emitted
 * trees are compiled against a classpath carrying the kernel SPI, JUnit and AssertJ — and nothing
 * else. An emitter that starts reaching for a mocking framework fails here.
 */
@Tag("e2e")
@Tag("codegen")
@DisplayName("Generated tests (T2): the emitted tests compile against the emitted code and pass")
class GeneratedTestsE2ETest {

    private static final String BASE_PACKAGE = "com.shop";

    @TempDir
    static Path workspace;

    private static Path entityClasses;
    private static Path generatedMain;
    private static Path generatedTests;
    private static Path compiled;

    @BeforeAll
    static void generateAndCompile() throws IOException {
        entityClasses = workspace.resolve("target/classes");
        Path classes = entityClasses;
        generatedMain = workspace.resolve("src/main/generated/java");
        generatedTests = workspace.resolve("src/test/generated/java");
        compiled = workspace.resolve("target/test-classes");

        ProcessorCompiler.compile(workspace.resolve("src"), classes, null, sources());

        CodegenPipeline pipeline = CodegenPipeline.createDefault();
        Path metadataDir = classes.resolve("exeris-metadata");
        pipeline.run(metadataDir, generatedMain, BASE_PACKAGE);
        pipeline.runTests(metadataDir, generatedTests, BASE_PACKAGE);

        compile(javaSourcesUnder(generatedMain, generatedTests), compiled);
    }

    @Test
    @DisplayName("the emitted tests land in the TEST root, never in the main one")
    void testsLandInTheirOwnRoot() {
        // The whole reason runTests takes a separate output root: a test under src/main/generated
        // would compile into the application artefact and drag JUnit onto its runtime classpath.
        assertThat(generatedTests.resolve("com/shop/handler/OrderHandlerTest.java")).exists();
        assertThat(generatedTests.resolve("com/shop/service/OrderServiceTest.java")).exists();
        assertThat(generatedTests.resolve("com/shop/repository/OrderRepositoryTest.java")).exists();
        assertThat(generatedTests.resolve("com/shop/repository/InvoiceRepositoryTest.java")).exists();
        assertThat(generatedTests.resolve("com/shop/testsupport/RecordingHttpExchange.java")).exists();
        assertThat(generatedTests.resolve("com/shop/testsupport/RecordingPersistence.java")).exists();
        assertThat(generatedTests.resolve("com/shop/testsupport/RecordingRequestBody.java")).exists();
        assertThat(generatedMain.resolve("com/shop/handler/OrderHandlerTest.java")).doesNotExist();
        assertThat(generatedMain.resolve("com/shop/service/OrderServiceTest.java")).doesNotExist();
        assertThat(generatedMain.resolve("com/shop/handler/OrderHandler.java")).exists();
    }

    @Test
    @DisplayName("each tree owns its own T13 manifest, so pruning one can never touch the other")
    void eachRootHasItsOwnManifest() {
        assertThat(generatedMain.resolve(".exeris-codegen-manifest")).exists();
        assertThat(generatedTests.resolve(".exeris-codegen-manifest")).exists();
    }

    @Test
    @DisplayName("the emitted tests pass when run against the emitted code")
    void generatedTestsPass() throws Exception {
        // Both roots: the generated tree AND the @ExerisDomain entity the processor compiled.
        try (URLClassLoader appLoader = new URLClassLoader(
                new URL[]{compiled.toUri().toURL(), entityClasses.toUri().toURL()},
                GeneratedTestsE2ETest.class.getClassLoader())) {

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(
                            DiscoverySelectors.selectClass(
                                    Class.forName("com.shop.handler.OrderHandlerTest", true, appLoader)),
                            DiscoverySelectors.selectClass(
                                    Class.forName("com.shop.service.OrderServiceTest", true, appLoader)),
                            DiscoverySelectors.selectClass(
                                    Class.forName("com.shop.repository.OrderRepositoryTest", true, appLoader)),
                            // The system-column entity: its round-trip is the only executed proof
                            // that the tenant/audit/soft-delete/version bind-read pairs line up.
                            DiscoverySelectors.selectClass(
                                    Class.forName("com.shop.repository.InvoiceRepositoryTest", true, appLoader)),
                            DiscoverySelectors.selectClass(
                                    Class.forName("com.shop.saga.OrderSagaFlowTest", true, appLoader)))
                    .build();

            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(appLoader);
            try {
                launcher.execute(request, listener);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }

            TestExecutionSummary summary = listener.getSummary();
            assertThat(summary.getTestsFailedCount())
                    .as("generated-test failures:%n%s", render(summary))
                    .isZero();
            // Guard against a vacuous pass: an emitter that stopped emitting @Test methods would
            // otherwise "succeed" with zero executed tests. 20 handler cases (9 covering the
            // bodyless routes and the pre-decode guards — including the ADR-076 pair that pins
            // both sides of a DELETE: 204 when a row matched, 404 when none did — plus 11
            // @Validation cases: the baseline
            // accept, a reject and a boundary accept for each of orderNumber's two length rules
            // and quantity's two numeric ones, the not-null reject, and the one case proving
            // handleUpdate carries the same guard) + 7 service cases (six CRUD delegations and
            // the one T8 finder the fixture carries) + 7 repository cases for Order (the save/load
            // round-trip and the six paths around it) + 9 for Invoice — the entity that carries
            // every system column, and the only tenant-partitioned one here, so it alone gets the
            // T36 pair proving save stamps an absent tenant and keeps one the caller set — + 4
            // saga cases.
            assertThat(summary.getTestsSucceededCount()).isEqualTo(47);
        }
    }

    private static String render(TestExecutionSummary summary) {
        StringWriter out = new StringWriter();
        summary.printFailuresTo(new PrintWriter(out), 20);
        return out.toString();
    }

    /**
     * Compiles the generated sources against the ADR-058 contract classpath.
     *
     * <p>The classpath is <b>named</b>, not inherited. Handing javac
     * {@code System.getProperty("java.class.path")} would make the "and nothing else" half of the
     * contract accidentally true: it holds only for as long as nobody adds a mocking framework to
     * this module, and the day someone does, an emitter could start importing it and this gate
     * would stay green. Naming the permitted artefacts one anchor class at a time makes the
     * contract the thing under test.
     */
    private static void compile(List<String> files, Path outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not a JRE) is required").isNotNull();
        Files.createDirectories(outputDir);

        List<String> args = new ArrayList<>(List.of(
                "-d", outputDir.toString(),
                "-classpath", contractClasspath(),
                // Same release as the reactor and as InMemoryJavaCompiler — the emitted
                // tests must compile at the level a consumer builds at.
                "--release", "25",
                "-nowarn"));
        args.addAll(files);

        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, diagnostics, args.toArray(String[]::new));
        assertThat(rc).as("generated sources must compile:%n%s", diagnostics).isZero();
    }

    /**
     * Everything the emitted trees are allowed to see, and nothing more.
     *
     * <p>Two groups, for two different reasons. The kernel entries are what the generated
     * <em>main</em> code targets — a downstream app has them because that is what it runs on. The
     * JUnit and AssertJ entries are the ADR-058 §2 dependency contract for the generated
     * <em>tests</em>: turning {@code -Dexeris.tests=true} on must cost a consumer those two
     * test-scope dependencies and no others, because tooling emits no {@code pom.xml} and so cannot
     * declare them.
     */
    private static String contractClasspath() {
        List<String> entries = new ArrayList<>(List.of(
                // What the generated main code binds.
                codeSourceOf("eu.exeris.kernel.spi.http.HttpExchange"),
                codeSourceOf("eu.exeris.kernel.core.bootstrap.KernelBootstrap"),
                // No slf4j anchor, deliberately. Generated code used to bind org.slf4j, which
                // reaches an app only through the kernel's driver tier; it now logs through
                // java.lang.System.Logger. Its absence here is the enforcement: if an emitter
                // reintroduced the facade, these sources would stop compiling.
                // What the generated tests may import — ADR-058 §2.
                codeSourceOf("org.junit.jupiter.api.Test"),
                codeSourceOf("org.assertj.core.api.Assertions"),
                // The @ExerisDomain entity the processor compiled; generated code binds it.
                entityClasses.toString()));
        return String.join(File.pathSeparator, entries);
    }

    /** Resolves one permitted artefact to the jar (or classes dir) it was loaded from. */
    private static String codeSourceOf(String className) {
        try {
            CodeSource source = Class.forName(className).getProtectionDomain().getCodeSource();
            assertThat(source).as("no code source for contract anchor %s", className).isNotNull();
            return Path.of(source.getLocation().toURI()).toString();
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException("contract-classpath anchor is missing: " + className, e);
        }
    }

    private static List<String> javaSourcesUnder(Path... roots) throws IOException {
        List<String> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> tree = Files.walk(root)) {
                tree.filter(p -> p.toString().endsWith(".java")).map(Path::toString).forEach(files::add);
            }
        }
        return files;
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("com/shop/domain/Order.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;
                import eu.exeris.sdk.annotation.DomainEvent;
                import eu.exeris.sdk.annotation.Saga;
                import eu.exeris.sdk.annotation.SagaStep;
                import eu.exeris.sdk.annotation.Validation;

                import java.math.BigDecimal;
                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "sales", path = "/orders")
                // T48: one @DomainEvent, so the emitted handler test actually constructs the
                // two-argument handler over a real publisher and a RecordingEventEngine. ADR-058
                // says a test emitter is not proven until this gate RUNS its output, and the
                // publisher branch of newHandler() is emitted code — without an event here it
                // would be emitted and never executed.
                @DomainEvent(name = "OrderPlaced", trigger = DomainEvent.Trigger.CREATE, topic = "orders.placed")
                // Three steps, one of them compensating: enough for the emitted saga test to have
                // a transition chain to check (a single-step saga has none) and a compensation
                // branch to exercise.
                @Saga(name = "OrderSaga", timeout = "PT30M", maxRetries = 3)
                public class Order {

                    private UUID id;

                    // filterable so the entity carries a T8 finder — the generated service test
                    // emits one delegation case per finder, and that path is only proven if the
                    // fixture actually has one. The length bounds give the emitted validation
                    // cases a boundary to sit on: without one, a reject-only case would survive
                    // an emitter that wrote <= where it meant <.
                    @Field(label = "Order Number", required = true, filterable = true)
                    @Validation(minLength = 3, maxLength = 8)
                    private String orderNumber;

                    // Not filterable (so they add no finder), but each takes a different bind /
                    // read pair through the repository — an int, a primitive boolean read as
                    // isExpedited(), a BigDecimal that round-trips through a String, and an
                    // Instant that binds natively. The generated round-trip covers all of them.
                    // A primitive with both bounds: the numeric half of the validation guard
                    // takes a different emission path from the String half (operators rather
                    // than length()), and a primitive takes a different one again from a boxed
                    // numeric — no null guard.
                    @Field(label = "Quantity")
                    @Validation(min = 1, max = 99)
                    private int quantity;

                    @Field(label = "Expedited")
                    private boolean expedited;

                    @Field(label = "Total")
                    private BigDecimal total;

                    @Field(label = "Placed At")
                    private Instant placedAt;

                    public UUID getId() {
                        return id;
                    }

                    public void setId(UUID id) {
                        this.id = id;
                    }

                    public String getOrderNumber() {
                        return orderNumber;
                    }

                    public void setOrderNumber(String orderNumber) {
                        this.orderNumber = orderNumber;
                    }

                    public int getQuantity() {
                        return quantity;
                    }

                    public void setQuantity(int quantity) {
                        this.quantity = quantity;
                    }

                    public boolean isExpedited() {
                        return expedited;
                    }

                    public void setExpedited(boolean expedited) {
                        this.expedited = expedited;
                    }

                    public BigDecimal getTotal() {
                        return total;
                    }

                    public void setTotal(BigDecimal total) {
                        this.total = total;
                    }

                    public Instant getPlacedAt() {
                        return placedAt;
                    }

                    public void setPlacedAt(Instant placedAt) {
                        this.placedAt = placedAt;
                    }

                    @SagaStep(order = 0, name = "reserveStock", service = "inventory",
                            command = "ReserveStock", compensation = "ReleaseStock")
                    public void reserveStock() {}

                    @SagaStep(order = 1, name = "chargePayment", service = "payments",
                            command = "ChargePayment")
                    public void chargePayment() {}

                    @SagaStep(order = 2, name = "ship", service = "logistics",
                            command = "Ship")
                    public void ship() {}
                }
                """);
        // A second entity carrying every system-column flag. Order covers the domain columns;
        // this one is the only way the TENANT_ID / CREATED_AT / UPDATED_AT / DELETED / VERSION
        // bind-read pairs — appended after the domain columns, each with its own accessor rule —
        // get a compiled, executed round-trip rather than a text-shape unit assertion.
        sources.put("com/shop/domain/Invoice.java",
                """
                package com.shop.domain;

                import eu.exeris.sdk.annotation.ExerisDomain;
                import eu.exeris.sdk.annotation.Field;

                import java.time.Instant;
                import java.util.UUID;

                @ExerisDomain(module = "billing", path = "/invoices",
                        tenantScoped = true, audited = true, softDelete = true, versioned = true)
                public class Invoice {

                    private UUID id;

                    @Field(label = "Reference", required = true)
                    private String reference;

                    // The system columns the flags above switch on. Declared here (rather than
                    // inherited) because the generated repository binds them by accessor, and
                    // annotated so they stay out of the finder surface.
                    @Field(label = "Tenant")
                    private UUID tenantId;

                    @Field(label = "Created At")
                    private Instant createdAt;

                    @Field(label = "Updated At")
                    private Instant updatedAt;

                    @Field(label = "Deleted")
                    private boolean deleted;

                    // Deliberately the WRAPPER, not `long`: this is the declaration that used to
                    // NPE on the first save() of a fresh entity (T26), because the emitter bound
                    // the version by unboxing. Keeping it boxed here is the regression test — a
                    // primitive would pass whether or not the fix is present.
                    @Field(label = "Version")
                    private Long version;

                    public UUID getId() {
                        return id;
                    }

                    public void setId(UUID id) {
                        this.id = id;
                    }

                    public String getReference() {
                        return reference;
                    }

                    public void setReference(String reference) {
                        this.reference = reference;
                    }

                    public UUID getTenantId() {
                        return tenantId;
                    }

                    public void setTenantId(UUID tenantId) {
                        this.tenantId = tenantId;
                    }

                    public Instant getCreatedAt() {
                        return createdAt;
                    }

                    public void setCreatedAt(Instant createdAt) {
                        this.createdAt = createdAt;
                    }

                    public Instant getUpdatedAt() {
                        return updatedAt;
                    }

                    public void setUpdatedAt(Instant updatedAt) {
                        this.updatedAt = updatedAt;
                    }

                    public boolean isDeleted() {
                        return deleted;
                    }

                    public void setDeleted(boolean deleted) {
                        this.deleted = deleted;
                    }

                    public Long getVersion() {
                        return version;
                    }

                    public void setVersion(Long version) {
                        this.version = version;
                    }
                }
                """);
        return sources;
    }
}
