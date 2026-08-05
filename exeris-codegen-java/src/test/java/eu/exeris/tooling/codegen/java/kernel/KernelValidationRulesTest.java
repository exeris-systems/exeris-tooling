package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.java.kernel.KernelValidationRules.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place that decides which fields carry a {@code @Validation} check.
 *
 * <p>What makes this worth its own test is that two emitters read it: the handler turns each rule
 * into a guard, the handler test turns each rule into a case. If they disagreed about the rule set,
 * the generated test would cover a different set of guards than the handler emits and stay green
 * either way — the exact failure this type exists to prevent.
 */
@DisplayName("KernelValidationRules")
class KernelValidationRulesTest {

    @Test
    @DisplayName("a required reference field carries a not-null rule; a required primitive does not")
    void requiredOnlyAppliesToReferenceTypes() {
        List<KernelValidationRules.FieldRules> rules = KernelValidationRules.of(List.of(
                FieldMetadata.builder("name", "String").required(true).build(),
                // A primitive cannot be null, so a not-null guard on it would never fire.
                FieldMetadata.builder("quantity", "int").required(true).build()));

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).field().name()).isEqualTo("name");
        assertThat(rules.get(0).rules()).extracting(KernelValidationRules.Rule::kind)
                .containsExactly(Kind.NOT_NULL);
    }

    @Test
    @DisplayName("rule order per field is fixed, so guards and generated cases stay in step")
    void ruleOrderIsFixed() {
        List<KernelValidationRules.FieldRules> rules = KernelValidationRules.of(List.of(
                FieldMetadata.builder("code", "String").required(true)
                        .minLength(2).maxLength(9).pattern("^[A-Z]+$").build()));

        assertThat(rules.get(0).rules()).extracting(KernelValidationRules.Rule::kind)
                .containsExactly(Kind.NOT_NULL, Kind.MIN_LENGTH, Kind.MAX_LENGTH, Kind.PATTERN);
    }

    @Test
    @DisplayName("length rules attach to String only, numeric bounds to numeric types only")
    void rulesAreTypeGated() {
        List<KernelValidationRules.FieldRules> rules = KernelValidationRules.of(List.of(
                // Length bounds on a non-String are not enforceable — there is no length() to call.
                FieldMetadata.builder("placedAt", "Instant").minLength(3).build(),
                // …and a numeric bound on a String has nothing to compare.
                FieldMetadata.builder("label", "String").min(1L).build(),
                FieldMetadata.builder("total", "BigDecimal").min(1L).max(9L).build()));

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).field().name()).isEqualTo("total");
        assertThat(rules.get(0).rules()).extracting(KernelValidationRules.Rule::kind)
                .containsExactly(Kind.MIN, Kind.MAX);
    }

    @Test
    @DisplayName("the accessor and mutator names are derived here, so both emitters use one spelling")
    void accessorNamesAreShared() {
        KernelValidationRules.FieldRules fr = KernelValidationRules.of(List.of(
                FieldMetadata.builder("orderNumber", "String").required(true).build())).get(0);

        assertThat(fr.accessor()).isEqualTo("getOrderNumber");
        assertThat(fr.mutator()).isEqualTo("setOrderNumber");
        // Prefixed so it can never collide with a handler-scope variable — a field named `id`
        // would otherwise clash with handleUpdate's path-id local (T22).
        assertThat(fr.local()).isEqualTo("valOrderNumber");
    }

    @Test
    @DisplayName("a field with no enforceable rule is absent, not present-and-empty")
    void unconstrainedFieldsAreDropped() {
        assertThat(KernelValidationRules.of(List.of(
                FieldMetadata.builder("expedited", "boolean").build(),
                FieldMetadata.builder("note", "String").build()))).isEmpty();
    }
}
