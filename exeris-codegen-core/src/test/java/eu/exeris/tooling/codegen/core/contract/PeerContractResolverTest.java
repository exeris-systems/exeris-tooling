package eu.exeris.tooling.codegen.core.contract;

import eu.exeris.tooling.codegen.core.contract.PeerReference.Coordinate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PeerContractResolver and PeerReference")
class PeerContractResolverTest {

    private static Path contract(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("cap-manifest.json"), "{\"schemaVersion\":2,\"stamp\":{}}");
        return dir;
    }

    private static PeerContractResolver resolver(PeerContractResolver.CoordinateResolver coordinates,
                                                 Path... reactorRoots) {
        return new PeerContractResolver(new PeerContractReader("1"), coordinates, Set.of(reactorRoots));
    }

    @Test
    @DisplayName("reads peers by path and by coordinate, sorted by declared name")
    void resolvesBoth(@TempDir Path tmp) throws IOException {
        Path byPath = contract(tmp.resolve("a"));
        Path byCoordinate = contract(tmp.resolve("b"));
        List<Coordinate> asked = new ArrayList<>();

        List<PeerContract> contracts = resolver(c -> {
            asked.add(c);
            return byCoordinate;
        }).resolve(List.of(
                new PeerReference("zeta", null, Coordinate.parse("zeta", "com.acme:zeta-service:2.0.0")),
                new PeerReference("alpha", byPath, null)));

        assertThat(contracts).extracting(PeerContract::name).containsExactly("alpha", "zeta");
        assertThat(asked).containsExactly(new Coordinate("com.acme", "zeta-service", "2.0.0"));
    }

    @Test
    @DisplayName("a path inside a module of the current build is refused: it gives Maven no build order")
    void refusesPathInsideReactor(@TempDir Path tmp) throws IOException {
        Path module = Files.createDirectories(tmp.resolve("producer"));
        Path inside = contract(module.resolve("target/classes/META-INF/exeris/contract"));

        PeerContractResolver insideResolver = resolver(c -> inside, module);
        List<PeerReference> declared = List.of(new PeerReference("billing", inside, null));

        assertThatThrownBy(() -> insideResolver.resolve(declared))
                .isInstanceOf(PeerContractException.class)
                .hasMessageContaining("peer 'billing'")
                .hasMessageContaining("inside the module")
                .hasMessageContaining("<dependencies>");
    }

    @Test
    @DisplayName("a path outside every module of the current build is read")
    void acceptsPathOutsideReactor(@TempDir Path tmp) throws IOException {
        Path module = Files.createDirectories(tmp.resolve("consumer"));
        Path outside = contract(tmp.resolve("elsewhere"));

        assertThat(resolver(c -> outside, module).resolve(List.of(new PeerReference("billing", outside, null))))
                .hasSize(1);
    }

    @Test
    @DisplayName("a coordinate the resolver cannot resolve is refused with the peer's name")
    void refusesUnresolvableCoordinate() {
        PeerContractResolver failing = resolver(c -> {
            throw new IOException("not found in central");
        });
        List<PeerReference> declared = List.of(new PeerReference("billing", null, Coordinate.parse("billing", "a:b:1")));

        assertThatThrownBy(() -> failing.resolve(declared))
                .isInstanceOf(PeerContractException.class)
                .hasMessageContaining("peer 'billing'")
                .hasMessageContaining("a:b:1")
                .hasMessageContaining("not found in central");
    }

    @Test
    @DisplayName("a name declared twice is refused")
    void refusesDuplicateName(@TempDir Path tmp) throws IOException {
        Path one = contract(tmp.resolve("one"));

        assertThatThrownBy(() -> resolver(c -> one).resolve(List.of(
                new PeerReference("billing", one, null), new PeerReference("billing", one, null))))
                .hasMessageContaining("peer 'billing'").hasMessageContaining("more than once");
    }

    @Test
    @DisplayName("a reference needs a usable name and exactly one of path and coordinate")
    void referenceValidation() {
        Path any = Path.of("x");
        Coordinate coordinate = new Coordinate("g", "a", "1");

        assertThatThrownBy(() -> new PeerReference("1bad", any, null)).hasMessageContaining("name is not usable");
        assertThatThrownBy(() -> new PeerReference("a-", any, null)).hasMessageContaining("name is not usable");
        assertThatThrownBy(() -> new PeerReference("a--b", any, null)).hasMessageContaining("name is not usable");
        assertThat(new PeerReference("a-b-1", any, null).name()).isEqualTo("a-b-1");
        String huge = "a-".repeat(200_000) + "_";
        assertThatThrownBy(() -> new PeerReference(huge, any, null))
                .isInstanceOf(PeerContractException.class).hasMessageContaining("name is not usable");
        assertThatThrownBy(() -> new PeerReference("a_b", any, null)).hasMessageContaining("name is not usable");
        assertThatThrownBy(() -> new PeerReference("ok", null, null)).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> new PeerReference("ok", any, coordinate)).hasMessageContaining("exactly one");
        assertThat(new PeerReference("order-svc2", any, null).name()).isEqualTo("order-svc2");
    }

    @Test
    @DisplayName("a coordinate has exactly three non-blank parts")
    void coordinateParsing() {
        assertThat(Coordinate.parse("p", " g:a:1.0 ")).hasToString("g:a:1.0");
        for (String bad : List.of("g:a", "g:a:1:jar", "g::1", "")) {
            assertThatThrownBy(() -> Coordinate.parse("billing", bad))
                    .isInstanceOf(PeerContractException.class)
                    .hasMessageContaining("peer 'billing'")
                    .hasMessageContaining("groupId:artifactId:version");
        }
    }

    @Test
    @DisplayName("layout predicates separate entity, enum and reserved names")
    void layout() {
        assertThat(ContractLayout.isEntityCandidate("Order.json")).isTrue();
        assertThat(List.of("enum_A.json", "view_A.json", "capability_A.json", "events.json", "cap-manifest.json",
                "Order.txt")).noneMatch(ContractLayout::isEntityCandidate);
        assertThat(ContractLayout.isEnum("enum_A.json")).isTrue();
        assertThat(ContractLayout.isEnum("enum_A.txt")).isFalse();
    }
}
