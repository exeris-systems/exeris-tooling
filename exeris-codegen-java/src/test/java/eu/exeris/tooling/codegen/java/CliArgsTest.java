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
 * missing one, unknown switch refused, etc.) so a regression in the parser is
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
        @DisplayName("empty string after = is preserved (path resolves to current dir)")
        void emptyValueAcceptedForOptional() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "--metadata-dir=m",
                    "--output-dir=o",
                    "--base-package="
            });

            assertThat(parsed.basePackage()).isEmpty();
        }

        @Test
        @DisplayName("the three switches alone leave tests, allowEmpty and printCatalogue off")
        void flagsDefaultOff() {
            CliArgs parsed = CliArgs.parse(new String[]{"--metadata-dir=m", "--output-dir=o"});

            assertThat(parsed.tests()).isFalse();
            assertThat(parsed.testOutputDir()).isNull();
            assertThat(parsed.allowEmpty()).isFalse();
            assertThat(parsed.printCatalogue()).isFalse();
            assertThat(parsed).isEqualTo(new CliArgs(Path.of("m"), Path.of("o"), null));
        }

        @Test
        @DisplayName("--tests with --test-output-dir, and --allow-empty, parse into their fields")
        void testsAndAllowEmpty() {
            CliArgs parsed = CliArgs.parse(new String[]{
                    "--metadata-dir=m", "--output-dir=o",
                    "--tests", "--test-output-dir=t", "--allow-empty"
            });

            assertThat(parsed.tests()).isTrue();
            assertThat(parsed.testOutputDir()).isEqualTo(Path.of("t"));
            assertThat(parsed.allowEmpty()).isTrue();
        }

        @Test
        @DisplayName("--print-catalogue needs no other switch")
        void printCatalogueAlone() {
            CliArgs parsed = CliArgs.parse(new String[]{"--print-catalogue"});

            assertThat(parsed.printCatalogue()).isTrue();
            assertThat(parsed.metadataDir()).isNull();
            assertThat(parsed.outputDir()).isNull();
        }
    }

    @Nested
    @DisplayName("arguments the parser does not know")
    class UnknownArgs {

        @Test
        @DisplayName("an unknown --switch=value is an error naming the switch")
        void unknownValueSwitch() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{
                    "--metadata-dir=m", "--output-dir=o", "--future-flag=whatever"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown switch: --future-flag");
        }

        @Test
        @DisplayName("an unknown bare --switch is an error, under --print-catalogue too")
        void unknownFlag() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{"--print-catalogue", "--verbose"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown switch: --verbose");
        }

        @Test
        @DisplayName("a positional argument is an error")
        void positional() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{
                    "--metadata-dir=m", "--output-dir=o", "trailing"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unexpected argument: trailing");
        }

        @Test
        @DisplayName("a value switch without =<value> is an error")
        void valueSwitchWithoutValue() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{"--metadata-dir", "m", "--output-dir=o"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--metadata-dir takes a value");
        }

        @Test
        @DisplayName("a flag with =<value> is an error")
        void flagWithValue() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{
                    "--metadata-dir=m", "--output-dir=o", "--allow-empty=true"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("--allow-empty takes no value");
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
        @DisplayName("--tests without --test-output-dir is an error")
        void testsRequireTestOutputDir() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{"--metadata-dir=m", "--output-dir=o", "--tests"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--tests requires --test-output-dir");
        }

        @Test
        @DisplayName("--test-output-dir without --tests is an error, not ignored")
        void testOutputDirRequiresTests() {
            assertThatThrownBy(() -> CliArgs.parse(new String[]{
                    "--metadata-dir=m", "--output-dir=o", "--test-output-dir=t"}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("--test-output-dir is read only with --tests");
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
        @DisplayName("compact ctor accepts null directories under printCatalogue")
        void printCatalogueTakesNoDirectories() {
            CliArgs args = new CliArgs(null, null, null, false, null, false, true);
            assertThat(args.printCatalogue()).isTrue();
        }

        @Test
        @DisplayName("compact ctor rejects tests without testOutputDir, and testOutputDir without tests")
        void testsAndTestOutputDirGoTogether() {
            assertThatThrownBy(() -> new CliArgs(Path.of("m"), Path.of("o"), null, true, null, false, false))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("testOutputDir");
            assertThatThrownBy(() -> new CliArgs(Path.of("m"), Path.of("o"), null, false, Path.of("t"), false, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null basePackage is accepted (means auto-detect)")
        void acceptsNullBasePackage() {
            CliArgs args = new CliArgs(Path.of("m"), Path.of("o"), null);
            assertThat(args.basePackage()).isNull();
        }
    }
}
