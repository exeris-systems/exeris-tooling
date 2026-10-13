package eu.exeris.tooling.initializr.engine;

import eu.exeris.tooling.initializr.model.ProjectScaffoldRequest;
import eu.exeris.tooling.initializr.model.ProjectTopology;
import eu.exeris.tooling.initializr.model.ProjectTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ZipArchiveGeneratorTest {

    @Test
    @DisplayName("Compresses ProjectTree to valid ZIP archive")
    void compressesToZip() throws IOException {
        ProjectScaffoldRequest request = ProjectScaffoldRequest.builder()
                .name("Demo Zip App")
                .slug("demo-zip-app")
                .topology(ProjectTopology.E2E)
                .build();

        ProjectTree tree = ProjectGenerator.generate(request);
        byte[] zipBytes = ZipArchiveGenerator.toZipBytes(tree, "demo-zip-app");

        assertThat(zipBytes).isNotEmpty();

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.add(entry.getName());
                zis.closeEntry();
            }
        }

        assertThat(entries).contains(
                "demo-zip-app/README.md",
                "demo-zip-app/backend/pom.xml",
                "demo-zip-app/frontend/package.json"
        );
    }
}
