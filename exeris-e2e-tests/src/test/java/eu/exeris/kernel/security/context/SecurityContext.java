package eu.exeris.kernel.security.context;

/**
 * Test stub for the kernel security context. Generated {@code Events}
 * publishers call {@link #currentTenant()} to obtain a tenant binding.
 */
public final class SecurityContext {

    private SecurityContext() {
    }

    public static TenantContext currentTenant() {
        return null;
    }
}
