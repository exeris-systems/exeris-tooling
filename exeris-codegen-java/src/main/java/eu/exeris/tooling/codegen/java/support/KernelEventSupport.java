package eu.exeris.tooling.codegen.java.support;

import com.palantir.javapoet.ClassName;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * Shared helpers for generators emitting against the Open-Core
 * {@code eu.exeris.kernel.spi.events.*} surface. Sibling of
 * {@link KernelScaffold}; consolidates the event-name normalisation,
 * constant-case conversion, duplicate-name guard, and the
 * frequently-referenced SPI {@link ClassName} constants used by both
 * the publisher and the subscriber generators.
 */
public final class KernelEventSupport {

    /** {@code eu.exeris.kernel.spi.events.EventEngine}. */
    public static final ClassName EVENT_ENGINE =
            ClassName.get("eu.exeris.kernel.spi.events", "EventEngine");

    /** {@code eu.exeris.kernel.spi.events.EventDescriptor}. */
    public static final ClassName EVENT_DESCRIPTOR =
            ClassName.get("eu.exeris.kernel.spi.events", "EventDescriptor");

    /** {@code eu.exeris.kernel.spi.events.EventPayload}. */
    public static final ClassName EVENT_PAYLOAD =
            ClassName.get("eu.exeris.kernel.spi.events", "EventPayload");

    private KernelEventSupport() {
        // Utility class.
    }

    /**
     * Normalises a {@link DomainEventMetadata#name()} to a canonical
     * {@code <Name>Event} identifier:
     * <ul>
     *   <li>blank/null name → {@code <EntityName>Event}</li>
     *   <li>name not ending in {@code Event} → name + {@code Event}</li>
     *   <li>otherwise → name unchanged</li>
     * </ul>
     */
    public static String eventName(DomainEventMetadata event, String entityName) {
        String raw = event.name();
        if (raw == null || raw.isBlank()) {
            return entityName + "Event";
        }
        return raw.endsWith("Event") ? raw : raw + "Event";
    }

    /**
     * Converts a {@code CamelCase} or {@code camelCase} identifier into
     * {@code UPPER_SNAKE_CASE} suitable for use as a {@code static final}
     * constant name in emitted code.
     */
    public static String toConstantCase(String camelCase) {
        StringBuilder sb = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * Throws {@link IllegalArgumentException} if two or more
     * {@link DomainEventMetadata} entries on the given domain normalise
     * to the same {@link #eventName(DomainEventMetadata, String)}. Without
     * this guard the publisher would emit duplicate {@code static final}
     * constant fields (non-compiling output) and both generators would
     * collide at runtime on the same routing key / SPI registration.
     */
    public static void assertDistinctEventNames(DomainMetadata metadata) {
        String entity = metadata.entityName();
        long distinctNames = metadata.events().stream()
                .map(e -> eventName(e, entity))
                .distinct()
                .count();
        if (distinctNames != metadata.events().size()) {
            throw new IllegalArgumentException(
                    "Duplicate event names after normalisation for entity '"
                            + entity + "'. Each @DomainEvent must produce a unique "
                            + "<Name>Event identifier.");
        }
    }
}
