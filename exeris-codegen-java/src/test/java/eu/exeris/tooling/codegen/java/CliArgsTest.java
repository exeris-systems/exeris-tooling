package eu.exeris.tooling.codegen.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link CliArgs#parse(String[])} — the arg-parsing seam extracted from
 * the CLI shell. Each test asserts a property of one branch (all-required-args,
 * missing one, unknown switch ignored, etc.) so a regression in the parser is
 * caught here before it surfaces as a misleading runtime stack trace.
 */
class CliArgsTest {

    @Nested
    @DisplayName("happy paths")
    class HappyPaths {

        @Test
        @DisplayName("all three switches parse into the matching record fields")
        void parsesAllThreeSwitches() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "--metadata-dir=/tmp/meta",
                    "--output-dir=/tmp/out",
                    "--base-package=com.foo.bar"
            });

            assertThat(parsed.metadataDir()).isEqualTo(Path.of("/tmp/meta"));
            assertThat(parsed.outputDir()).isEqualTo(Path.of("/tmp/out"));
            assertThat(parsed.basePackage()).isEqualTo("com.foo.bar");
        }

        @Test
        @DisplayName("--base-package is optional — absent means null (auto-detect)")
        void basePackageOptional() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "--metadata-dir=meta",
                    "--output-dir=out"
            });

            assertThat(parsed.basePackage()).isNull();
            assertThat(parsed.metadataDir()).isEqualTo(Path.of("meta"));
            assertThat(parsed.outputDir()).isEqualTo(Path.of("out"));
        }

        @Test
        @DisplayName("switches accepted in any order")
        void switchOrderIrrelevant() {
            CliArgs first = CliArgs.parse(new String[]{
                    "--metadata-dir=a", "--output-dir=b", "--base-package=p"
            });
            CliArgs second = CliArgs.parse(new String[]{
                    "--base-package=p", "--output-dir=b", "--metadata-dir=a"
            });

            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("unknown --foo=bar switch is silently ignored (forward compat)")
        void unknownSwitchIgnored() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "--metadata-dir=m",
                    "--output-dir=o",
                    "--future-flag=whatever"
            });

            assertThat(parsed.metadataDir()).isEqualTo(Path.of("m"));
            assertThat(parsed.outputDir()).isEqualTo(Path.of("o"));
        }

        @Test
        @DisplayName("positional / non-switch tokens are silently ignored")
        void positionalIgnored() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "leading-positional",
                    "--metadata-dir=m",
                    "--output-dir=o",
                    "trailing-positional"
            });

            assertThat(parsed.metadataDir()).isEqualTo(Path.of("m"));
            assertThat(parsed.outputDir()).isEqualTo(Path.of("o"));
        }

        @Test
        @DisplayName("empty string after = is preserved (path resolves to current dir)")
        void emptyValueAcceptedForOptional() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "--metadata-dir=m",
                    "--output-dir=o",
                    "--base-package="
            });

            assertThat(parsed.basePackage()).isEmpty();
        }
    }

    @Nested
    @DisplayName("required-argument validation")
    class RequiredArgs {

        @Test
        @DisplayName("missing --metadata-dir throws IllegalArgumentException naming the switch")
        void missingMetadataDir() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{"--output-dir=o"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--metadata-dir");
        }

        @Test
        @DisplayName("missing --output-dir throws IllegalArgumentException naming the switch")
        void missingOutputDir() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{"--metadata-dir=m"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--output-dir");
        }

        @Test
        @DisplayName("empty arg array throws — metadata-dir is missing")
        void emptyArgs() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--metadata-dir");
        }

        @Test
        @DisplayName("only unknown switches → still missing required, throws")
        void onlyUnknown() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{"--whatever=x", "--also=y"}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("record contract")
    class RecordContract {

        @Test
        @DisplayName("compact ctor rejects null metadataDir")
        void rejectsNullMetadataDir() {
            assertThatThrownBy(() -> new CliArgs(null, Path.of("o"), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("metadataDir");
        }

        @Test
        @DisplayName("compact ctor rejects null outputDir")
        void rejectsNullOutputDir() {
            assertThatThrownBy(() -> new CliArgs(Path.of("m"), null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("outputDir");
        }

        @Test
        @DisplayName("null basePackage is accepted (means auto-detect)")
        void acceptsNullBasePackage() {
            CliArgs args = new CliArgs(Path.of("m"), Path.of("o"), null);
            assertThat(args.basePackage()).isNull();
        }
    }
}
