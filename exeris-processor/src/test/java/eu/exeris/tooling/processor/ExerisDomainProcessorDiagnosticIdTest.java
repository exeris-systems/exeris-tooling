package eu.exeris.tooling.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import eu.exeris.tooling.diagnostics.DiagnosticId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every diagnostic the processor prints carries a registered {@link DiagnosticId} in its fixed
 * position.
 *
 * <p>The guarantee is structural first: {@code note}, {@code warning}, {@code error} and
 * {@code reportProcessingFailure} each take a required {@code DiagnosticId}, and they all reach
 * {@code Messager} through one private method, so a call site without an identifier does not
 * compile. {@link #messagerIsCalledFromOneMethodOnly()} keeps it that way — a direct
 * {@code printMessage} added elsewhere would bypass the parameter. The compilations below check
 * the printed shape end to end, across a NOTE, every WARNING group and an ERROR.
 */
@DisplayName("ExerisDomainProcessor — every diagnostic carries a stable identifier")
class ExerisDomainProcessorDiagnosticIdTest {

    private static final Pattern PRINTED =
            Pattern.compile("^\\[Exeris] (EXT-PROC-\\d{4}): \\S");

    private static final Set<String> REGISTERED = Arrays.stream(DiagnosticId.values())
            .map(DiagnosticId::code)
            .collect(Collectors.toSet());

    @Test
    @DisplayName("NOTE, ordinary WARNING and -Aexeris.strict WARNING all carry a registered EXT-PROC id")
    void warningsAndNotesCarryIds() {
        Compilation compilation = javac()
                .withOptions("-Aexeris.verbose=true", "-Aexeris.strict=true")
                .withProcessors(new ExerisDomainProcessor())
                .compile(JavaFileObjects.forSourceString("com.example.Item", """
                        package com.example;

                        import eu.exeris.sdk.annotation.Action;
                        import eu.exeris.sdk.annotation.ExerisDomain;
                        import eu.exeris.sdk.annotation.RouteAccess;

                        @ExerisDomain(module = "catalog", path = "/items", tenantScoped = true,
                                apiVersion = "v2")
                        @RouteAccess(RouteAccess.Level.AUTHENTICATED)
                        public class Item {
                            private String name;

                            @Action(name = "approve", label = "Approve", path = "/approve")
                            public void approve() {
                            }
                        }
                        """));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        Set<String> codes = codesOf(compilation);
        assertThat(codes).contains(
                DiagnosticId.VERBOSE_PROGRESS.code(),
                DiagnosticId.TENANT_SCOPED_DEPRECATED.code(),
                DiagnosticId.STRICT_INERT_ATTRIBUTE.code(),
                DiagnosticId.STRICT_INERT_ANNOTATION.code());
    }

    @Test
    @DisplayName("an ERROR carries a registered EXT-PROC id")
    void errorsCarryIds() {
        Compilation compilation = javac()
                .withProcessors(new ExerisDomainProcessor())
                .compile(JavaFileObjects.forSourceString("com.example.Shape", """
                        package com.example;

                        import eu.exeris.sdk.annotation.ExerisDomain;

                        @ExerisDomain(module = "geo", path = "/shapes")
                        public interface Shape {
                        }
                        """));

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(codesOf(compilation)).contains(DiagnosticId.ANNOTATION_ON_WRONG_ELEMENT.code());
        assertThat(compilation.errors()).extracting(d -> d.getMessage(null))
                .contains("[Exeris] EXT-PROC-1001: @ExerisDomain can only be applied to classes");
    }

    @Test
    @DisplayName("Messager is called from one method only, so the required id cannot be bypassed")
    void messagerIsCalledFromOneMethodOnly() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/eu/exeris/tooling/processor/ExerisDomainProcessor.java"),
                StandardCharsets.UTF_8);
        int print = source.indexOf("private void print(Diagnostic.Kind kind, DiagnosticId id,");
        assertThat(print).as("the single printing method").isPositive();
        int printEnd = source.indexOf("\n    }\n", print);

        List<Integer> calls = Pattern.compile("messager\\.printMessage\\(").matcher(source)
                .results().map(r -> r.start()).toList();
        assertThat(calls).isNotEmpty()
                .allSatisfy(at -> assertThat(at).isBetween(print, printEnd));
    }

    /**
     * Every {@code [Exeris]} diagnostic of the compilation, asserted to match the printed shape
     * with a registered processor identifier; returns the identifiers seen.
     */
    private static Set<String> codesOf(Compilation compilation) {
        List<String> exeris = compilation.diagnostics().stream()
                .map(d -> d.getMessage(null))
                .filter(m -> m != null && m.contains("[Exeris]"))
                .toList();
        assertThat(exeris).isNotEmpty();
        return exeris.stream().map(message -> {
            Matcher m = PRINTED.matcher(message);
            assertThat(m.find()).as("printed shape of: " + message).isTrue();
            assertThat(REGISTERED).as("registered: " + m.group(1)).contains(m.group(1));
            return m.group(1);
        }).collect(Collectors.toSet());
    }
}
