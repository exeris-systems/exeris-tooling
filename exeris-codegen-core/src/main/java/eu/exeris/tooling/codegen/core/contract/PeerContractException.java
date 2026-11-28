package eu.exeris.tooling.codegen.core.contract;

/**
 * A peer's contract was refused; the message names the peer.
 *
 * @since 0.12
 */
public final class PeerContractException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the refusal.
     *
     * @param peer   the consumer-declared peer name
     * @param reason what does not hold
     */
    public PeerContractException(String peer, String reason) {
        super("peer '" + peer + "': " + reason);
    }

    /**
     * Creates the refusal with a cause.
     *
     * @param peer   the consumer-declared peer name
     * @param reason what does not hold
     * @param cause  the underlying failure
     */
    public PeerContractException(String peer, String reason, Throwable cause) {
        super("peer '" + peer + "': " + reason, cause);
    }
}
