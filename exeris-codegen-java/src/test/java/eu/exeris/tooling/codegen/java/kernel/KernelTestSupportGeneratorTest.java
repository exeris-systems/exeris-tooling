package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KernelTestSupportGenerator")
class KernelTestSupportGeneratorTest {

    @Test
    @DisplayName("emits RecordingHttpExchange once per app, under <basePackage>.testsupport")
    void emitsTheExchangeDouble() {
        GeneratedFile file = new KernelTestSupportGenerator().generate("com.example");

        assertThat(file.className()).isEqualTo("RecordingHttpExchange");
        assertThat(file.packageName()).isEqualTo("com.example.testsupport");
        assertThat(file.artifactType()).isEqualTo(ArtifactType.TEST);
    }

    @Test
    @DisplayName("overrides every respond(...) overload, the interface defaults included")
    void overridesEveryRespondOverload() {
        // The defaults build an HttpResponse through the codec path, which needs an encoder bound
        // at runtime — a double that inherited them would drag kernel runtime state into a plain
        // handler unit test, which is the whole thing this type exists to avoid.
        String source = new KernelTestSupportGenerator().generate("com.example").content();

        assertThat(source)
                .contains("public void respond(HttpResponse response)")
                .contains("public void respond(HttpStatus status)")
                .contains("public void respond(HttpStatus status, Object body)")
                .contains("implements HttpExchange");
    }

    @Test
    @DisplayName("exposes what a handler test asserts on: status, body and path params")
    void exposesTheRecordedState() {
        String source = new KernelTestSupportGenerator().generate("com.example").content();

        assertThat(source)
                .contains("public HttpStatus status()")
                .contains("public Object body()")
                .contains("public RecordingHttpExchange withPathParam(String name, String value)")
                .contains("public static RecordingHttpExchange get(String path)")
                .contains("public static RecordingHttpExchange delete(String path)");
    }

    @Test
    @DisplayName("emission is deterministic — byte-identical across runs")
    void emissionIsDeterministic() {
        assertThat(new KernelTestSupportGenerator().generate("com.example").content())
                .isEqualTo(new KernelTestSupportGenerator().generate("com.example").content());
    }
}
