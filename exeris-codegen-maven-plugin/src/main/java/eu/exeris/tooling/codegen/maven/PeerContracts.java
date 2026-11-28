package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.contract.PeerContract;
import eu.exeris.tooling.codegen.core.contract.PeerContractException;
import eu.exeris.tooling.codegen.core.contract.PeerContractReader;
import eu.exeris.tooling.codegen.core.contract.PeerContractResolver;
import eu.exeris.tooling.codegen.core.contract.PeerContractResolver.CoordinateResolver;
import eu.exeris.tooling.codegen.core.contract.PeerReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the {@code peers} configuration into read contracts. A shell over
 * {@link PeerContractResolver}; the reading rules live in {@code exeris-codegen-core}.
 */
final class PeerContracts {

    private PeerContracts() {
    }

    /**
     * Reads every declared peer.
     *
     * @param declarations the configured peers; {@code null} or empty reads nothing
     * @param reactorRoots base directories of the modules of the current build
     * @param coordinates  resolves a coordinate to a jar or class output directory
     * @return the contracts, sorted by name
     * @throws PeerContractException naming the peer, when one is refused
     */
    static List<PeerContract> read(List<PeerDeclaration> declarations, Set<Path> reactorRoots,
                                   CoordinateResolver coordinates) {
        if (declarations == null || declarations.isEmpty()) {
            return List.of();
        }
        List<PeerReference> references = new ArrayList<>();
        for (PeerDeclaration declaration : declarations) {
            references.add(reference(declaration));
        }
        return new PeerContractResolver(new PeerContractReader(), coordinates, reactorRoots)
                .resolve(references);
    }

    private static PeerReference reference(PeerDeclaration declaration) {
        String name = declaration.getName() == null ? "(unnamed)" : declaration.getName();
        if (declaration.getName() == null || declaration.getName().isBlank()) {
            throw new PeerContractException(name, "a peer needs a <name>");
        }
        boolean hasCoordinate = declaration.getCoordinate() != null && !declaration.getCoordinate().isBlank();
        return new PeerReference(name,
                declaration.getPath() == null ? null : declaration.getPath().toPath(),
                hasCoordinate ? PeerReference.Coordinate.parse(name, declaration.getCoordinate()) : null);
    }
}
