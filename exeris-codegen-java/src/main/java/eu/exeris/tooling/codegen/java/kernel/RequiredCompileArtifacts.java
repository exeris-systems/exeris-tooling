package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Derives the artefacts the emitted Java imports from outside the JDK, so a build can name the one
 * its classpath lacks before {@code javac} reports a missing package instead.
 *
 * <p>Tooling emits no {@code pom.xml}, so every such import is a requirement on the consumer's
 * build that no dependency declaration carries. Each entry below is tied to the predicate under
 * which an emitter writes the import, and to nothing else — the same rule {@code RequiredDrivers}
 * keeps for the runtime half:
 *
 * <ul>
 *   <li>{@code eu.exeris:exeris-kernel-spi} and {@code eu.exeris:exeris-kernel-core} — whenever an
 *       application is emitted, which is whenever there is a domain: every handler and repository
 *       imports the SPI, and {@code Application} imports {@code KernelBootstrap},
 *       {@code HttpRouter} and {@code TransactionOrchestrator} from Core.</li>
 *   <li>{@code eu.exeris:exeris-sdk-composition-runtime} — when the build is composed (at least one
 *       {@code @CapabilityModule}): {@code Application} imports {@code CompositionConductor}.</li>
 *   <li>{@code tools.jackson.core:jackson-databind} — when some entity has a {@code List<X>} field:
 *       its repository imports Jackson 3 ({@code KernelRepositoryGenerator.importsJackson}).</li>
 *   <li>{@code org.junit.jupiter:junit-jupiter-api} and {@code org.assertj:assertj-core} — for the
 *       generated tests, the only two libraries ADR-058 lets them import.</li>
 * </ul>
 *
 * <p>The emitted {@code Application} Javadoc names the same main-source artefacts under the same
 * predicates; a new import in an emitter adds its entry here and its sentence there.
 *
 * @since 0.10
 */
public final class RequiredCompileArtifacts {

    /** The classpath an artefact must be on: the one that compiles main sources, or tests. */
    public enum Classpath {
        /** The compile classpath: compile, provided and system scope. */
        MAIN,
        /** The test classpath, which adds runtime and test scope to {@link #MAIN}. */
        TEST
    }

    /**
     * One artefact the emitted code imports.
     *
     * @param groupId    the Maven group id
     * @param artifactId the Maven artifact id
     * @param classpath  the classpath the importing code is compiled against
     * @param importedBy which emitted code imports it, for the message that names it
     */
    public record Requirement(String groupId, String artifactId, Classpath classpath, String importedBy) {

        /**
         * Validates that every component is present.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public Requirement {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(classpath, "classpath");
            Objects.requireNonNull(importedBy, "importedBy");
        }

        /**
         * Returns the artefact's coordinate without a version.
         *
         * @return {@code groupId:artifactId}
         */
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    private static final String EXERIS = "eu.exeris";

    private RequiredCompileArtifacts() {
    }

    /**
     * Returns the artefacts the generated main sources import.
     *
     * @param domains  every entity this build emits code for
     * @param composed whether the build carries capability metadata, which makes {@code Application}
     *                 import the boot conductor
     * @return the requirements in a fixed order; empty when {@code domains} is empty, because no
     *         Java source is emitted without a domain
     */
    public static List<Requirement> forMainSources(List<DomainMetadata> domains, boolean composed) {
        List<Requirement> required = new ArrayList<>(5);
        if (domains == null || domains.isEmpty()) {
            return required;
        }
        required.add(new Requirement(EXERIS, "exeris-kernel-spi", Classpath.MAIN,
                "every generated handler and repository"));
        required.add(new Requirement(EXERIS, "exeris-kernel-core", Classpath.MAIN,
                "the generated Application"));
        if (composed) {
            required.add(new Requirement(EXERIS, "exeris-sdk-composition-runtime", Classpath.MAIN,
                    "the generated Application, for its CompositionConductor (the build has a"
                            + " @CapabilityModule)"));
        }
        if (domains.stream().anyMatch(KernelRepositoryGenerator::importsJackson)) {
            required.add(new Requirement("tools.jackson.core", "jackson-databind", Classpath.MAIN,
                    "the generated repository of an entity with a List<X> field"));
        }
        return required;
    }

    /**
     * Returns the artefacts the generated tests import beyond the main sources' own.
     *
     * @param domains every entity this build emits tests for
     * @return JUnit 5's API and AssertJ; empty when {@code domains} is empty, because no test is
     *         emitted without a domain
     */
    public static List<Requirement> forGeneratedTests(List<DomainMetadata> domains) {
        if (domains == null || domains.isEmpty()) {
            return List.of();
        }
        return List.of(
                new Requirement("org.junit.jupiter", "junit-jupiter-api", Classpath.TEST,
                        "every generated test"),
                new Requirement("org.assertj", "assertj-core", Classpath.TEST,
                        "every generated test"));
    }
}
