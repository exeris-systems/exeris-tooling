package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.driver.RequiredSubsystems;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the support classes the generated integration tests share (ADR-058 Amendment 1), in
 * {@code <basePackage>.testsupport} of the integration-test root:
 *
 * <ul>
 *   <li>{@code ApplicationUnderTest} boots the generated {@code Application} once per test JVM on
 *       a fresh in-memory H2 database in PostgreSQL mode, through the kernel's own
 *       {@code exeris.http.*} and {@code exeris.persistence.*} keys. It applies the generated
 *       migrations itself, from the test classpath, in their version order: the kernel's
 *       persistence driver applies only its own. H2 knows two of the PostgreSQL spellings the
 *       migrations use by other names, {@code TIMESTAMPTZ} and a key column's
 *       {@code PRIMARY KEY DEFAULT gen_random_uuid()}, and those two are restated; nothing else in a
 *       migration is changed. Readiness is a response other than {@code 503} from a path no route
 *       serves.</li>
 *   <li>{@code IntegrationHttp} sends one request over {@code java.net.http}, pinned to HTTP/1.1,
 *       and reads the status and the body.</li>
 *   <li>{@code IntegrationTokens}, only when some route demands a verified identity, holds an RSA
 *       key pair made at test runtime, publishes it as a JWKS document and mints RS256 tokens over
 *       it; {@code ApplicationUnderTest} serves the document on a loopback
 *       {@code com.sun.net.httpserver} endpoint and points the kernel's
 *       {@code security.jwks.uri}, {@code security.jwt.issuer} and {@code security.jwt.audience}
 *       at it, so a token is verified as a production token is.</li>
 * </ul>
 *
 * <p>The emitted source imports the JDK alone. Everything that varies between runs, the key pair,
 * the ports and the database name, is made at test runtime; the emitted text holds only fixed
 * literals, so the same metadata gives the same bytes. No class name matches a Surefire or
 * Failsafe default include.
 *
 * @since 0.11
 */
public final class KernelIntegrationSupportGenerator {

    /** Simple name of the class that boots the application under test. */
    public static final String APPLICATION_UNDER_TEST = "ApplicationUnderTest";

    /** Simple name of the HTTP helper. */
    public static final String INTEGRATION_HTTP = "IntegrationHttp";

    /** Simple name of the token helper, emitted when some route demands a verified identity. */
    public static final String INTEGRATION_TOKENS = "IntegrationTokens";

    /** The system property that names the class booted in place of the generated one. */
    public static final String APPLICATION_PROPERTY = "exeris.it.application";

    /** The key id of the signing key, and of the only key the published JWKS document carries. */
    static final String KEY_ID = "exeris-it";

    /** The issuer and the audience every minted token carries and the kernel is configured with. */
    static final String ISSUER = "urn:exeris:it";

    /** The subject of every minted token: a fixed UUID, which the kernel's claims mapper requires. */
    static final String SUBJECT = "01890000-0000-7000-8000-000000000001";

    private static final String LOOPBACK = "127.0.0.1";
    private static final String STRING_LITERAL = "$S";
    private static final String DEFAULT_APPLICATION = "DEFAULT_APPLICATION";
    private static final String BEARER_PARAMETER = "bearer";
    private static final String JSON_PARAMETER = "json";
    private static final String METHOD_PARAMETER = "method";
    private static final String PATH_PARAMETER = "path";

    private static final String JAVA_UTIL = "java.util";
    private static final String JAVA_IO = "java.io";
    private static final String JAVA_NET = "java.net";
    private static final String JAVA_NET_HTTP = "java.net.http";
    private static final String JAVA_SQL = "java.sql";
    private static final String JAVA_SECURITY = "java.security";
    private static final String DO_NOT_EDIT = "<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n";

    private static final ClassName STRING = ClassName.get(String.class);
    private static final ClassName SYSTEM = ClassName.get(System.class);
    private static final ClassName THREAD = ClassName.get(Thread.class);
    private static final ClassName DURATION = ClassName.get("java.time", "Duration");
    private static final ClassName MAP = ClassName.get(JAVA_UTIL, "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get(JAVA_UTIL, "LinkedHashMap");
    private static final ClassName ATOMIC_REFERENCE = ClassName.get("java.util.concurrent.atomic", "AtomicReference");
    private static final ClassName ATOMIC_LONG = ClassName.get("java.util.concurrent.atomic", "AtomicLong");
    private static final ClassName INVOCATION_TARGET = ClassName.get("java.lang.reflect", "InvocationTargetException");
    private static final ClassName IO_EXCEPTION = ClassName.get(JAVA_IO, "IOException");
    private static final ClassName INPUT_STREAM = ClassName.get(JAVA_IO, "InputStream");
    private static final ClassName OUTPUT_STREAM = ClassName.get(JAVA_IO, "OutputStream");
    private static final ClassName UNCHECKED_IO = ClassName.get(JAVA_IO, "UncheckedIOException");
    private static final ClassName INET_SOCKET_ADDRESS = ClassName.get(JAVA_NET, "InetSocketAddress");
    private static final ClassName SERVER_SOCKET = ClassName.get(JAVA_NET, "ServerSocket");
    private static final ClassName URI = ClassName.get(JAVA_NET, "URI");
    private static final ClassName HTTP_CLIENT = ClassName.get(JAVA_NET_HTTP, "HttpClient");
    private static final ClassName HTTP_REQUEST = ClassName.get(JAVA_NET_HTTP, "HttpRequest");
    private static final ClassName HTTP_RESPONSE = ClassName.get(JAVA_NET_HTTP, "HttpResponse");
    private static final ClassName CHARSETS = ClassName.get("java.nio.charset", "StandardCharsets");
    private static final ClassName CONNECTION = ClassName.get(JAVA_SQL, "Connection");
    private static final ClassName DRIVER_MANAGER = ClassName.get(JAVA_SQL, "DriverManager");
    private static final ClassName SQL_EXCEPTION = ClassName.get(JAVA_SQL, "SQLException");
    private static final ClassName STATEMENT = ClassName.get(JAVA_SQL, "Statement");
    private static final ClassName HTTP_SERVER = ClassName.get("com.sun.net.httpserver", "HttpServer");
    private static final ClassName BIG_INTEGER = ClassName.get("java.math", "BigInteger");
    private static final ClassName KEY_PAIR = ClassName.get(JAVA_SECURITY, "KeyPair");
    private static final ClassName KEY_PAIR_GENERATOR = ClassName.get(JAVA_SECURITY, "KeyPairGenerator");
    private static final ClassName SIGNATURE = ClassName.get(JAVA_SECURITY, "Signature");
    private static final ClassName GENERAL_SECURITY = ClassName.get(JAVA_SECURITY, "GeneralSecurityException");
    private static final ClassName RSA_PUBLIC_KEY = ClassName.get("java.security.interfaces", "RSAPublicKey");
    private static final ClassName ARRAYS = ClassName.get(JAVA_UTIL, "Arrays");
    private static final ClassName BASE64 = ClassName.get(JAVA_UTIL, "Base64");

    /**
     * Creates the generator. It keeps no state, so one instance serves every build.
     */
    public KernelIntegrationSupportGenerator() {
        // no state to initialise
    }

    /**
     * Emits the support classes for a build the integration tests boot.
     *
     * @param domains     every entity of the build, in build order
     * @param basePackage the build's base package; the classes go to its {@code testsupport}
     * @return {@code ApplicationUnderTest} and {@code IntegrationHttp}, then
     *         {@code IntegrationTokens} when some route demands a verified identity
     */
    public List<GeneratedFile> generateAll(List<DomainMetadata> domains, String basePackage) {
        String packageName = basePackage + "." + KernelTestSupportGenerator.TEST_SUPPORT_PACKAGE;
        boolean security = RequiredSubsystems.usesSecurity(domains);
        List<GeneratedFile> files = new ArrayList<>(3);
        files.add(file(packageName, APPLICATION_UNDER_TEST,
                applicationUnderTest(packageName, basePackage, migrations(domains), security)));
        files.add(file(packageName, INTEGRATION_HTTP, integrationHttp(packageName)));
        if (security) {
            files.add(file(packageName, INTEGRATION_TOKENS, integrationTokens()));
        }
        return files;
    }

    /**
     * The classpath resource of every generated migration, in the order a migration tool applies
     * them: by version, which for the seven-digit versions the pipeline writes is name order.
     */
    static List<String> migrations(List<DomainMetadata> domains) {
        List<String> names = new ArrayList<>();
        KernelFlywayGenerator flyway = new KernelFlywayGenerator();
        for (DomainMetadata domain : domains) {
            names.add(flyway.generate(domain).relativePath());
        }
        GeneratedFile foreignKeys = new KernelApplicationGenerator().generateForeignKeys(domains);
        if (foreignKeys != null) {
            names.add(foreignKeys.relativePath());
        }
        names.sort(null);
        return List.copyOf(names);
    }

    private static GeneratedFile file(String packageName, String className, TypeSpec type) {
        return new GeneratedFile(packageName, className, KernelScaffold.render(packageName, type),
                ArtifactType.TEST);
    }

    // ------------------------------------------------------------------ ApplicationUnderTest

    private static TypeSpec applicationUnderTest(String packageName, String basePackage,
                                                 List<String> migrations, boolean security) {
        ClassName self = ClassName.get(packageName, APPLICATION_UNDER_TEST);
        TypeSpec.Builder type = KernelScaffold.publicClass(APPLICATION_UNDER_TEST)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Boots the generated application once per test JVM for the generated integration\n")
                .addJavadoc("tests (ADR-058 Amendment 1).\n")
                .addJavadoc("<p>The first call to {@link #baseUri()} reserves a loopback port, applies the\n")
                .addJavadoc("generated migrations to a fresh in-memory H2 database in PostgreSQL mode, hands both to\n")
                .addJavadoc("the kernel as its own {@code exeris.http.*} and {@code exeris.persistence.*} system\n")
                .addJavadoc("properties, and runs the application on a daemon thread until a request to a path no\n")
                .addJavadoc("route serves is answered with anything but {@code 503}. The properties it set are\n")
                .addJavadoc("restored once the kernel has read them. {@code -D$L=<class>} boots\n",
                        APPLICATION_PROPERTY)
                .addJavadoc("another class, such as a\n")
                .addJavadoc("subclass of {@code Application} that overrides a hook.\n")
                .addJavadoc("<p>The kernel's persistence driver applies its own migrations only, so the generated\n")
                .addJavadoc("ones are applied here, read from the test classpath. H2 knows two PostgreSQL spellings\n")
                .addJavadoc("they use by other names, and those two are restated.\n")
                .addJavadoc(DO_NOT_EDIT);

        type.addField(constant(STRING, DEFAULT_APPLICATION, CodeBlock.of("$S", basePackage + ".Application"),
                "The class booted unless {@code -D" + APPLICATION_PROPERTY + "} names another."));
        type.addField(constant(STRING, "APPLICATION_PROPERTY", CodeBlock.of("$S", APPLICATION_PROPERTY),
                "The system property that names the class to boot."));
        type.addField(privateConstant(STRING, "LOOPBACK", CodeBlock.of("$S", LOOPBACK)));
        type.addField(FieldSpec.builder(STRING, "DATABASE_ROLE", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The user and password of the in-memory database. The connection that applies the\n")
                .addJavadoc("migrations creates the database, so its owner, and the kernel's pool sign in alike.\n")
                .initializer("$S", "exeris_it")
                .build());
        type.addField(privateConstant(STRING, "READINESS_PATH", CodeBlock.of("$S", "/exeris-it-readiness")));
        type.addField(privateConstant(DURATION, "BOOT_TIMEOUT", CodeBlock.of("$T.ofSeconds(60)", DURATION)));
        type.addField(privateConstant(DURATION, "STOP_TIMEOUT", CodeBlock.of("$T.ofSeconds(75)", DURATION)));
        type.addField(privateConstant(ArrayTypeName.of(STRING), "MIGRATIONS", stringArray(migrations)));
        type.addField(privateConstant(ArrayTypeName.of(STRING), "PROPERTIES", stringArray(properties(security))));
        type.addField(privateConstant(ClassName.get(Object.class), "LOCK", CodeBlock.of("new Object()")));
        type.addField(privateConstant(ParameterizedTypeName.get(ATOMIC_REFERENCE, ClassName.get(Throwable.class)),
                "FAILURE", CodeBlock.of("new $T<>()", ATOMIC_REFERENCE)));
        type.addField(FieldSpec.builder(STRING, "baseUri", Modifier.PRIVATE, Modifier.STATIC).build());
        type.addField(FieldSpec.builder(Throwable.class, "bootFailure", Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("Why the boot failed; every later {@link #baseUri()} rethrows it instead of booting\n")
                .addJavadoc("again, so a failed boot costs the run one boot timeout, not one per test.\n")
                .build());
        type.addField(FieldSpec.builder(THREAD, "application", Modifier.PRIVATE, Modifier.STATIC).build());
        if (security) {
            type.addField(FieldSpec.builder(HTTP_SERVER, "keys", Modifier.PRIVATE, Modifier.STATIC).build());
        }

        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(baseUriMethod());
        type.addMethod(stopMethod(security));
        type.addMethod(bootMethod(self, security));
        type.addMethod(discardMethod(security));
        type.addMethod(runMethod());
        type.addMethod(awaitComposedMethod());
        type.addMethod(applyMigrationsMethod());
        type.addMethod(readMethod(self));
        type.addMethod(h2Method());
        type.addMethod(statementsMethod());
        type.addMethod(reserveLoopbackPortMethod());
        type.addMethod(snapshotMethod());
        type.addMethod(restoreMethod());
        if (security) {
            type.addMethod(serveKeysMethod(ClassName.get(packageName, INTEGRATION_TOKENS)));
        }
        return type.build();
    }

    private static List<String> properties(boolean security) {
        List<String> keys = new ArrayList<>(List.of(
                "exeris.http.mode", "exeris.http.bindHost", "exeris.http.port",
                "http.mode", "http.bindHost", "http.port",
                "exeris.persistence.jdbcUrl", "exeris.persistence.username", "exeris.persistence.password",
                "exeris.persistence.rlsEnabled", "exeris.persistence.runMigrations"));
        if (security) {
            keys.addAll(List.of("exeris.security.jwks.uri", "exeris.security.jwt.issuer",
                    "exeris.security.jwt.audience"));
        }
        return keys;
    }

    private static MethodSpec baseUriMethod() {
        return MethodSpec.methodBuilder("baseUri")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(STRING)
                .addJavadoc("Returns the application's base URI, booting it on the first call.\n")
                .addJavadoc("@return {@code http://127.0.0.1:<port>}\n")
                .addJavadoc("@throws AssertionError if this or an earlier call failed to boot the application\n")
                .addCode("""
                        synchronized (LOCK) {
                            if (bootFailure != null) {
                                throw new AssertionError("the application failed to boot earlier in this run",
                                        bootFailure);
                            }
                            if (baseUri == null) {
                                try {
                                    baseUri = boot();
                                } catch (RuntimeException | Error e) {
                                    bootFailure = e;
                                    throw e;
                                }
                            }
                            return baseUri;
                        }
                        """)
                .build();
    }

    private static MethodSpec stopMethod(boolean security) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("stop")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addException(InterruptedException.class)
                .addJavadoc("Stops the application, for a caller that runs the tests in-process; the next\n")
                .addJavadoc("{@link #baseUri()} boots a fresh one.\n")
                .addJavadoc("@throws InterruptedException if interrupted while waiting for it to stop\n")
                .beginControlFlow("synchronized (LOCK)")
                .beginControlFlow("if (application != null)")
                .addStatement("application.interrupt()")
                .addStatement("application.join(STOP_TIMEOUT.toMillis())")
                .addStatement("application = null")
                .endControlFlow();
        if (security) {
            method.beginControlFlow("if (keys != null)")
                    .addStatement("keys.stop(0)")
                    .addStatement("keys = null")
                    .endControlFlow();
        }
        return method.addStatement("baseUri = null")
                .addStatement("bootFailure = null")
                .addStatement("FAILURE.set(null)")
                .endControlFlow()
                .build();
    }

    private static MethodSpec bootMethod(ClassName self, boolean security) {
        CodeBlock.Builder body = CodeBlock.builder()
                .addStatement("FAILURE.set(null)")
                .addStatement("int port = reserveLoopbackPort()")
                .addStatement("$T jdbcUrl = $S + $T.toUnsignedString($T.nanoTime(), 36) + $S", STRING,
                        "jdbc:h2:mem:exeris_it_", Long.class, SYSTEM, ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                .addStatement("$T applicationClass = $T.getProperty(APPLICATION_PROPERTY, DEFAULT_APPLICATION)",
                        STRING, SYSTEM)
                .addStatement("applyMigrations(jdbcUrl)")
                .addStatement("$T<$T, $T> previous = snapshot()", MAP, STRING, STRING)
                .beginControlFlow("try")
                .addStatement("$T.setProperty($S, $S)", SYSTEM, "exeris.http.mode", "SERVER")
                .addStatement("$T.setProperty($S, LOOPBACK)", SYSTEM, "exeris.http.bindHost")
                .addStatement("$T.setProperty($S, $T.toString(port))", SYSTEM, "exeris.http.port", Integer.class)
                .addStatement("$T.setProperty($S, $S)", SYSTEM, "http.mode", "SERVER")
                .addStatement("$T.setProperty($S, LOOPBACK)", SYSTEM, "http.bindHost")
                .addStatement("$T.setProperty($S, $T.toString(port))", SYSTEM, "http.port", Integer.class)
                .addStatement("$T.setProperty($S, jdbcUrl)", SYSTEM, "exeris.persistence.jdbcUrl")
                .addStatement("$T.setProperty($S, DATABASE_ROLE)", SYSTEM, "exeris.persistence.username")
                .addStatement("$T.setProperty($S, DATABASE_ROLE)", SYSTEM, "exeris.persistence.password")
                .addStatement("$T.setProperty($S, $S)", SYSTEM, "exeris.persistence.rlsEnabled", "false")
                .addStatement("$T.setProperty($S, $S)", SYSTEM, "exeris.persistence.runMigrations", "true");
        if (security) {
            body.addStatement("keys = serveKeys()")
                    .addStatement("$T.setProperty($S,\n$S + LOOPBACK + $S + keys.getAddress().getPort() + $S)",
                            SYSTEM, "exeris.security.jwks.uri", "http://", ":", "/jwks")
                    .addStatement("$T.setProperty($S, $T.ISSUER)", SYSTEM, "exeris.security.jwt.issuer",
                            ClassName.get(self.packageName(), INTEGRATION_TOKENS))
                    .addStatement("$T.setProperty($S, $T.AUDIENCE)", SYSTEM, "exeris.security.jwt.audience",
                            ClassName.get(self.packageName(), INTEGRATION_TOKENS));
        }
        body.addStatement("$T thread = $T.ofPlatform().daemon().name($S).unstarted(() -> run(applicationClass))",
                        THREAD, THREAD, "exeris-it-application")
                .addStatement("thread.setContextClassLoader($T.class.getClassLoader())", self)
                .addStatement("thread.start()")
                .addStatement("application = thread")
                .addStatement("awaitComposed(thread, port)")
                .nextControlFlow("catch ($T | $T e)", RuntimeException.class, Error.class)
                .addStatement("discard()")
                .addStatement("throw e")
                .nextControlFlow("finally")
                .addStatement("restore(previous)")
                .endControlFlow()
                .addStatement("return \"http://\" + LOOPBACK + \":\" + port");
        return MethodSpec.methodBuilder("boot")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addCode(body.build())
                .build();
    }

    private static MethodSpec discardMethod(boolean security) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("discard")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addJavadoc("Releases what a failed boot started: the application thread and the key server.\n")
                .beginControlFlow("if (application != null)")
                .addStatement("application.interrupt()")
                .beginControlFlow("try")
                .addStatement("application.join(STOP_TIMEOUT.toMillis())")
                .nextControlFlow("catch (InterruptedException e)")
                .addStatement("$T.currentThread().interrupt()", THREAD)
                .endControlFlow()
                .addStatement("application = null")
                .endControlFlow();
        if (security) {
            method.beginControlFlow("if (keys != null)")
                    .addStatement("keys.stop(0)")
                    .addStatement("keys = null")
                    .endControlFlow();
        }
        return method.build();
    }

    private static MethodSpec runMethod() {
        return MethodSpec.methodBuilder("run")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(STRING, "applicationClass")
                .beginControlFlow("try")
                .addStatement("Class<?> type = Class.forName(applicationClass, true, "
                        + "$T.currentThread().getContextClassLoader())", THREAD)
                .addStatement("Object instance = type.getDeclaredConstructor().newInstance()")
                .addStatement("type.getMethod($S).invoke(instance)", "run")
                .nextControlFlow("catch ($T e)", INVOCATION_TARGET)
                .addStatement("FAILURE.set(e.getCause())")
                .nextControlFlow("catch ($T | $T e)", ReflectiveOperationException.class, RuntimeException.class)
                .addStatement("FAILURE.set(e)")
                .endControlFlow()
                .build();
    }

    private static MethodSpec awaitComposedMethod() {
        return MethodSpec.methodBuilder("awaitComposed")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(THREAD, "thread")
                .addParameter(TypeName.INT, "port")
                .addCode("""
                        $T client = $T.newBuilder().version($T.Version.HTTP_1_1).build();
                        $T request = $T.newBuilder($T.create("http://" + LOOPBACK + ":" + port + READINESS_PATH))
                                .timeout($T.ofSeconds(5)).GET().build();
                        long deadline = $T.nanoTime() + BOOT_TIMEOUT.toNanos();
                        String last = "no response yet";
                        while ($T.nanoTime() < deadline) {
                            if (!thread.isAlive()) {
                                throw new AssertionError("the application exited during boot", FAILURE.get());
                            }
                            try {
                                int status = client.send(request, $T.BodyHandlers.discarding()).statusCode();
                                if (status != 503) {
                                    return;
                                }
                                last = "status " + status;
                            } catch ($T e) {
                                last = e.toString();
                            } catch (InterruptedException e) {
                                $T.currentThread().interrupt();
                                throw new AssertionError("interrupted while waiting for the application", e);
                            }
                            $T.parkNanos(50_000_000L);
                        }
                        thread.interrupt();
                        throw new AssertionError("the application did not compose within " + BOOT_TIMEOUT
                                + "; last readiness probe: " + last, FAILURE.get());
                        """, HTTP_CLIENT, HTTP_CLIENT, HTTP_CLIENT, HTTP_REQUEST, HTTP_REQUEST, URI, DURATION,
                        SYSTEM, SYSTEM, HTTP_RESPONSE, IO_EXCEPTION, THREAD,
                        ClassName.get("java.util.concurrent.locks", "LockSupport"))
                .build();
    }

    private static MethodSpec applyMigrationsMethod() {
        return MethodSpec.methodBuilder("applyMigrations")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(STRING, "jdbcUrl")
                .addCode("""
                        try ($T connection = $T.getConnection(jdbcUrl, DATABASE_ROLE, DATABASE_ROLE);
                             $T statement = connection.createStatement()) {
                            for (String migration : MIGRATIONS) {
                                for (String sql : statements(h2(read(migration)))) {
                                    statement.execute(sql);
                                }
                            }
                        } catch ($T e) {
                            throw new IllegalStateException("applying the generated migrations to " + jdbcUrl
                                    + " failed", e);
                        }
                        """, CONNECTION, DRIVER_MANAGER, STATEMENT, SQL_EXCEPTION)
                .build();
    }

    private static MethodSpec readMethod(ClassName self) {
        return MethodSpec.methodBuilder("read")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addParameter(STRING, "resource")
                .addCode("""
                        try ($T in = $T.class.getClassLoader().getResourceAsStream(resource)) {
                            if (in == null) {
                                throw new IllegalStateException(resource + " is not on the test classpath; the"
                                        + " generated migrations under src/main/generated/java/db/migration must be"
                                        + " copied to the classpath, as exeris-app-parent's resource entry does");
                            }
                            return new String(in.readAllBytes(), $T.UTF_8);
                        } catch ($T e) {
                            throw new $T("reading " + resource + " failed", e);
                        }
                        """, INPUT_STREAM, self, CHARSETS, IO_EXCEPTION, UNCHECKED_IO)
                .build();
    }

    private static MethodSpec h2Method() {
        return MethodSpec.methodBuilder("h2")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addParameter(STRING, "sql")
                .addStatement("return sql.replace($S, $S)\n.replace($S, $S)",
                        "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE",
                        "PRIMARY KEY DEFAULT gen_random_uuid()", "DEFAULT RANDOM_UUID() PRIMARY KEY")
                .build();
    }

    private static MethodSpec statementsMethod() {
        TypeName list = ParameterizedTypeName.get(ClassName.get(JAVA_UTIL, "List"), STRING);
        return MethodSpec.methodBuilder("statements")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(list)
                .addParameter(STRING, "sql")
                .addCode("""
                        StringBuilder code = new StringBuilder();
                        for (String line : sql.split("\\n")) {
                            if (!line.strip().startsWith("--")) {
                                code.append(line).append('\\n');
                            }
                        }
                        $T<String> statements = new $T<>();
                        for (String statement : code.toString().split(";")) {
                            if (!statement.isBlank()) {
                                statements.add(statement.strip());
                            }
                        }
                        return statements;
                        """, ClassName.get(JAVA_UTIL, "List"), ClassName.get(JAVA_UTIL, "ArrayList"))
                .build();
    }

    private static MethodSpec reserveLoopbackPortMethod() {
        return MethodSpec.methodBuilder("reserveLoopbackPort")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(TypeName.INT)
                .addCode("""
                        try ($T socket = new $T()) {
                            socket.bind(new $T(LOOPBACK, 0));
                            return socket.getLocalPort();
                        } catch ($T e) {
                            throw new $T("unable to reserve a loopback port", e);
                        }
                        """, SERVER_SOCKET, SERVER_SOCKET, INET_SOCKET_ADDRESS, IO_EXCEPTION, UNCHECKED_IO)
                .build();
    }

    private static MethodSpec snapshotMethod() {
        return MethodSpec.methodBuilder("snapshot")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(MAP, STRING, STRING))
                .addStatement("$T<$T, $T> values = new $T<>()", MAP, STRING, STRING, LINKED_HASH_MAP)
                .beginControlFlow("for (String key : PROPERTIES)")
                .addStatement("values.put(key, $T.getProperty(key))", SYSTEM)
                .endControlFlow()
                .addStatement("return values")
                .build();
    }

    private static MethodSpec restoreMethod() {
        return MethodSpec.methodBuilder("restore")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .addParameter(ParameterizedTypeName.get(MAP, STRING, STRING), "values")
                .addCode("""
                        values.forEach((key, value) -> {
                            if (value == null) {
                                $T.clearProperty(key);
                            } else {
                                $T.setProperty(key, value);
                            }
                        });
                        """, SYSTEM, SYSTEM)
                .build();
    }

    private static MethodSpec serveKeysMethod(ClassName tokens) {
        return MethodSpec.methodBuilder("serveKeys")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(HTTP_SERVER)
                .addCode("""
                        byte[] document = $T.jwks().getBytes($T.UTF_8);
                        try {
                            $T server = $T.create(new $T(LOOPBACK, 0), 0);
                            server.createContext("/jwks", exchange -> {
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, document.length);
                                try ($T out = exchange.getResponseBody()) {
                                    out.write(document);
                                }
                            });
                            server.start();
                            return server;
                        } catch ($T e) {
                            throw new $T("unable to serve the token verification keys", e);
                        }
                        """, tokens, CHARSETS, HTTP_SERVER, HTTP_SERVER, INET_SOCKET_ADDRESS, OUTPUT_STREAM,
                        IO_EXCEPTION, UNCHECKED_IO)
                .build();
    }

    // ------------------------------------------------------------------ IntegrationHttp

    private static TypeSpec integrationHttp(String packageName) {
        ClassName self = ClassName.get(packageName, INTEGRATION_HTTP);
        ClassName response = self.nestedClass("Response");
        TypeSpec responseType = TypeSpec.recordBuilder("Response")
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("One response: its status and its body as text.\n")
                .addJavadoc("@param status the status code\n")
                .addJavadoc("@param body   the body, empty when there is none\n")
                .recordConstructor(MethodSpec.constructorBuilder()
                        .addParameter(TypeName.INT, "status")
                        .addParameter(STRING, "body")
                        .build())
                .build();

        return KernelScaffold.publicClass(INTEGRATION_HTTP)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Sends the generated integration tests' requests to the application under test\n")
                .addJavadoc("(ADR-058 Amendment 1).\n")
                .addJavadoc("<p>The client is pinned to HTTP/1.1: on an {@code http://} URI the JDK client otherwise\n")
                .addJavadoc("asks for an h2c upgrade, which the kernel's HTTP driver honours, and the test would no\n")
                .addJavadoc("longer exercise the plain request path.\n")
                .addJavadoc(DO_NOT_EDIT)
                .addType(responseType)
                .addField(privateConstant(HTTP_CLIENT, "CLIENT", CodeBlock.of(
                        "$T.newBuilder().version($T.Version.HTTP_1_1)\n.connectTimeout($T.ofSeconds(5)).build()",
                        HTTP_CLIENT, HTTP_CLIENT, DURATION)))
                .addField(privateConstant(ATOMIC_LONG, "SEQUENCE", CodeBlock.of("new $T()", ATOMIC_LONG)))
                .addField(privateConstant(TypeName.INT, "PAGE_SIZE",
                        CodeBlock.of("$L", ListQuerySupport.MAX_SIZE)))
                .addField(privateConstant(TypeName.INT, "MAX_PAGES", CodeBlock.of("$L", 1000)))
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(sendMethod(response))
                .addMethod(listedMethod())
                .addMethod(idOfMethod())
                .addMethod(jsonStringMethod())
                .addMethod(sequenceMethod())
                .addMethod(uniqueMethod())
                .build();
    }

    private static MethodSpec sendMethod(ClassName response) {
        return MethodSpec.methodBuilder("send")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(response)
                .addParameter(STRING, METHOD_PARAMETER)
                .addParameter(STRING, PATH_PARAMETER)
                .addParameter(STRING, JSON_PARAMETER)
                .addParameter(STRING, BEARER_PARAMETER)
                .addJavadoc("Sends one request to the application under test, booting it first if need be.\n")
                .addJavadoc("@param method the HTTP method\n")
                .addJavadoc("@param path   the path, with its query when it has one\n")
                .addJavadoc("@param json   the JSON body, or {@code null} for none\n")
                .addJavadoc("@param bearer the {@code Authorization} header value, or {@code null} for none\n")
                .addJavadoc("@return the status and the body\n")
                .addCode("""
                        $T.Builder request = $T.newBuilder($T.create($T.baseUri() + path))
                                .timeout($T.ofSeconds(30));
                        if (bearer != null) {
                            request.header("Authorization", bearer);
                        }
                        if (json == null) {
                            request.method(method, $T.BodyPublishers.noBody());
                        } else {
                            request.header("Content-Type", "application/json")
                                    .method(method, $T.BodyPublishers.ofString(json, $T.UTF_8));
                        }
                        try {
                            $T<String> answer = CLIENT.send(request.build(), $T.BodyHandlers.ofString($T.UTF_8));
                            return new Response(answer.statusCode(), answer.body() == null ? "" : answer.body());
                        } catch ($T e) {
                            throw new $T(method + " " + path + " failed", e);
                        } catch (InterruptedException e) {
                            $T.currentThread().interrupt();
                            throw new IllegalStateException("interrupted during " + method + " " + path, e);
                        }
                        """, HTTP_REQUEST, HTTP_REQUEST, URI,
                        ClassName.get(response.packageName(), APPLICATION_UNDER_TEST), DURATION,
                        HTTP_REQUEST, HTTP_REQUEST, CHARSETS, HTTP_RESPONSE, HTTP_RESPONSE, CHARSETS,
                        IO_EXCEPTION, UNCHECKED_IO, THREAD)
                .build();
    }

    private static MethodSpec listedMethod() {
        return MethodSpec.methodBuilder("listed")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(STRING, PATH_PARAMETER)
                .addParameter(STRING, "id")
                .addParameter(STRING, BEARER_PARAMETER)
                .addJavadoc("Reports whether a list route carries a row, walking its pages until the last one.\n")
                .addJavadoc("@param path   the list route\n")
                .addJavadoc("@param id     the row's key\n")
                .addJavadoc("@param bearer the {@code Authorization} header value, or {@code null} for none\n")
                .addJavadoc("@return {@code true} when some page carries the key\n")
                .addJavadoc("@throws IllegalStateException if a page is not {@code 200}\n")
                .addCode("""
                        for (int page = 0; page < MAX_PAGES; page++) {
                            String query = "?page=" + page + "&size=" + PAGE_SIZE;
                            Response response = send("GET", path + query, null, bearer);
                            if (response.status() != 200) {
                                throw new IllegalStateException("GET " + path + " page " + page + " answered "
                                        + response.status() + ": " + response.body());
                            }
                            if (response.body().contains(id)) {
                                return true;
                            }
                            if (response.body().contains("\\"last\\":true")) {
                                return false;
                            }
                        }
                        return false;
                        """)
                .build();
    }

    private static MethodSpec idOfMethod() {
        return MethodSpec.methodBuilder("idOf")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(STRING)
                .addParameter(STRING, "body")
                .addParameter(STRING, "keyField")
                .addJavadoc("Reads the string value of a top-level member of a JSON object.\n")
                .addJavadoc("@param body     the JSON object\n")
                .addJavadoc("@param keyField the member's name\n")
                .addJavadoc("@return the value\n")
                .addJavadoc("@throws IllegalStateException if the object carries no such string member\n")
                .addCode("""
                        String marker = "\\"" + keyField + "\\":\\"";
                        int start = body.indexOf(marker);
                        if (start < 0) {
                            throw new IllegalStateException("no \\"" + keyField + "\\" string member in " + body);
                        }
                        int from = start + marker.length();
                        int end = body.indexOf('"', from);
                        if (end < 0) {
                            throw new IllegalStateException("unterminated \\"" + keyField + "\\" member in " + body);
                        }
                        return body.substring(from, end);
                        """)
                .build();
    }

    private static MethodSpec jsonStringMethod() {
        return MethodSpec.methodBuilder("jsonString")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(STRING)
                .addParameter(STRING, "value")
                .addJavadoc("Quotes a value that holds no character JSON escapes, as a JSON string.\n")
                .addJavadoc("@param value the value\n")
                .addJavadoc("@return the value between double quotes\n")
                .addStatement("return '\"' + value + '\"'")
                .build();
    }

    private static MethodSpec sequenceMethod() {
        return MethodSpec.methodBuilder("sequence")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.LONG)
                .addJavadoc("Returns a number no earlier call in this JVM returned, for a unique column.\n")
                .addJavadoc("@return the next number, from 1\n")
                .addStatement("return SEQUENCE.incrementAndGet()")
                .build();
    }

    private static MethodSpec uniqueMethod() {
        return MethodSpec.methodBuilder("unique")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(STRING)
                .addParameter(TypeName.INT, "length")
                .addJavadoc("Returns a string of {@code length} characters no earlier call in this JVM returned,\n")
                .addJavadoc("for a unique column.\n")
                .addJavadoc("@param length the length\n")
                .addJavadoc("@return the string\n")
                .addCode("""
                        String digits = Long.toString(sequence(), 36);
                        if (digits.length() > length) {
                            throw new IllegalStateException("no unique value of length " + length + " is left");
                        }
                        return "u".repeat(length - digits.length()) + digits;
                        """)
                .build();
    }

    // ------------------------------------------------------------------ IntegrationTokens

    private static TypeSpec integrationTokens() {
        return KernelScaffold.publicClass(INTEGRATION_TOKENS)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Mints the tokens the generated integration tests present, and publishes the key\n")
                .addJavadoc("that verifies them (ADR-058 Amendment 1).\n")
                .addJavadoc("<p>The RSA key pair is made when this class is first used, never stored in source.\n")
                .addJavadoc("A token is a compact RS256 JWT carrying the key id, the issuer and audience the\n")
                .addJavadoc("kernel is configured with, a fixed UUID subject, an expiry an hour ahead and, when\n")
                .addJavadoc("scopes are given, a space-delimited {@code scope} claim.\n")
                .addJavadoc(DO_NOT_EDIT)
                .addField(constant(STRING, "KEY_ID", CodeBlock.of("$S", KEY_ID), "The signing key's id."))
                .addField(constant(STRING, "ISSUER", CodeBlock.of("$S", ISSUER), "The issuer of every token."))
                .addField(constant(STRING, "AUDIENCE", CodeBlock.of("$S", ISSUER), "The audience of every token."))
                .addField(constant(STRING, "SUBJECT", CodeBlock.of("$S", SUBJECT), "The subject of every token."))
                .addField(privateConstant(KEY_PAIR, "KEYS", CodeBlock.of("generate()")))
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(bearerMethod())
                .addMethod(tokenMethod())
                .addMethod(jwksMethod())
                .addMethod(generateMethod())
                .addMethod(encodeMethod())
                .addMethod(unsignedMethod())
                .build();
    }

    private static MethodSpec bearerMethod() {
        return MethodSpec.methodBuilder("bearer")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .varargs()
                .returns(STRING)
                .addParameter(ArrayTypeName.of(STRING), "scopes")
                .addJavadoc("Returns an {@code Authorization} header value carrying a fresh token.\n")
                .addJavadoc("@param scopes the scopes the token carries; none for a token without the claim\n")
                .addJavadoc("@return {@code Bearer <token>}\n")
                .addStatement("return $S + token(scopes)", "Bearer ")
                .build();
    }

    private static MethodSpec tokenMethod() {
        return MethodSpec.methodBuilder("token")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .varargs()
                .returns(STRING)
                .addParameter(ArrayTypeName.of(STRING), "scopes")
                .addJavadoc("Mints a token.\n")
                .addJavadoc("@param scopes the scopes the token carries; none for a token without the claim\n")
                .addJavadoc("@return the compact JWT\n")
                .addCode("""
                        long now = $T.currentTimeMillis() / 1000;
                        String header = "{\\"alg\\":\\"RS256\\",\\"typ\\":\\"JWT\\",\\"kid\\":\\"" + KEY_ID + "\\"}";
                        StringBuilder claims = new StringBuilder()
                                .append("{\\"iss\\":\\"").append(ISSUER)
                                .append("\\",\\"aud\\":\\"").append(AUDIENCE)
                                .append("\\",\\"sub\\":\\"").append(SUBJECT)
                                .append("\\",\\"iat\\":").append(now)
                                .append(",\\"exp\\":").append(now + 3600);
                        if (scopes.length > 0) {
                            claims.append(",\\"scope\\":\\"").append(String.join(" ", scopes)).append('"');
                        }
                        claims.append('}');
                        String signingInput = encode(header.getBytes($T.UTF_8)) + "."
                                + encode(claims.toString().getBytes($T.UTF_8));
                        try {
                            $T signature = $T.getInstance("SHA256withRSA");
                            signature.initSign(KEYS.getPrivate());
                            signature.update(signingInput.getBytes($T.US_ASCII));
                            return signingInput + "." + encode(signature.sign());
                        } catch ($T e) {
                            throw new IllegalStateException("signing a token failed", e);
                        }
                        """, SYSTEM, CHARSETS, CHARSETS, SIGNATURE, SIGNATURE, CHARSETS, GENERAL_SECURITY)
                .build();
    }

    private static MethodSpec jwksMethod() {
        return MethodSpec.methodBuilder("jwks")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(STRING)
                .addJavadoc("Returns the JWKS document publishing the verification key.\n")
                .addJavadoc("@return the document, one RS256 signing key under {@link #KEY_ID}\n")
                .addCode("""
                        $T key = ($T) KEYS.getPublic();
                        return "{\\"keys\\":[{\\"kty\\":\\"RSA\\",\\"kid\\":\\"" + KEY_ID
                                + "\\",\\"alg\\":\\"RS256\\",\\"use\\":\\"sig\\",\\"n\\":\\""
                                + unsigned(key.getModulus())
                                + "\\",\\"e\\":\\"" + unsigned(key.getPublicExponent()) + "\\"}]}";
                        """, RSA_PUBLIC_KEY, RSA_PUBLIC_KEY)
                .build();
    }

    private static MethodSpec generateMethod() {
        return MethodSpec.methodBuilder("generate")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(KEY_PAIR)
                .addCode("""
                        try {
                            $T generator = $T.getInstance("RSA");
                            generator.initialize(2048);
                            return generator.generateKeyPair();
                        } catch ($T e) {
                            throw new IllegalStateException("no RSA key pair generator", e);
                        }
                        """, KEY_PAIR_GENERATOR, KEY_PAIR_GENERATOR, GENERAL_SECURITY)
                .build();
    }

    private static MethodSpec encodeMethod() {
        return MethodSpec.methodBuilder("encode")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addParameter(ArrayTypeName.of(TypeName.BYTE), "bytes")
                .addStatement("return $T.getUrlEncoder().withoutPadding().encodeToString(bytes)", BASE64)
                .build();
    }

    private static MethodSpec unsignedMethod() {
        return MethodSpec.methodBuilder("unsigned")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(STRING)
                .addParameter(BIG_INTEGER, "value")
                .addCode("""
                        byte[] bytes = value.toByteArray();
                        if (bytes.length > 1 && bytes[0] == 0) {
                            bytes = $T.copyOfRange(bytes, 1, bytes.length);
                        }
                        return encode(bytes);
                        """, ARRAYS)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private static FieldSpec constant(TypeName type, String name, CodeBlock value, String javadoc) {
        return FieldSpec.builder(type, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc(javadoc + "\n")
                .initializer(value)
                .build();
    }

    private static FieldSpec privateConstant(TypeName type, String name, CodeBlock value) {
        return FieldSpec.builder(type, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(value)
                .build();
    }

    private static CodeBlock stringArray(List<String> values) {
        CodeBlock.Builder array = CodeBlock.builder().add("{");
        for (int i = 0; i < values.size(); i++) {
            array.add(i == 0 ? "\n$>$>" + STRING_LITERAL : ",\n" + STRING_LITERAL, values.get(i));
        }
        return array.add(values.isEmpty() ? "}" : "$<$<\n}").build();
    }
}
