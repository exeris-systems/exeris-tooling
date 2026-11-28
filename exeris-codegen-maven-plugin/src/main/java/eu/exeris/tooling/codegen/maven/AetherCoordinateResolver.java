package eu.exeris.tooling.codegen.maven;

import eu.exeris.tooling.codegen.core.contract.PeerContractResolver.CoordinateResolver;
import eu.exeris.tooling.codegen.core.contract.PeerReference.Coordinate;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.transfer.ChecksumFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a peer's main jar through the Maven resolver, against the project's remote
 * repositories. The artifact is never added to the project's dependencies, so the peer's jar is not on
 * the consumer's classpath and no class of it is loaded. Inside one reactor the session's workspace reader answers with
 * the producer's class output directory once the producer has run {@code process-classes}. A
 * download whose checksum does not match, or whose repository serves no checksum, fails the request
 * whatever checksum policy the repository or the build's own checksum flags declare: the session's
 * policy is merged over each repository's, so the session the request runs in carries {@code fail}
 * as well.
 */
final class AetherCoordinateResolver implements CoordinateResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    AetherCoordinateResolver(RepositorySystem system, RepositorySystemSession session,
                             List<RemoteRepository> repositories) {
        this.system = system;
        this.session = strictSession(session);
        this.repositories = failOnChecksumMismatch(repositories);
    }

    /** A copy of the session whose checksum policy is {@code fail}, or {@code null} for no session. */
    static RepositorySystemSession strictSession(RepositorySystemSession session) {
        if (session == null) {
            return null;
        }
        DefaultRepositorySystemSession strict = new DefaultRepositorySystemSession(session);
        strict.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        return strict;
    }

    /** Each repository, with the checksum policy of its release and snapshot policies set to fail. */
    static List<RemoteRepository> failOnChecksumMismatch(List<RemoteRepository> repositories) {
        if (repositories == null) {
            return List.of();
        }
        List<RemoteRepository> strict = new ArrayList<>(repositories.size());
        for (RemoteRepository repository : repositories) {
            strict.add(new RemoteRepository.Builder(repository)
                    .setReleasePolicy(strict(repository.getPolicy(false)))
                    .setSnapshotPolicy(strict(repository.getPolicy(true)))
                    .build());
        }
        return strict;
    }

    private static RepositoryPolicy strict(RepositoryPolicy policy) {
        return new RepositoryPolicy(policy.isEnabled(), policy.getUpdatePolicy(),
                RepositoryPolicy.CHECKSUM_POLICY_FAIL);
    }

    private static boolean isChecksumFailure(ArtifactResolutionException failure) {
        List<Throwable> pending = new ArrayList<>();
        pending.add(failure);
        for (ArtifactResult result : failure.getResults()) {
            pending.addAll(result.getExceptions());
        }
        for (int i = 0; i < pending.size() && i < 64; i++) {
            Throwable t = pending.get(i);
            if (t instanceof ChecksumFailureException) {
                return true;
            }
            if (t.getCause() != null) {
                pending.add(t.getCause());
            }
            pending.addAll(List.of(t.getSuppressed()));
        }
        return false;
    }

    @Override
    public Path resolve(Coordinate coordinate) throws IOException {
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact(coordinate.groupId(), coordinate.artifactId(), "jar", coordinate.version()),
                repositories, null);
        try {
            return system.resolveArtifact(session, request).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            String prefix = isChecksumFailure(e) ? "checksum not verified: " : "";
            throw new IOException(prefix + e.getMessage(), e);
        }
    }
}
