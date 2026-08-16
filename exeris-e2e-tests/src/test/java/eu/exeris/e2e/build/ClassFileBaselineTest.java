package eu.exeris.e2e.build;

import eu.exeris.tooling.codegen.core.capability.CapTierWall;
import eu.exeris.tooling.codegen.java.CodegenPipeline;
import eu.exeris.tooling.processor.ExerisDomainProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts what this reactor actually ships, read off the class files rather than off
 * {@code maven.compiler.release}.
 *
 * <p>The property is a build input; the major version and the preview stamp are what a
 * consumer's JVM refuses or accepts. They can disagree — a module overriding the release,
 * or a preview flag reintroduced on one execution, moves the bytes without moving the
 * property. Kernel ADR-066 and SDK ADR-069 both gate on the bytes for the same reason.
 */
@DisplayName("class-file baseline")
class ClassFileBaselineTest {

    /** Java 25 LTS. Raising this is a deliberate, consumer-visible decision — see the root POM. */
    private static final int EXPECTED_MAJOR = 69;

    /** javac stamps preview-compiled classes with this minor; such a class loads on no other JDK. */
    private static final int PREVIEW_MINOR = 0xFFFF;

    private record ClassFile(int major, int minor) {}

    @ParameterizedTest(name = "{0} is major 69, unstamped")
    @ValueSource(strings = {
            "eu.exeris.tooling.processor.ExerisDomainProcessor",
            "eu.exeris.tooling.codegen.core.capability.CapTierWall",
            "eu.exeris.tooling.codegen.java.CodegenPipeline",
    })
    @DisplayName("every published module compiles to the LTS baseline")
    void publishedModulesAreOnTheBaseline(String className) throws Exception {
        ClassFile cf = read(Class.forName(className));

        assertThat(cf.major())
                .as("%s class-file major — a higher value locks out JDK %d consumers",
                        className, EXPECTED_MAJOR - 44)
                .isEqualTo(EXPECTED_MAJOR);
        assertThat(cf.minor())
                .as("%s preview stamp — a stamped class loads on exactly one JDK, "
                        + "and only with --enable-preview", className)
                .isNotEqualTo(PREVIEW_MINOR);
    }

    @Test
    @DisplayName("the three sampled modules are the ones a consumer resolves")
    void theSampleCoversWhatShips() {
        // Guards the test itself: these are compile-time references, so a module that is
        // renamed or dropped breaks this file rather than silently shrinking its coverage.
        assertThat(ExerisDomainProcessor.class.getName()).endsWith("ExerisDomainProcessor");
        assertThat(CapTierWall.class.getName()).endsWith("CapTierWall");
        assertThat(CodegenPipeline.class.getName()).endsWith("CodegenPipeline");
    }

    private static ClassFile read(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource);
             DataInputStream data = new DataInputStream(in)) {
            int magic = data.readInt();
            assertThat(magic).as("class-file magic for %s", type.getName()).isEqualTo(0xCAFEBABE);
            int minor = data.readUnsignedShort();
            int major = data.readUnsignedShort();
            return new ClassFile(major, minor);
        }
    }
}
