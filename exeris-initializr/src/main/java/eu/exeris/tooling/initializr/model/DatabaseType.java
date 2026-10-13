package eu.exeris.tooling.initializr.model;

/**
 * Database selection for Exeris applications.
 */
public enum DatabaseType {
    /** Zero-friction in-memory database, runs immediately without container orchestration. */
    H2("com.h2database", "h2", "2.3.232", "org.h2.Driver", "jdbc:h2:mem:appdb;DB_CLOSE_DELAY=-1"),
    POSTGRESQL(
            "org.postgresql",
            "postgresql",
            "42.7.5",
            "org.postgresql.Driver",
            "jdbc:postgresql://localhost:5432/appdb"
    );

    private final String groupId;
    private final String artifactId;
    private final String defaultVersion;
    private final String driverClassName;
    private final String defaultJdbcUrl;

    DatabaseType(
            String groupId,
            String artifactId,
            String defaultVersion,
            String driverClassName,
            String defaultJdbcUrl
    ) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.defaultVersion = defaultVersion;
        this.driverClassName = driverClassName;
        this.defaultJdbcUrl = defaultJdbcUrl;
    }

    public String groupId() {
        return groupId;
    }

    public String artifactId() {
        return artifactId;
    }

    public String defaultVersion() {
        return defaultVersion;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String defaultJdbcUrl() {
        return defaultJdbcUrl;
    }
}
