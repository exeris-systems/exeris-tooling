package eu.exeris.tooling.codegen.java.support;

import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the one derivation of an entity's infrastructure packages: only the trailing
 * {@code .domain} segment is replaced, so a {@code domain} earlier in the package name stays part
 * of the base package for the main and the test emitters alike.
 */
@DisplayName("DomainPackages")
class DomainPackagesTest {

    @ParameterizedTest
    @DisplayName("basePackage strips the trailing .domain segment only")
    @CsvSource({
            "com.shop.domain,          com.shop",
            "com.domainco.app.domain,  com.domainco.app",
            "com.domainx.shop.domain,  com.domainx.shop",
            "a.domain.b.domain,        a.domain.b",
            "a.domain.domain,          a.domain",
            "app.subdomain.domain,     app.subdomain"
    })
    void basePackageStripsTheTrailingSegmentOnly(String domainPackage, String expected) {
        assertThat(DomainPackages.basePackage(domainPackage)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("sibling replaces the trailing .domain segment with the named one")
    @CsvSource({
            "com.shop.domain,          repository, com.shop.repository",
            "com.domainco.app.domain,  service,    com.domainco.app.service",
            "a.domain.b.domain,        handler,    a.domain.b.handler",
            "a.domain.b.domain,        event,      a.domain.b.event"
    })
    void siblingReplacesTheTrailingSegment(String domainPackage, String segment, String expected) {
        DomainMetadata metadata = DomainMetadata.builder("Order", domainPackage).build();

        assertThat(DomainPackages.sibling(metadata, segment)).isEqualTo(expected);
    }

    @Test
    @DisplayName("basePackage(DomainMetadata) reads the entity's package")
    void basePackageOfMetadata() {
        DomainMetadata metadata = DomainMetadata.builder("Widget", "com.domainco.app.domain").build();

        assertThat(DomainPackages.basePackage(metadata)).isEqualTo("com.domainco.app");
    }

    @ParameterizedTest
    @DisplayName("a package that does not end in .domain is refused, never derived from")
    @NullAndEmptySource
    @ValueSource(strings = {"domain", "com.shop", "com.domain.shop", "com.shopdomain", "com.shop.domain.order"})
    void refusesAPackageWithoutTheSuffix(String domainPackage) {
        assertThatThrownBy(() -> DomainPackages.basePackage(domainPackage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".domain");
    }
}
