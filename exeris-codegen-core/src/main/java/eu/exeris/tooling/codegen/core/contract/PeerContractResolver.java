package eu.exeris.tooling.codegen.core.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the peers a consumer declares into the contracts they lead to (ADR-048 Amendment 3,
 * sections 7, 8 and 10).
 *
 * <p>A coordinate is handed to a {@link CoordinateResolver}; the Maven plugin supplies one over the
 * Maven resolver, so this module stays free of it. A path that lies inside a module of the current
 * reactor is refused: a path creates no ordering edge in the reactor, so it could be read before
 * the producer has written it.
 *
 * @since 0.12
 */
public final class PeerContractResolver {

    /**
     * Locates the artifact a coordinate names.
     */
    @FunctionalInterface
    public interface CoordinateResolver {

        /**
         * Resolves the backend's main artifact.
         *
         * @param coordinate the coordinate
         * @return a jar file, or the class output directory of a module in the current reactor
         * @throws IOException when the artifact cannot be resolved
         */
        Path resolve(PeerReference.Coordinate coordinate) throws IOException;
    }

    private final PeerContractReader reader;
    private final CoordinateResolver coordinates;
    private final Set<Path> reactorRoots;

    /**
     * Creates a resolver.
     *
     * @param reader       the contract reader
     * @param coordinates  resolves a coordinate to a jar or directory
     * @param reactorRoots the base directories of every module in the current reactor
     */
    public PeerContractResolver(PeerContractReader reader, CoordinateResolver coordinates,
                                Set<Path> reactorRoots) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.coordinates = Objects.requireNonNull(coordinates, "coordinates");
        Set<Path> roots = new HashSet<>();
        for (Path root : reactorRoots) {
            roots.add(real(root));
        }
        this.reactorRoots = Set.copyOf(roots);
    }

    /**
     * Reads every declared peer's contract, sorted by declared name.
     *
     * @param references the declared peers
     * @return one contract per peer
     * @throws PeerContractException naming the peer, when one is refused
     */
    public List<PeerContract> resolve(List<PeerReference> references) {
        Set<String> seen = new HashSet<>();
        for (PeerReference ref : references) {
            if (!seen.add(ref.name())) {
                throw new PeerContractException(ref.name(), "declared more than once");
            }
        }
        List<PeerReference> sorted = new ArrayList<>(references);
        sorted.sort(Comparator.comparing(PeerReference::name));
        List<PeerContract> result = new ArrayList<>();
        for (PeerReference ref : sorted) {
            result.add(resolveOne(ref));
        }
        return List.copyOf(result);
    }

    private PeerContract resolveOne(PeerReference ref) {
        Path location;
        if (ref.path() != null) {
            location = ref.path();
            Path real = real(location);
            for (Path root : reactorRoots) {
                if (real.startsWith(root)) {
                    throw new PeerContractException(ref.name(), location + " lies inside the module " + root
                            + " of this build. A path gives Maven no build order, so the contract could be "
                            + "read before it is written; declare the peer by coordinate and list the "
                            + "producer under the plugin's <dependencies>");
                }
            }
        } else {
            try {
                location = coordinates.resolve(ref.coordinate());
            } catch (IOException e) {
                throw new PeerContractException(ref.name(), "cannot resolve " + ref.coordinate() + ": " + e, e);
            }
        }
        return reader.read(ref.name(), location);
    }

    private static Path real(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
        } catch (IOException _) {
            return path.toAbsolutePath().normalize();
        }
    }
}
