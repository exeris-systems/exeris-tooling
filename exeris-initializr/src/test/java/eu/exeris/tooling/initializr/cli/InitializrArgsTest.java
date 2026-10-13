package eu.exeris.tooling.initializr.cli;

import eu.exeris.tooling.initializr.model.DatabaseType;
import eu.exeris.tooling.initializr.model.ProjectTopology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitializrArgsTest {

    @Test
    @DisplayName("with no argument, writes a backend-only PostgreSQL project com.example:demo into ./demo")
    void defaults() {
        InitializrArgs args = InitializrArgs.parse(new String[0]);
        assertThat(args.action()).isEqualTo(InitializrArgs.Action.GENERATE);
        assertThat(args.request().topology()).isEqualTo(ProjectTopology.BACKEND_ONLY);
        assertThat(args.request().database()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(args.request().groupId()).isEqualTo("com.example");
        assertThat(args.request().artifactId()).isEqualTo("demo");
        assertThat(args.request().packageName()).isEqualTo("com.example.demo");
        assertThat(args.outputDir()).isEqualTo(Path.of("demo"));
        assertThat(args.zip()).isNull();
        assertThat(args.force()).isFalse();
    }

    @Test
    @DisplayName("reads every value switch and flag")
    void everySwitch() {
        InitializrArgs args = InitializrArgs.parse(new String[] {
            "--topology=e2e", "--group-id=org.acme", "--artifact-id=shop", "--package=org.acme.shop.app",
            "--name=The Shop", "--database=h2", "--entity=Order", "--output-dir=out/shop", "--force"});
        assertThat(args.request().topology()).isEqualTo(ProjectTopology.E2E);
        assertThat(args.request().groupId()).isEqualTo("org.acme");
        assertThat(args.request().artifactId()).isEqualTo("shop");
        assertThat(args.request().packageName()).isEqualTo("org.acme.shop.app");
        assertThat(args.request().name()).isEqualTo("The Shop");
        assertThat(args.request().database()).isEqualTo(DatabaseType.H2);
        assertThat(args.request().entityName()).isEqualTo("Order");
        assertThat(args.outputDir()).isEqualTo(Path.of("out/shop"));
        assertThat(args.force()).isTrue();
    }

    @Test
    @DisplayName("--zip writes an archive instead of a directory")
    void zip() {
        InitializrArgs args = InitializrArgs.parse(new String[] {"--zip=demo.zip"});
        assertThat(args.zip()).isEqualTo(Path.of("demo.zip"));
        assertThat(args.outputDir()).isNull();
    }

    @Test
    @DisplayName("--help and --version take precedence over generation, --help first")
    void helpAndVersion() {
        assertThat(InitializrArgs.parse(new String[] {"--version"}).action()).isEqualTo(InitializrArgs.Action.VERSION);
        assertThat(InitializrArgs.parse(new String[] {"--version", "--help"}).action())
                .isEqualTo(InitializrArgs.Action.HELP);
        assertThat(InitializrArgs.parse(new String[] {"--artifact-id=x", "--help"}).request()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "--capabilities=audit-trail          | Unknown switch: --capabilities",
        "-h                                  | Unexpected argument: -h",
        "demo                                | Unexpected argument: demo",
        "--artifact-id                       | --artifact-id takes a value",
        "--artifact-id=                      | --artifact-id takes a value",
        "--force=true                        | --force takes no value",
        "--topology=E2E                      | --topology is one of backend-only, e2e: E2E",
        "--topology=frontend-only            | --topology is one of backend-only, e2e: frontend-only",
        "--database=mysql                    | --database is one of postgresql, h2: mysql",
        "--entity=item                       | entity must be",
        "--package=com.example.class         | package is not a Java package name",
        "--group-id=com example              | groupId may hold only"
    })
    @DisplayName("refuses an argument it cannot use, naming it")
    void refuses(String arg, String message) {
        assertThatThrownBy(() -> InitializrArgs.parse(new String[] {arg}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    @Test
    @DisplayName("refuses a switch given twice, and --output-dir with --zip")
    void refusesCombinations() {
        assertThatThrownBy(() -> InitializrArgs.parse(new String[] {"--force", "--force"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");
        assertThatThrownBy(() -> InitializrArgs.parse(new String[] {"--output-dir=a", "--zip=b.zip"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alternatives");
    }
}
