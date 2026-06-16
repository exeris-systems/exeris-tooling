package eu.exeris.tooling.codegen.core.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VersionRange")
class VersionRangeTest {

    @Test
    @DisplayName("blank/null range matches anything, including an unversioned provider")
    void blankMatchesAny() {
        assertThat(VersionRange.parse(null).matches("1.0.0")).isTrue();
        assertThat(VersionRange.parse("").matches(null)).isTrue();
        assertThat(VersionRange.parse("  ").matches("9.9")).isTrue();
    }

    @Test
    @DisplayName("a bare version is an exact match")
    void bareVersionIsExact() {
        VersionRange r = VersionRange.parse("1.2.0");
        assertThat(r.matches("1.2.0")).isTrue();
        assertThat(r.matches("1.2")).isTrue();      // trailing .0 implied
        assertThat(r.matches("1.2.1")).isFalse();
        assertThat(r.matches(null)).isFalse();
    }

    @Test
    @DisplayName("half-open interval [1.0,2.0) includes the lower bound, excludes the upper")
    void halfOpenInterval() {
        VersionRange r = VersionRange.parse("[1.0,2.0)");
        assertThat(r.matches("1.0")).isTrue();
        assertThat(r.matches("1.5")).isTrue();
        assertThat(r.matches("1.9.9")).isTrue();
        assertThat(r.matches("2.0")).isFalse();
        assertThat(r.matches("0.9")).isFalse();
    }

    @Test
    @DisplayName("open-ended ranges and inclusive upper")
    void openEndedRanges() {
        assertThat(VersionRange.parse("[1.0,)").matches("5.0")).isTrue();
        assertThat(VersionRange.parse("[1.0,)").matches("0.9")).isFalse();
        assertThat(VersionRange.parse("(,2.0]").matches("2.0")).isTrue();
        assertThat(VersionRange.parse("(,2.0]").matches("2.0.1")).isFalse();
        assertThat(VersionRange.parse("(1.0,2.0)").matches("1.0")).isFalse();   // exclusive lower
    }

    @Test
    @DisplayName("single-value interval [1.5] is exact")
    void singleValueInterval() {
        VersionRange r = VersionRange.parse("[1.5]");
        assertThat(r.matches("1.5")).isTrue();
        assertThat(r.matches("1.6")).isFalse();
    }

    @Test
    @DisplayName("a bounded range never matches an unversioned provider")
    void boundedNeverMatchesUnversioned() {
        assertThat(VersionRange.parse("[1.0,2.0)").matches(null)).isFalse();
        assertThat(VersionRange.parse("1.0.0").matches(null)).isFalse();
    }

    @Test
    @DisplayName("numeric segments compare numerically, not lexically (10 > 9)")
    void numericComparison() {
        assertThat(VersionRange.compare("1.10.0", "1.9.0")).isPositive();
        assertThat(VersionRange.compare("2.0", "10.0")).isNegative();
        assertThat(VersionRange.compare("1.0.0", "1.0")).isZero();
    }

    @Test
    @DisplayName("a malformed interval is rejected")
    void malformedInterval() {
        assertThatThrownBy(() -> VersionRange.parse("[1.0,2.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
