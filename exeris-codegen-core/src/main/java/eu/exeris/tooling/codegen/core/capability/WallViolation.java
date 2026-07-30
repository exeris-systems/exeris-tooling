package eu.exeris.tooling.codegen.core.capability;

/**
 * One cap-tier Wall breach: a class in the cap under build references a type it
 * must not see (ADR-024 validation predicate 4, ADR-055).
 *
 * @param violatingClass  binary name of the cap class holding the reference
 * @param forbiddenType   binary name of the referenced type that breaches the Wall
 * @param rule            which Wall rule was broken — user-facing, so it names the
 *                        boundary rather than an internal enum constant
 *
 * @since 0.7.0
 */
public record WallViolation(String violatingClass, String forbiddenType, Rule rule)
        implements Comparable<WallViolation> {

    /** The three boundaries the cap-tier Wall draws (ADR-024 §"The Wall, extended to capabilities"). */
    public enum Rule {

        /**
         * A host-runtime package — Spring, Netty, Reactor, the servlet API. Host-runtime
         * selection is a Tier 1 / SKU-manifest concern; a cap that hard-wires one stops
         * being portable across SKUs and breaks the guarantee that
         * {@code exeris-spring-runtime}'s absence cannot break any cap.
         */
        HOST_RUNTIME("host-runtime package (host selection belongs to the SKU, not a cap)"),

        /**
         * A kernel private package. Only the SPI surface is a supported cap-facing
         * contract; reaching into internals couples the cap to a kernel patch release.
         */
        KERNEL_INTERNAL("kernel private package (only the kernel SPI surface is cap-facing)"),

        /**
         * Another cap's {@code internal} package. Cross-cap visibility runs through
         * {@code @Provides}d services only — this is what makes a cap detachable under
         * ADR-023 without dragging a hidden classpath along.
         */
        SIBLING_CAP_INTERNAL("sibling cap's internal package (cross-cap access goes through @Provides)");

        private final String description;

        Rule(String description) {
            this.description = description;
        }

        /** Human-readable boundary description, used verbatim in the build failure. */
        public String description() {
            return description;
        }
    }

    /** One line of the build failure, e.g. {@code com.acme.Foo -> org.springframework.X (host-runtime package …)}. */
    public String message() {
        return violatingClass + " -> " + forbiddenType + " (" + rule.description() + ")";
    }

    /**
     * Deterministic ordering for the failure report (hard-constraint #3 applies to
     * diagnostics too — a build must not reorder its own error list between runs).
     */
    @Override
    public int compareTo(WallViolation other) {
        int byClass = violatingClass.compareTo(other.violatingClass);
        if (byClass != 0) {
            return byClass;
        }
        int byType = forbiddenType.compareTo(other.forbiddenType);
        return byType != 0 ? byType : rule.compareTo(other.rule);
    }
}
