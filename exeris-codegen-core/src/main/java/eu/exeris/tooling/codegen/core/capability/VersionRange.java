package eu.exeris.tooling.codegen.core.capability;

/**
 * A minimal, deterministic version-range matcher for capability resolution.
 *
 * <p>Supported {@code @Requires(versionRange = …)} forms:
 * <ul>
 *   <li>blank / {@code null} — matches any provider version (including unversioned)</li>
 *   <li>Maven-style intervals — {@code [1.0,2.0)}, {@code (1.0,2.0]}, {@code [1.0,)},
 *       {@code (,2.0]}; {@code [} / {@code ]} inclusive, {@code (} / {@code )} exclusive</li>
 *   <li>{@code [1.5]} — exactly 1.5 (both bounds inclusive and equal)</li>
 *   <li>a bare version ({@code 1.2.0}) — treated as <em>exact equality</em> (a contract
 *       registry wants predictable matching, not Maven's "soft" lower-bound semantics)</li>
 * </ul>
 *
 * <p>A bounded range never matches an unversioned provider ({@code version == null}):
 * you cannot prove an unknown version sits inside an interval. Only the blank range does.
 *
 * <p>Version comparison splits on {@code . - +} and compares segment-wise: numerically when
 * both segments parse as integers, lexically otherwise. Missing trailing segments read as 0
 * ({@code 1.2} == {@code 1.2.0}).
 *
 * @since 0.5.0
 */
public final class VersionRange {

    private final boolean any;
    private final String exact;          // non-null => exact-match mode
    private final String lower;          // null => unbounded below
    private final boolean lowerInclusive;
    private final String upper;          // null => unbounded above
    private final boolean upperInclusive;

    private VersionRange(boolean any, String exact,
                         String lower, boolean lowerInclusive,
                         String upper, boolean upperInclusive) {
        this.any = any;
        this.exact = exact;
        this.lower = lower;
        this.lowerInclusive = lowerInclusive;
        this.upper = upper;
        this.upperInclusive = upperInclusive;
    }

    /**
     * Parses a range spec.
     *
     * @param spec the range string, or {@code null}/blank for "any"
     * @return a matcher
     * @throws IllegalArgumentException if an interval is malformed
     */
    public static VersionRange parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return new VersionRange(true, null, null, false, null, false);
        }
        String s = spec.trim();
        char first = s.charAt(0);
        if (first != '[' && first != '(') {
            // bare version => exact match
            return new VersionRange(false, s, null, false, null, false);
        }
        char last = s.charAt(s.length() - 1);
        if (last != ']' && last != ')') {
            throw new IllegalArgumentException("Malformed version range (unclosed interval): " + spec);
        }
        boolean lowInc = first == '[';
        boolean upInc = last == ']';
        String inner = s.substring(1, s.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            // [1.5] => exactly 1.5. An exclusive single-value form (1.5), [1.5), (1.5]
            // denotes the empty set — reject it rather than silently treating it as exact.
            if (!lowInc || !upInc) {
                throw new IllegalArgumentException(
                        "Malformed version range (exclusive single value denotes the empty set): " + spec);
            }
            String v = inner.trim();
            if (v.isEmpty()) {
                throw new IllegalArgumentException("Malformed version range (empty single value): " + spec);
            }
            return new VersionRange(false, v, null, false, null, false);
        }
        String low = inner.substring(0, comma).trim();
        String up = inner.substring(comma + 1).trim();
        return new VersionRange(false, null,
                low.isEmpty() ? null : low, lowInc,
                up.isEmpty() ? null : up, upInc);
    }

    /**
     * @param version the provider's declared version, or {@code null} when unversioned
     * @return whether {@code version} satisfies this range
     */
    public boolean matches(String version) {
        if (any) {
            return true;
        }
        if (version == null || version.isBlank()) {
            // Only the blank range (handled above) matches an unversioned provider.
            return false;
        }
        if (exact != null) {
            return compare(version, exact) == 0;
        }
        if (lower != null) {
            int c = compare(version, lower);
            if (c < 0 || (c == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (upper != null) {
            int c = compare(version, upper);
            if (c > 0 || (c == 0 && !upperInclusive)) {
                return false;
            }
        }
        return true;
    }

    /** Segment-wise version comparison; package-visible for unit tests. */
    static int compare(String a, String b) {
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            Integer ia = tryInt(sa);
            Integer ib = tryInt(sb);
            int c = (ia != null && ib != null) ? Integer.compare(ia, ib) : sa.compareTo(sb);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static Integer tryInt(String s) {
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
