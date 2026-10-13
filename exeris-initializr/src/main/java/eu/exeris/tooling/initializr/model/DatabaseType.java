package eu.exeris.tooling.initializr.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The database a generated backend is scaffolded for: the JDBC driver its POM declares at runtime
 * scope, and the connection its README configures.
 *
 * <p>The choice is a scaffold default the application owns from then on. {@code exeris-app-starter}
 * declares no JDBC driver (ADR-091), so the generated POM declares one, and changing it is an edit to
 * that POM.
 *
 * @since 0.10
 */
public enum DatabaseType {

    /**
     * H2, which builds the project and runs its generated tests with no database server. It does not
     * run the generated migrations, which are PostgreSQL SQL, so an application started on it has no
     * tables of its own.
     */
    H2("h2", "com.h2database", "h2"),

    /** PostgreSQL, on the server the generated {@code docker-compose.yml} starts. */
    POSTGRESQL("postgresql", "org.postgresql", "postgresql");

    private final String cliValue;
    private final String driverGroupId;
    private final String driverArtifactId;

    DatabaseType(String cliValue, String driverGroupId, String driverArtifactId) {
        this.cliValue = cliValue;
        this.driverGroupId = driverGroupId;
        this.driverArtifactId = driverArtifactId;
    }

    /**
     * Returns the value that selects this database on the command line ({@code --database=<value>}).
     *
     * @return the lower-case value, {@code h2} or {@code postgresql}
     * @since 0.10
     */
    public String cliValue() {
        return cliValue;
    }

    /**
     * Returns the groupId of the JDBC driver the generated POM declares.
     *
     * @return the driver's Maven groupId
     * @since 0.10
     */
    public String driverGroupId() {
        return driverGroupId;
    }

    /**
     * Returns the artifactId of the JDBC driver the generated POM declares.
     *
     * @return the driver's Maven artifactId
     * @since 0.10
     */
    public String driverArtifactId() {
        return driverArtifactId;
    }

    /**
     * Returns the JDBC URL the generated README passes as {@code exeris.persistence.jdbcUrl}.
     *
     * @param database the database name, a lower-case identifier
     * @return the URL of an in-memory H2 database in PostgreSQL mode, or of {@code database} on a
     *         PostgreSQL server at {@code localhost:5432}
     * @since 0.10
     */
    public String jdbcUrl(String database) {
        return switch (this) {
            case H2 -> "jdbc:h2:mem:" + database + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
            case POSTGRESQL -> "jdbc:postgresql://localhost:5432/" + database;
        };
    }

    /**
     * Finds the database a command-line value names, matched exactly.
     *
     * @param value the value given to {@code --database}
     * @return the database, or empty when {@code value} names none
     * @since 0.10
     */
    public static Optional<DatabaseType> fromCliValue(String value) {
        return Arrays.stream(values()).filter(d -> d.cliValue.equals(value)).findFirst();
    }
}
