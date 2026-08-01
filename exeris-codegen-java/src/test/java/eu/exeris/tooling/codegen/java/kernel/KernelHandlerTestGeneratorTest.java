package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-generator test for {@link KernelHandlerTestGenerator} (T2, ADR-058).
 *
 * <p>Shape only. That the emitted tests <em>compile and pass</em> against the emitted handler is
 * proven end-to-end by {@code GeneratedTestsE2ETest}, which is the assertion that actually matters
 * for a test emitter — substring checks here would happily accept a test that never runs.
 */
@DisplayName("KernelHandlerTestGenerator")
class KernelHandlerTestGeneratorTest {

    private static final DomainMetadata ORDER =
            DomainMetadata.builder("Order", "com.example.domain").path("/orders").build();

    @Test
    @DisplayName("emits <Entity>HandlerTest into the handler package, typed as a TEST artefact")
    void emitsHandlerTest() {
        GeneratedFile file = new KernelHandlerTestGenerator().generate(ORDER, "com.example");

        assertThat(file.className()).isEqualTo("OrderHandlerTest");
        assertThat(file.packageName()).isEqualTo("com.example.handler");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("covers the bodyless routes, including both handleGetById branches and the id guard")
    void coversTheBodylessRoutes() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("handler.handleGetAll(exchange)")
                .contains("HttpStatus.OK")
                .contains("HttpStatus.NOT_FOUND")
                .contains("HttpStatus.BAD_REQUEST")
                .contains("handler.handleDelete(exchange)")
                .contains("HttpStatus.NO_CONTENT");
    }

    @Test
    @DisplayName("covers the body-carrying routes' guard paths, which reject before the body is read")
    void coversTheBodyRouteGuards() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("handler.handleCreate(exchange)")
                .contains("handler.handleUpdate(exchange)")
                // Bodyless POST/PUT exchanges: parseBody rejects on hasBody() before resolving a
                // decoder, so no request-body double is needed to reach BAD_REQUEST.
                .contains("RecordingHttpExchange.post(")
                .contains("RecordingHttpExchange.put(")
                // Each guard test proves the short-circuit, not just the status.
                .contains("assertThat(service.saved).isNull()")
                .contains("assertThat(service.updatedId).isNull()");
    }

    @Test
    @DisplayName("the emitted test binds no kernel provider — that line is the next slice's decision")
    void bindsNoKernelProviderSlot() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        // The paths past a successful decode need a body decoder, a memory allocator and a
        // LoanedBuffer bound through the kernel's ScopedValue slots. Whether a *generated* test may
        // do that is a decision ADR-058 has not taken, so nothing here may quietly start.
        assertThat(source)
                .doesNotContain("ScopedValue")
                .doesNotContain("KernelProviders")
                .doesNotContain("LoanedBuffer");
    }

    @Test
    @DisplayName("the dependency contract holds: JUnit + AssertJ only, no mocking framework")
    void importsOnlyTheContractDependencies() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("import org.junit.jupiter.api.Test;")
                .contains("import org.assertj.core.api.Assertions;")
                .contains("import com.example.testsupport.RecordingHttpExchange;");
        assertThat(source)
                .doesNotContain("org.mockito")
                .doesNotContain("org.easymock")
                .doesNotContain("Mockito.");
    }

    @Test
    @DisplayName("the service double subclasses the generated service — no persistence stack involved")
    void stubsTheServiceBySubclassing() {
        String source = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(source)
                .contains("static final class StubOrderService extends OrderService")
                .contains("super((OrderRepository) null)")
                .contains("public List<Order> findAll()")
                .contains("public Optional<Order> findById(UUID id)")
                .contains("public void delete(UUID id)");
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs (no random UUID literal)")
    void emissionIsDeterministic() {
        String first = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();
        String second = new KernelHandlerTestGenerator().generate(ORDER, "com.example").content();

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("UUID.randomUUID()");
    }

    @Test
    @DisplayName("rejects a domain package that does not end with '.domain'")
    void rejectsNonDomainPackage() {
        DomainMetadata bad = DomainMetadata.builder("Order", "com.example.order").build();
        KernelHandlerTestGenerator generator = new KernelHandlerTestGenerator();

        assertThatThrownBy(() -> generator.generate(bad, "com.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.example.order")
                .hasMessageContaining(".domain");
    }
}
