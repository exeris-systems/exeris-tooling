package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.contract.PeerContract;
import eu.exeris.tooling.codegen.core.contract.PeerContractException;
import eu.exeris.tooling.codegen.core.contract.PeerReference.Coordinate;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.transfer.ChecksumFailureException;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("peers — the plugin's declaration of the contracts it consumes")
class PeerContractsTest {

    private static Path contract(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("cap-manifest.json"), "{\"schemaVersion\":2,\"stamp\":{}}");
        return dir;
    }

    private static PeerDeclaration byPath(String name, Path path) {
        return new PeerDeclaration(name, path.toFile(), null);
    }

    @Test
    @DisplayName("no peers reads nothing")
    void none() {
        assertThat(PeerContracts.read(null, Set.of(), c -> null)).isEmpty();
        assertThat(PeerContracts.read(List.of(), Set.of(), c -> null)).isEmpty();
    }

    @Test
    @DisplayName("a peer by path and a peer by coordinate are read, sorted by name")
    void reads(@TempDir Path tmp) throws IOException {
        Path a = contract(tmp.resolve("a"));
        Path b = contract(tmp.resolve("b"));

        List<PeerContract> contracts = PeerContracts.read(
                List.of(new PeerDeclaration("zeta", null, "com.acme:zeta:1"), byPath("alpha", a)),
                Set.of(), c -> b);

        assertThat(contracts).extracting(PeerContract::name).containsExactly("alpha", "zeta");
    }

    @Test
    @DisplayName("a peer with no name, a bad coordinate, or both path and coordinate is refused")
    void refusals(@TempDir Path tmp) throws IOException {
        Path a = contract(tmp.resolve("a"));

        List<PeerDeclaration> unnamed = List.of(new PeerDeclaration(null, a.toFile(), null));
        List<PeerDeclaration> badCoordinate = List.of(new PeerDeclaration("x", null, "a:b"));
        List<PeerDeclaration> both = List.of(new PeerDeclaration("x", a.toFile(), "a:b:1"));

        assertThatThrownBy(() -> PeerContracts.read(unnamed, Set.of(), c -> a))
                .isInstanceOf(PeerContractException.class).hasMessageContaining("<name>");
        assertThatThrownBy(() -> PeerContracts.read(badCoordinate, Set.of(), c -> a))
                .hasMessageContaining("peer 'x'").hasMessageContaining("groupId:artifactId:version");
        assertThatThrownBy(() -> PeerContracts.read(both, Set.of(), c -> a)).hasMessageContaining("exactly one");
        assertThat(new PeerDeclaration().getName()).isNull();
        assertThat(new PeerDeclaration("x", a.toFile(), "a:b:1").getCoordinate()).isEqualTo("a:b:1");
        assertThat(new PeerDeclaration("x", a.toFile(), null).getPath()).isEqualTo(a.toFile());
    }

    @Test
    @DisplayName("GenerateMojo refuses a peer with EXT-PLUG-2006 before it generates anything")
    void generateRefusesPeer(@TempDir Path tmp) {
        List<Object> calls = new ArrayList<>();
        GenerateMojo mojo = new GenerateMojo();
        mojo.metadataDir = tmp.resolve("m").toFile();
        mojo.outputDir = tmp.resolve("o").toFile();
        mojo.project = new MavenProject();
        mojo.pipeline = (m, o, b, e, d) -> calls.add(m);
        mojo.peers = List.of(byPath("billing", tmp.resolve("missing")));

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("EXT-PLUG-2006")
                .hasMessageContaining("peer 'billing'");
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("GenerateMojo reads declared peers, warns EXT-PLUG-2007 on schema skew, then generates")
    void generateReadsPeers(@TempDir Path tmp) throws Exception {
        Path peer = contract(tmp.resolve("peer"));
        Files.writeString(peer.resolve("Order.json"),
                "{\"entityName\":\"Order\",\"packageName\":\"com.b.domain\",\"schemaVersion\":\"0.0.1-old\"}");
        List<Object> calls = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        GenerateMojo mojo = new GenerateMojo();
        mojo.setLog(new SystemStreamLog() {
            @Override
            public void warn(CharSequence content) {
                warnings.add(content.toString());
            }
        });
        mojo.metadataDir = tmp.resolve("m").toFile();
        mojo.outputDir = tmp.resolve("o").toFile();
        mojo.project = new MavenProject();
        mojo.pipeline = (m, o, b, e, d) -> calls.add(m);
        mojo.peers = List.of(byPath("billing", peer));

        mojo.execute();

        assertThat(calls).hasSize(1);
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("EXT-PLUG-2007").contains("Order"));
    }

    @Test
    @DisplayName("GenerateMojo refuses a path inside a module of the session's reactor")
    void generateRefusesPathInReactor(@TempDir Path tmp) throws IOException {
        Path module = Files.createDirectories(tmp.resolve("producer"));
        Path inside = contract(module.resolve("target/classes/META-INF/exeris/contract"));
        MavenProject producer = new MavenProject();
        producer.setFile(module.resolve("pom.xml").toFile());
        MavenProject aggregator = new MavenProject();
        aggregator.setPackaging("pom");
        aggregator.setFile(tmp.resolve("pom.xml").toFile());
        MavenSession session = new MavenSession(null, new DefaultMavenExecutionRequest(),
                new DefaultMavenExecutionResult(), List.of(aggregator, producer));
        GenerateMojo mojo = new GenerateMojo();
        mojo.metadataDir = tmp.resolve("m").toFile();
        mojo.outputDir = tmp.resolve("o").toFile();
        mojo.project = new MavenProject();
        mojo.session = session;
        mojo.pipeline = (m, o, b, e, d) -> { };
        mojo.peers = List.of(byPath("billing", inside));

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoFailureException.class)
                .hasMessageContaining("EXT-PLUG-2006")
                .hasMessageContaining("inside the module");
    }

    private static RepositorySystem system(Artifact[] asked, File answer, ArtifactResolutionException failure) {
        return system(asked, new RepositorySystemSession[1], answer, failure);
    }

    private static RepositorySystem system(Artifact[] asked, RepositorySystemSession[] session, File answer,
                                           ArtifactResolutionException failure) {
        return (RepositorySystem) Proxy.newProxyInstance(RepositorySystem.class.getClassLoader(),
                new Class<?>[] {RepositorySystem.class}, (proxy, method, args) -> {
                    assertThat(method.getName()).isEqualTo("resolveArtifact");
                    session[0] = (RepositorySystemSession) args[0];
                    ArtifactRequest request = (ArtifactRequest) args[1];
                    asked[0] = request.getArtifact();
                    if (failure != null) {
                        throw failure;
                    }
                    return new ArtifactResult(request).setArtifact(request.getArtifact().setFile(answer));
                });
    }

    @Test
    @DisplayName("a coordinate is resolved as the jar of that group, artifact and version")
    void aetherResolves(@TempDir Path tmp) throws IOException {
        Artifact[] asked = new Artifact[1];
        File jar = tmp.resolve("billing.jar").toFile();

        Path resolved = new AetherCoordinateResolver(system(asked, jar, null), null, List.of())
                .resolve(new Coordinate("com.acme", "billing", "1.4.0"));

        assertThat(resolved).isEqualTo(jar.toPath());
        assertThat(asked[0].getGroupId()).isEqualTo("com.acme");
        assertThat(asked[0].getArtifactId()).isEqualTo("billing");
        assertThat(asked[0].getVersion()).isEqualTo("1.4.0");
        assertThat(asked[0].getExtension()).isEqualTo("jar");
    }

    @Test
    @DisplayName("a resolution failure surfaces as an I/O failure that carries the resolver's message")
    void aetherFails() {
        ArtifactResolutionException failure = new ArtifactResolutionException(List.of(), "no such artifact");

        assertThatThrownBy(() -> new AetherCoordinateResolver(system(new Artifact[1], null, failure), null, List.of())
                .resolve(new Coordinate("com.acme", "billing", "1.4.0")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no such artifact");
    }

    @Test
    @DisplayName("every repository fails on a checksum mismatch, whatever its own policy, and keeps its other settings")
    void checksumPolicyFails() {
        RemoteRepository lenient = new RemoteRepository.Builder("central", "default", "https://repo.example/maven2")
                .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER,
                        RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_DAILY,
                        RepositoryPolicy.CHECKSUM_POLICY_WARN))
                .setAuthentication(new AuthenticationBuilder().addUsername("reader").build())
                .setProxy(new org.eclipse.aether.repository.Proxy("http", "proxy.example", 3128))
                .setMirroredRepositories(List.of(new RemoteRepository.Builder("upstream", "default",
                        "https://upstream.example/maven2").build()))
                .build();

        RemoteRepository strict = AetherCoordinateResolver.failOnChecksumMismatch(List.of(lenient)).get(0);

        assertThat(strict.getId()).isEqualTo("central");
        assertThat(strict.getUrl()).isEqualTo("https://repo.example/maven2");
        assertThat(strict.getPolicy(false).getChecksumPolicy()).isEqualTo(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        assertThat(strict.getPolicy(false).isEnabled()).isTrue();
        assertThat(strict.getPolicy(false).getUpdatePolicy()).isEqualTo(RepositoryPolicy.UPDATE_POLICY_NEVER);
        assertThat(strict.getPolicy(true).getChecksumPolicy()).isEqualTo(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        assertThat(strict.getPolicy(true).isEnabled()).isFalse();
        assertThat(strict.getAuthentication()).isEqualTo(lenient.getAuthentication());
        assertThat(strict.getProxy()).isEqualTo(lenient.getProxy());
        assertThat(strict.getMirroredRepositories()).isEqualTo(lenient.getMirroredRepositories());
    }

    @Test
    @DisplayName("the session the request runs in fails on a checksum even when the build's own session warns")
    void sessionFails(@TempDir Path tmp) throws IOException {
        DefaultRepositorySystemSession lax = new DefaultRepositorySystemSession();
        lax.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_WARN);
        RepositorySystemSession[] seen = new RepositorySystemSession[1];

        new AetherCoordinateResolver(system(new Artifact[1], seen, tmp.resolve("b.jar").toFile(), null), lax,
                List.of()).resolve(new Coordinate("com.acme", "billing", "1.4.0"));

        assertThat(seen[0].getChecksumPolicy()).isEqualTo(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        assertThat(lax.getChecksumPolicy()).isEqualTo(RepositoryPolicy.CHECKSUM_POLICY_WARN);
    }

    @Test
    @DisplayName("a repository that serves no checksum is reported as an unverified checksum too")
    void missingChecksumIsNamed() {
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact("com.acme", "billing", "jar", "1.4.0"), List.of(), null);
        ArtifactResult result = new ArtifactResult(request);
        result.addException(new ChecksumFailureException("Checksum validation failed, no checksums available"));
        ArtifactResolutionException failure = new ArtifactResolutionException(List.of(result), "could not resolve");

        assertThatThrownBy(() -> new AetherCoordinateResolver(system(new Artifact[1], null, failure), null,
                List.of()).resolve(new Coordinate("com.acme", "billing", "1.4.0")))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("checksum not verified: ");
    }

    @Test
    @DisplayName("a checksum failure in the resolution result is reported as an unverified checksum naming the peer")
    void checksumMismatchIsNamed() {
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact("com.acme", "billing", "jar", "1.4.0"), List.of(), null);
        ArtifactResult result = new ArtifactResult(request);
        result.addException(new ChecksumFailureException("expected abc, actual def"));
        ArtifactResolutionException failure = new ArtifactResolutionException(List.of(result), "could not resolve");

        List<PeerDeclaration> declared = List.of(new PeerDeclaration("billing", null, "com.acme:billing:1.4.0"));
        AetherCoordinateResolver aether = new AetherCoordinateResolver(system(new Artifact[1], null, failure), null,
                List.of());

        assertThatThrownBy(() -> PeerContracts.read(declared, Set.of(), aether::resolve))
                .isInstanceOf(PeerContractException.class)
                .hasMessageContaining("billing")
                .hasMessageContaining("checksum not verified");
    }
}
