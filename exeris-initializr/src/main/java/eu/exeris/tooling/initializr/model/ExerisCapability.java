package eu.exeris.tooling.initializr.model;

/**
 * Available Exeris capabilities for modular application composition.
 */
public enum ExerisCapability {
    AUDIT_TRAIL(
            "audit-trail",
            "eu.exeris",
            "exeris-caps-audit-trail",
            "Security & Compliance",
            "Immutable audit log records with actor tracking and tamper detection.",
            "AuditCapabilityModule"
    ),
    JWT_VALIDATION(
            "jwt-validation",
            "eu.exeris",
            "exeris-caps-jwt-validation",
            "Identity & Security",
            "Cryptographic signature verification, claims inspection, and tenant assertion.",
            "JwtValidationCapabilityModule"
    ),
    SESSION_MANAGEMENT(
            "session-management",
            "eu.exeris",
            "exeris-caps-session-management",
            "Identity & Security",
            "Distributed session state and idle/expiration lifecycle enforcement.",
            "SessionCapabilityModule"
    ),
    MFA_TOTP(
            "mfa-totp",
            "eu.exeris",
            "exeris-caps-mfa-totp",
            "Identity & Security",
            "RFC 6238 time-based one-time password verification.",
            "MfaTotpCapabilityModule"
    ),
    RATE_LIMITING(
            "rate-limiting",
            "eu.exeris",
            "exeris-caps-rate-limiting",
            "Gateway & Traffic",
            "Token-bucket and sliding-window rate limiters per client/tenant.",
            "RateLimitingCapabilityModule"
    ),
    CORS_POLICY(
            "cors-policy",
            "eu.exeris",
            "exeris-caps-cors-policy",
            "Gateway & Traffic",
            "Cross-origin resource sharing policy with strict origin enforcement.",
            "CorsCapabilityModule"
    );

    private final String key;
    private final String groupId;
    private final String artifactId;
    private final String category;
    private final String description;
    private final String moduleClassName;

    ExerisCapability(
            String key,
            String groupId,
            String artifactId,
            String category,
            String description,
            String moduleClassName
    ) {
        this.key = key;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.category = category;
        this.description = description;
        this.moduleClassName = moduleClassName;
    }

    public String key() {
        return key;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    public String category() {
        return category;
    }

    public String description() {
        return description;
    }

    public String moduleClassName() {
        return moduleClassName;
    }

    public static ExerisCapability fromKey(String key) {
        for (ExerisCapability cap : values()) {
            if (cap.key.equalsIgnoreCase(key) || cap.name().equalsIgnoreCase(key)) {
                return cap;
            }
        }
        throw new IllegalArgumentException("Unknown capability: " + key);
    }
}
