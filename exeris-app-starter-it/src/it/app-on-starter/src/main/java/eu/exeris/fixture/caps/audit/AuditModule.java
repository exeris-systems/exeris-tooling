package eu.exeris.fixture.caps.audit;

import eu.exeris.sdk.annotation.capability.CapabilityModule;

/**
 * A capability module, so the generated {@code Application} is a composition host and imports
 * the SDK's boot conductor, the other conditional import exeris-app-starter carries. It also gives
 * {@code exeris:verify-capabilities} a graph and a Wall scan to run.
 */
@CapabilityModule
public class AuditModule {
}
