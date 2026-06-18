package eu.exeris.tooling.codegen.java.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two action-name transforms that several generators share.
 *
 * <p>Before this util, {@code KernelHandlerGenerator}, {@code KernelApplicationGenerator},
 * {@code OpenApiPathsBuilder}, and the page/table DSL generators each carried a byte-identical
 * copy. The route's method reference (built from {@link NameCasing#pascal}) must resolve to the
 * generated {@code handle<X>} (also built from {@code pascal}); the served route segment
 * ({@link NameCasing#kebab}) must equal the advertised OpenAPI/DSL path segment. One impl, one
 * test — divergence is now impossible by construction.
 */
@DisplayName("NameCasing")
class NameCasingTest {

    @ParameterizedTest
    @DisplayName("pascal: camelCase / kebab / snake action identities → one Java fragment")
    @CsvSource({
            "markUrgent,  MarkUrgent",
            "mark-urgent, MarkUrgent",
            "mark_urgent, MarkUrgent",
            "cancel,      Cancel",
            "applyDiscount2, ApplyDiscount2"
    })
    void pascalNormalizesSeparators(String input, String expected) {
        assertThat(NameCasing.pascal(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("kebab: camelCase action identity → URL path segment")
    @CsvSource({
            "markUrgent,  mark-urgent",
            "cancel,      cancel",
            "mark-urgent, mark-urgent"
    })
    void kebabProducesUrlSegment(String input, String expected) {
        assertThat(NameCasing.kebab(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("pascal(name) and kebab(name) stay paired: the route fragment and the URL "
            + "segment derive from the same identity")
    void pascalAndKebabAgreeOnIdentity() {
        // The route wiring emits handle<pascal(name)> at URL .../actions/<kebab(name)>;
        // both must come from one source so they cannot drift.
        String name = "markUrgent";
        assertThat("handle" + NameCasing.pascal(name)).isEqualTo("handleMarkUrgent");
        assertThat(NameCasing.kebab(name)).isEqualTo("mark-urgent");
    }
}
