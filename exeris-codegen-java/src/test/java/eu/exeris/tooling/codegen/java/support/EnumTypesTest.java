package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.EnumMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EnumTypes")
class EnumTypesTest {

    private static EnumMetadata emitted(String qualifiedName) {
        String name = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        String pkg = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));
        return new EnumMetadata(name, qualifiedName, pkg, null, List.of());
    }

    private static final List<EnumMetadata> ENUMS = EnumTypes.sorted(List.of(
            emitted("com.shop.b.Status"), emitted("com.shop.a.Status"), emitted("com.shop.Priority")));

    @Test
    @DisplayName("the type names an emitted enum by qualified name, else by simple name")
    void byType() {
        assertThat(EnumTypes.resolve(null, "com.shop.Priority", ENUMS).qualifiedName())
                .isEqualTo("com.shop.Priority");
        assertThat(EnumTypes.resolve("", "Priority", ENUMS).qualifiedName()).isEqualTo("com.shop.Priority");
        // Two enums share the simple name: the first by qualified name, on every run.
        assertThat(EnumTypes.resolve(null, "Status", ENUMS).qualifiedName()).isEqualTo("com.shop.a.Status");
        assertThat(EnumTypes.resolve(null, "com.shop.b.Status", ENUMS).qualifiedName())
                .isEqualTo("com.shop.b.Status");
    }

    @Test
    @DisplayName("an enumType wins over the type, and must itself name an emitted enum")
    void byEnumType() {
        assertThat(EnumTypes.resolve("com.shop.Priority", "String", ENUMS).qualifiedName())
                .isEqualTo("com.shop.Priority");
        assertThat(EnumTypes.resolve("com.shop.Missing", "com.shop.Priority", ENUMS)).isNull();
    }

    @Test
    @DisplayName("a type that names no emitted enum resolves to none")
    void none() {
        assertThat(EnumTypes.resolve(null, "com.shop.OrderStatus", ENUMS)).isNull();
        assertThat(EnumTypes.resolve(null, "java.time.OffsetDateTime", ENUMS)).isNull();
        assertThat(EnumTypes.resolve(null, "com.shop.Priority", List.of())).isNull();
    }
}
