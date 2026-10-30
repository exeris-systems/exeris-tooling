package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.DatabaseType;
import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTopology;
import eu.exeris.tooling.initializr.model.ProjectTree;
import eu.exeris.tooling.initializr.model.ScaffoldFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectGeneratorTest {

    private static final ScaffoldVersions VERSIONS = new ScaffoldVersions("9.8.7", "2.3.232", "42.7.13", "5.11.4",
            "3.27.7");

    private static ProjectTree generate(ProjectTopology topology, DatabaseType database) {
        return ProjectGenerator.generate(new ProjectScaffoldRequest("com.example", "order-service", null,
                "Orders & Co", topology, database, "OrderLine"), VERSIONS);
    }

    private static String text(ProjectTree tree, String path) {
        return tree.find(path).map(ScaffoldFile::contentAsString)
                .orElseThrow(() -> new AssertionError("no " + path + " in " + tree.paths()));
    }

    @Test
    @DisplayName("backend-only writes a Maven project with one entity and the two-pass build script")
    void backendOnlyFiles() {
        ProjectTree tree = generate(ProjectTopology.BACKEND_ONLY, DatabaseType.H2);
        assertThat(tree.paths()).containsExactly(
                ".gitignore",
                ".gitattributes",
                "README.md",
                "pom.xml",
                "first-build.sh",
                "src/main/java/com/example/order/service/domain/OrderLine.java");
        assertThat(tree.find("first-build.sh")).get().extracting(ScaffoldFile::executable).isEqualTo(true);
    }

    @Test
    @DisplayName(".gitattributes pins every tree the generators write to LF line endings")
    void gitattributesPinGeneratedTreesToLf() {
        assertThat(text(generate(ProjectTopology.BACKEND_ONLY, DatabaseType.H2), ".gitattributes"))
                .contains("src/main/generated/** text eol=lf\n", "src/test/generated/** text eol=lf\n")
                .doesNotContain("backend/", "frontend/", "{{");
        assertThat(text(generate(ProjectTopology.E2E, DatabaseType.POSTGRESQL), ".gitattributes"))
                .contains("backend/src/main/generated/** text eol=lf\n",
                        "backend/src/test/generated/** text eol=lf\n", "frontend/app/** text eol=lf\n")
                .doesNotContain("{{");
    }

    @Test
    @DisplayName("the backend POM is the parent, the starter, a JDBC driver and the generated tests' libraries")
    void backendPom() {
        String pom = text(generate(ProjectTopology.BACKEND_ONLY, DatabaseType.H2), "pom.xml");
        assertThat(pom)
                .contains("""
                        <artifactId>exeris-app-parent</artifactId>
                                <version>9.8.7</version>""")
                .contains("""
                        <groupId>com.example</groupId>
                            <artifactId>order-service</artifactId>""")
                .contains("<description>Orders &amp; Co</description>")
                .contains("""
                        <artifactId>exeris-app-starter</artifactId>
                                    <type>pom</type>""")
                .contains("""
                        <groupId>com.h2database</groupId>
                                    <artifactId>h2</artifactId>
                                    <version>2.3.232</version>
                                    <scope>runtime</scope>""")
                .contains("""
                        <artifactId>junit-jupiter</artifactId>
                                    <version>5.11.4</version>""")
                .contains("""
                        <artifactId>assertj-core</artifactId>
                                    <version>3.27.7</version>""")
                .contains("<exeris.tests>true</exeris.tests>")
                .contains("<url/>", "<license/>", "<developer/>", "<developerConnection/>");
        assertThat(pom.split("<dependency>", -1)).hasSize(5);
        assertThat(pom).doesNotContain("exeris-caps", "<plugin>", "SNAPSHOT</version>\n        <relativePath/>");
    }

    @Test
    @DisplayName("PostgreSQL declares its driver and adds the compose file the README's values connect to")
    void postgresql() {
        ProjectTree tree = generate(ProjectTopology.BACKEND_ONLY, DatabaseType.POSTGRESQL);
        assertThat(text(tree, "pom.xml")).contains("""
                <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <version>42.7.13</version>""");
        assertThat(text(tree, "docker-compose.yml")).contains("POSTGRES_DB: order_service",
                "POSTGRES_USER: exeris", "POSTGRES_PASSWORD: exeris");
        assertThat(text(tree, "README.md")).contains(
                "| `exeris.http.port` | `8080` |",
                "| `exeris.persistence.jdbcUrl` | `jdbc:postgresql://localhost:5432/order_service` |",
                "| `exeris.persistence.username` | `exeris` |",
                "| `exeris.persistence.runMigrations` | `true` |",
                """
                docker compose up -d --wait
                for migration in src/main/generated/java/db/migration/V*.sql; do
                  docker compose exec -T postgres psql -q -U exeris -d order_service""",
                "-Dexeris.persistence.username=exeris -Dexeris.persistence.password=exeris",
                "-cp \"target/classes:$(cat target/classpath.txt)\" com.example.order.service.Application")
                .doesNotContain("H2 builds", "\n\n\n", "{{");
    }

    @Test
    @DisplayName("the entity is an @ExerisDomain in <package>.domain, served at its kebab-case plural")
    void entity() {
        String entity = text(generate(ProjectTopology.BACKEND_ONLY, DatabaseType.H2),
                "src/main/java/com/example/order/service/domain/OrderLine.java");
        assertThat(entity)
                .startsWith("package com.example.order.service.domain;\n")
                .contains("@ExerisDomain(module = \"order-lines\", path = \"/order-lines\")")
                .contains("public class OrderLine {")
                .contains("private UUID id;")
                .doesNotContain("@CapabilityModule", "@View", "@UI");
    }

    @Test
    @DisplayName("the build script runs the two passes the starter's fixture runs, without clean")
    void firstBuildScript() {
        String script = text(generate(ProjectTopology.BACKEND_ONLY, DatabaseType.H2), "first-build.sh");
        assertThat(script).startsWith("#!/bin/sh\n")
                .contains("""

                mvn -Dexeris.codegen.skip=true compile "$@"
                mvn verify "$@"
                """)
                .doesNotContain("mvn clean");
    }

    @Test
    @DisplayName("the README documents the two passes, and that H2 builds and tests but runs no migration")
    void readmeH2() {
        String readme = text(generate(ProjectTopology.BACKEND_ONLY, DatabaseType.H2), "README.md");
        assertThat(readme)
                .startsWith("# Orders & Co\n")
                .contains("sh first-build.sh", "mvn -Dexeris.codegen.skip=true compile", "then `mvn verify`")
                .contains("the H2 JDBC driver", "H2 builds the project and runs its generated tests",
                        "--database=postgresql", "com.example.order.service.Application")
                .doesNotContain("exeris.datasource", "exeris.application", "| System property |", "{{", "}}",
                        "\n\n\n");
    }

    @Test
    @DisplayName("e2e writes the backend under backend/ and, under frontend/, only what runs exeris-gen")
    void e2eFiles() {
        ProjectTree tree = generate(ProjectTopology.E2E, DatabaseType.POSTGRESQL);
        assertThat(tree.paths()).containsExactly(
                ".gitignore",
                ".gitattributes",
                "README.md",
                "backend/pom.xml",
                "backend/first-build.sh",
                "backend/src/main/java/com/example/order/service/domain/OrderLine.java",
                "frontend/package.json",
                "docker-compose.yml");
    }

    @Test
    @DisplayName("the e2e frontend runs the TypeScript emitter on the backend build's metadata, into app/")
    void e2eFrontend() {
        ProjectTree tree = generate(ProjectTopology.E2E, DatabaseType.POSTGRESQL);
        String packageJson = text(tree, "frontend/package.json");
        assertThat(packageJson)
                .contains("\"name\": \"order-service-frontend\"")
                .contains("\"generate\": \"exeris-gen generate --input ../backend/target/classes/exeris-metadata"
                        + " --output app --app-name order-service\"")
                .contains("\"@exeris/codegen-ts\": \"9.8.7\"")
                .doesNotContain("@angular", "vite", "\"start\"");
        // exeris-gen writes its own package.json, angular.json and src/ into app/; nothing here may
        // sit at a path it writes.
        assertThat(tree.paths()).noneMatch(path -> path.startsWith("frontend/app/") || path.startsWith("frontend/src/"));
        assertThat(text(tree, "README.md")).contains("""
                cd backend
                sh first-build.sh""", "npm run generate",
                "| `exeris.http.port` | `8443` |", "`localhost:8443`", "npm install <path>/exeris-codegen-ts",
                "for migration in backend/src/main/generated/java/db/migration/V*.sql; do",
                """
                done
                cd backend
                mvn dependency:build-classpath""")
                .doesNotContain("\n\n\n", "{{");
    }

    @ParameterizedTest
    @EnumSource(ProjectTopology.class)
    @DisplayName("the same request gives the same tree, byte for byte")
    void deterministic(ProjectTopology topology) {
        assertThat(generate(topology, DatabaseType.POSTGRESQL)).isEqualTo(generate(topology, DatabaseType.POSTGRESQL));
    }
}
