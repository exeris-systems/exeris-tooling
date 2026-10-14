package eu.exeris.e2e.postgres;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The PostgreSQL server a {@code @Tag("postgres")} class runs against, named by system properties.
 *
 * <ul>
 *   <li>{@value #URL_PROPERTY}: a JDBC URL ({@code jdbc:postgresql://host:port/database}), required;</li>
 *   <li>{@value #USERNAME_PROPERTY} and {@value #PASSWORD_PROPERTY}: the administrative login.
 *       Optional when the URL carries {@code user} and {@code password} parameters itself.</li>
 * </ul>
 *
 * <p>The administrative role creates the schemas and roles a class needs and applies the emitted
 * migrations; isolation cells run as a {@code NOSUPERUSER NOBYPASSRLS} role the class creates.
 *
 * <p>A class tagged {@code postgres} runs only when the tag is selected ({@code -Pe2e-postgres};
 * the default build excludes it). Once selected, a missing URL fails the class instead of skipping
 * it, because a skipped isolation suite reports the same green as a passing one.
 */
public final class PostgresTarget {

    public static final String URL_PROPERTY = "exeris.e2e.postgres.url";
    public static final String USERNAME_PROPERTY = "exeris.e2e.postgres.username";
    public static final String PASSWORD_PROPERTY = "exeris.e2e.postgres.password";

    private final String url;

    private PostgresTarget(String url) {
        this.url = url;
    }

    /**
     * Reads the target from the system properties.
     *
     * @throws IllegalStateException when {@value #URL_PROPERTY} is not set
     */
    public static PostgresTarget required() {
        String url = System.getProperty(URL_PROPERTY);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("a @Tag(\"postgres\") class was selected, but -D" + URL_PROPERTY
                    + " is not set. Run: mvn -pl exeris-e2e-tests -am verify -Pe2e-postgres -D"
                    + URL_PROPERTY + "=jdbc:postgresql://localhost:5432/postgres -D" + USERNAME_PROPERTY
                    + "=postgres -D" + PASSWORD_PROPERTY + "=<password>  (see CONTRIBUTING.md)");
        }
        String withCredentials = url.strip();
        withCredentials = appendParameter(withCredentials, "user", System.getProperty(USERNAME_PROPERTY));
        withCredentials = appendParameter(withCredentials, "password", System.getProperty(PASSWORD_PROPERTY));
        return new PostgresTarget(withCredentials);
    }

    /** The JDBC URL, carrying the administrative login. */
    public String url() {
        return url;
    }

    /** Opens a connection as the administrative role. */
    public Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    /**
     * The URL without its {@code user} and {@code password} parameters, for a connection that logs
     * in as another role: the driver lets a URL parameter override the login it is handed.
     */
    public String urlWithoutCredentials() {
        int query = url.indexOf('?');
        if (query < 0) {
            return url;
        }
        String kept = Arrays.stream(url.substring(query + 1).split("&"))
                .filter(parameter -> !parameter.isEmpty())
                .filter(parameter -> !parameter.startsWith("user=") && !parameter.startsWith("password="))
                .collect(Collectors.joining("&"));
        return kept.isEmpty() ? url.substring(0, query) : url.substring(0, query) + "?" + kept;
    }

    private static String appendParameter(String url, String name, String value) {
        if (value == null || value.isBlank()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
