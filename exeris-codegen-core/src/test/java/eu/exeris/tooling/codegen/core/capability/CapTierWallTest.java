package eu.exeris.tooling.codegen.core.capability;

import eu.exeris.sdk.sourcemodel.ast.CapabilityModuleMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cap-tier Wall guard tests (ADR-024 predicate 4 / ADR-055).
 *
 * <p>Fixtures are compiled with the real {@code javac} at test time rather than
 * hand-assembled, because the whole point of the guard is what <em>javac chooses to
 * emit</em> — which reference lands in the constant pool versus only in a descriptor or a
 * generic signature. A hand-built class file would let the test assert the extraction the
 * implementation happens to do, instead of the extraction real bytecode demands.
 *
 * <p>The forbidden types are declared as fixture sources in their own packages (a
 * three-line fake {@code org.springframework.context.ApplicationContext}, etc.) so the
 * suite stays hermetic — no Spring, Netty or Reactor on the test classpath.
 */
@DisplayName("CapTierWall")
class CapTierWallTest {

    private static JavaCompiler compiler;

    @BeforeAll
    static void compilerAvailable() {
        compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK (not JRE) is required to compile Wall fixtures").isNotNull();
    }

    /** The fake forbidden/allowed types every fixture set shares. */
    private static final Map<String, String> STUBS = Map.of(
            "org/springframework/context/ApplicationContext.java",
            "package org.springframework.context; public interface ApplicationContext {}",

            "org/springframework/stereotype/Component.java",
            """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Component {}
            """,

            "eu/exeris/kernel/core/internal/BufferPool.java",
            "package eu.exeris.kernel.core.internal; public final class BufferPool {}",

            "eu/exeris/kernel/spi/http/HttpRouter.java",
            "package eu.exeris.kernel.spi.http; public interface HttpRouter {}",

            "eu/exeris/caps/billing/internal/Ledger.java",
            "package eu.exeris.caps.billing.internal; public final class Ledger {}",

            "eu/exeris/caps/billing/api/InvoiceService.java",
            "package eu.exeris.caps.billing.api; public interface InvoiceService {}",

            "eu/exeris/caps/audit/internal/Sink.java",
            "package eu.exeris.caps.audit.internal; public final class Sink {}",

            "eu/exeris/caps/audit/internals/Helper.java",
            "package eu.exeris.caps.audit.internals; public final class Helper {}");

    /**
     * Compiles {@code sources} plus the shared stubs into {@code dir} and scans the result.
     *
     * @param ownCapNames the cap names the build owns (see {@link CapTierWall#ownCapNames})
     */
    private static List<WallViolation> compileAndScan(Path dir,
                                                      Set<String> ownCapNames,
                                                      Map<String, String> sources) throws IOException {
        Path src = dir.resolve("src");
        Path stubClasses = dir.resolve("stubs");
        Path classes = dir.resolve("classes");
        Files.createDirectories(stubClasses);
        Files.createDirectories(classes);

        // The stubs compile to a SEPARATE directory used only as -classpath, mirroring how a
        // real cap sees Spring / the kernel / a sibling cap: as dependency artefacts, never as
        // its own target/classes. Compiling them into the scanned directory would make the
        // fake ApplicationContext cap code and flag its own self-reference.
        compile(stubClasses, null, writeAll(src, STUBS));
        compile(classes, stubClasses, writeAll(src, sources));

        return CapTierWall.scan(classes, ownCapNames);
    }

    private static void compile(Path outputDir, Path classpath, List<String> files) {
        List<String> args = new ArrayList<>(List.of("-d", outputDir.toString(), "-nowarn"));
        if (classpath != null) {
            args.addAll(List.of("-classpath", classpath.toString()));
        }
        args.addAll(files);
        int rc = compiler.run(null, null, null, args.toArray(String[]::new));
        assertThat(rc).as("fixture compilation must succeed").isZero();
    }

    private static List<String> writeAll(Path srcRoot, Map<String, String> sources) throws IOException {
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            files.add(write(srcRoot, e.getKey(), e.getValue()));
        }
        return files;
    }

    private static String write(Path srcRoot, String relativePath, String content) throws IOException {
        Path file = srcRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file.toString();
    }

    @Nested
    @DisplayName("the extraction surface — every place javac can hide a reference")
    class ExtractionSurface {

        @Test
        @DisplayName("a supertype reference is caught (constant-pool ClassEntry)")
        void supertypeReference(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap implements org.springframework.context.ApplicationContext {}
                    """));

            assertThat(found).extracting(WallViolation::forbiddenType)
                    .contains("org.springframework.context.ApplicationContext");
        }

        @Test
        @DisplayName("a method-parameter type is caught even when the body never touches it")
        void parameterTypeOnly(@TempDir Path dir) throws IOException {
            // The regression this guard exists to prevent: a pool-only scan sees NOTHING here,
            // because an unused parameter contributes no ClassEntry — only a descriptor.
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public void configure(org.springframework.context.ApplicationContext ctx) { }
                    }
                    """));

            assertThat(found).extracting(WallViolation::forbiddenType)
                    .containsExactly("org.springframework.context.ApplicationContext");
        }

        @Test
        @DisplayName("a field type is caught even when the field is never dereferenced")
        void fieldTypeOnly(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public org.springframework.context.ApplicationContext ctx;
                    }
                    """));

            assertThat(found).extracting(WallViolation::forbiddenType)
                    .containsExactly("org.springframework.context.ApplicationContext");
        }

        @Test
        @DisplayName("a generic type argument is caught (Signature attribute only)")
        void genericArgumentOnly(@TempDir Path dir) throws IOException {
            // The descriptor here is only Ljava/util/List; — the forbidden type lives
            // exclusively in the generic Signature attribute.
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    import java.util.List;
                    public class Cap {
                        public List<org.springframework.context.ApplicationContext> all;
                    }
                    """));

            assertThat(found).extracting(WallViolation::forbiddenType)
                    .containsExactly("org.springframework.context.ApplicationContext");
        }

        @Test
        @DisplayName("an annotation type is caught")
        void annotationType(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    @org.springframework.stereotype.Component
                    public class Cap { }
                    """));

            assertThat(found).extracting(WallViolation::forbiddenType)
                    .containsExactly("org.springframework.stereotype.Component");
        }

        @Test
        @DisplayName("a return type is caught")
        void returnTypeOnly(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public org.springframework.context.ApplicationContext ctx() { return null; }
                    }
                    """));

            assertThat(found).extracting(WallViolation::forbiddenType)
                    .containsExactly("org.springframework.context.ApplicationContext");
        }
    }

    @Nested
    @DisplayName("the three boundaries")
    class Boundaries {

        @Test
        @DisplayName("a kernel private package is a KERNEL_INTERNAL violation")
        void kernelInternal(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public eu.exeris.kernel.core.internal.BufferPool pool;
                    }
                    """));

            assertThat(found).singleElement().satisfies(v -> {
                assertThat(v.forbiddenType()).isEqualTo("eu.exeris.kernel.core.internal.BufferPool");
                assertThat(v.rule()).isEqualTo(WallViolation.Rule.KERNEL_INTERNAL);
            });
        }

        @Test
        @DisplayName("the kernel SPI surface is allowed")
        void kernelSpiAllowed(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public eu.exeris.kernel.spi.http.HttpRouter router;
                    }
                    """));

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("a SIBLING cap's internals are forbidden; the cap's OWN internals are not")
        void siblingVersusOwnInternals(@TempDir Path dir) throws IOException {
            // This build owns "billing" only. Reading its own internal Ledger is ordinary
            // encapsulation; reaching into audit's internal Sink is the breach.
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public Ledger own;
                        public eu.exeris.caps.audit.internal.Sink sibling;
                    }
                    """));

            assertThat(found).singleElement().satisfies(v -> {
                assertThat(v.forbiddenType()).isEqualTo("eu.exeris.caps.audit.internal.Sink");
                assertThat(v.rule()).isEqualTo(WallViolation.Rule.SIBLING_CAP_INTERNAL);
            });
        }

        @Test
        @DisplayName("a sibling cap's api package is allowed — that is the @Provides surface")
        void siblingApiAllowed(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("audit"), Map.of(
                    "eu/exeris/caps/audit/internal/Cap.java",
                    """
                    package eu.exeris.caps.audit.internal;
                    public class Cap {
                        public eu.exeris.caps.billing.api.InvoiceService invoices;
                    }
                    """));

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("'internals' is not 'internal' — the segment test is exact, not a substring")
        void pluralInternalsIsNotPrivate(@TempDir Path dir) throws IOException {
            List<WallViolation> found = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public eu.exeris.caps.audit.internals.Helper helper;
                    }
                    """));

            assertThat(found).isEmpty();
        }
    }

    @Nested
    @DisplayName("scan contract")
    class ScanContract {

        @Test
        @DisplayName("a missing classes directory yields no violations rather than an error")
        void missingDirectoryIsNotAFailure(@TempDir Path dir) {
            assertThat(CapTierWall.scan(dir.resolve("nope"), Set.of())).isEmpty();
            assertThat(CapTierWall.scan(null, Set.of())).isEmpty();
        }

        @Test
        @DisplayName("countClassFiles separates a vacuous scan from a clean one")
        void countsScannedClasses(@TempDir Path dir) throws IOException {
            // "No violations" has two causes — a clean cap, and a classesDir the compiler never
            // wrote to. The verdict cannot tell them apart; the class count is what can.
            assertThat(CapTierWall.countClassFiles(dir.resolve("nope"))).isZero();
            assertThat(CapTierWall.countClassFiles(null)).isZero();

            List<WallViolation> clean = compileAndScan(dir, Set.of("billing"), Map.of(
                    "eu/exeris/caps/billing/internal/Cap.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Cap {
                        public eu.exeris.caps.billing.api.InvoiceService invoices;
                    }
                    """));

            // Same empty verdict as the two calls above, opposite meaning.
            assertThat(clean).isEmpty();
            assertThat(CapTierWall.countClassFiles(dir.resolve("classes"))).isEqualTo(1);
        }

        @Test
        @DisplayName("violations are deterministically ordered (hard-constraint #3 covers diagnostics)")
        void deterministicOrder(@TempDir Path dir) throws IOException {
            Map<String, String> sources = Map.of(
                    "eu/exeris/caps/billing/internal/Beta.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Beta {
                        public eu.exeris.kernel.core.internal.BufferPool pool;
                        public org.springframework.context.ApplicationContext ctx;
                    }
                    """,
                    "eu/exeris/caps/billing/internal/Alpha.java",
                    """
                    package eu.exeris.caps.billing.internal;
                    public class Alpha {
                        public eu.exeris.caps.audit.internal.Sink sink;
                    }
                    """);

            List<WallViolation> first = compileAndScan(dir, Set.of("billing"), sources);
            List<WallViolation> second = compileAndScan(
                    Files.createTempDirectory(dir, "again"), Set.of("billing"), sources);

            assertThat(first).isEqualTo(second);
            // sorted by violating class, then forbidden type
            assertThat(first).extracting(WallViolation::violatingClass)
                    .startsWith("eu.exeris.caps.billing.internal.Alpha");
            assertThat(first).hasSize(3);
        }
    }

    @Nested
    @DisplayName("ownCapNames")
    class OwnCapNames {

        private static CapabilityModuleDescriptor module(String qualifiedName) {
            return new CapabilityModuleDescriptor("M", "p", qualifiedName,
                    CapabilityModuleMetadata.empty());
        }

        @Test
        @DisplayName("derives the cap name from the @CapabilityModule package")
        void derivesFromModulePackage() {
            assertThat(CapTierWall.ownCapNames(List.of(
                    module("eu.exeris.caps.billing.BillingModule"),
                    module("eu.exeris.caps.audit.internal.AuditModule"))))
                    .containsExactly("billing", "audit");
        }

        @Test
        @DisplayName("a cap outside eu.exeris.caps.* claims no name — so it may read no cap's internals")
        void thirdPartyCapClaimsNothing() {
            // Deliberate: a third-party cap gets no licence over any eu.exeris.caps internal
            // package. It is not a special case, it is the same rule with an empty own-set.
            assertThat(CapTierWall.ownCapNames(List.of(module("com.acme.caps.billing.Module"))))
                    .isEmpty();
        }
    }
}
