package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;

/**
 * Emits the one piece of test infrastructure the generated tests share:
 * {@code RecordingHttpExchange}, a hand-rolled {@link eu.exeris.kernel.spi.http.HttpExchange}
 * double that captures what a handler responded.
 *
 * <p><b>Why hand-rolled and not a mock (ADR-058).</b> Tooling emits no {@code pom.xml}, so every
 * import a generated test carries becomes a hard requirement on the consumer's build. The
 * dependency contract is therefore JUnit 5 + AssertJ and nothing else — a mocking framework would
 * make {@code -Dexeris.tests} impose a third library on every downstream project. {@code
 * HttpExchange} has two abstract methods, so the double costs less than the dependency would.
 *
 * <p>The four {@code respond(...)} overloads are all overridden, including the interface's
 * <em>default</em> ones. That is deliberate: the defaults funnel through the codec path to build an
 * {@code HttpResponse}, which needs an encoder bound at runtime. Overriding them keeps the double
 * infrastructure-free and lets a test assert on the status and the response object the handler
 * actually passed, rather than on an encoded buffer.
 *
 * <p>Project-wide, like {@link KernelApplicationGenerator}: one copy per application under
 * {@code <basePackage>.testsupport}, not one per entity.
 *
 * @since 0.7.0
 */
public final class KernelTestSupportGenerator {

    /** Sub-package the emitted support types live in, relative to the project base package. */
    public static final String TEST_SUPPORT_PACKAGE = "testsupport";

    /** Simple name of the emitted {@code HttpExchange} double. */
    public static final String RECORDING_EXCHANGE = "RecordingHttpExchange";

    private static final ClassName HTTP_EXCHANGE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpExchange");
    private static final ClassName HTTP_REQUEST =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequest");
    private static final ClassName HTTP_RESPONSE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpResponse");
    private static final ClassName HTTP_STATUS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName HTTP_METHOD =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpMethod");
    private static final ClassName HTTP_VERSION =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpVersion");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");

    /**
     * @param basePackage the project base package; the support type lands in
     *                    {@code <basePackage>.testsupport}
     * @return the single emitted file; never {@code null}
     */
    public GeneratedFile generate(String basePackage) {
        String packageName = supportPackage(basePackage);
        TypeName stringMap = ParameterizedTypeName.get(MAP,
                ClassName.get(String.class), ClassName.get(String.class));

        TypeSpec.Builder type = KernelScaffold.publicClass(RECORDING_EXCHANGE)
                .addModifiers(Modifier.FINAL)
                .addSuperinterface(HTTP_EXCHANGE)
                .addJavadoc("An {@link $T} double that records what a handler responded.\n", HTTP_EXCHANGE)
                .addJavadoc("<p>Every {@code respond(...)} overload is overridden, the interface's\n")
                .addJavadoc("default ones included: those build an {@link $T} through the codec\n", HTTP_RESPONSE)
                .addJavadoc("path, which needs an encoder bound at runtime. Recording the status and\n")
                .addJavadoc("the response object directly keeps this double free of kernel runtime\n")
                .addJavadoc("state, so a handler test needs no bootstrap.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(HTTP_REQUEST, "request", Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(stringMap, "pathParams", Modifier.PRIVATE, Modifier.FINAL)
                        .initializer("new $T<>()", LINKED_HASH_MAP).build())
                .addField(FieldSpec.builder(HTTP_STATUS, "status", Modifier.PRIVATE).build())
                .addField(FieldSpec.builder(ClassName.get(Object.class), "body", Modifier.PRIVATE).build());

        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_REQUEST, "request")
                .addStatement("this.request = request")
                .build());

        type.addMethod(factory("get", "GET"));
        type.addMethod(factory("delete", "DELETE"));
        // POST/PUT with no body. A handler's body guard rejects before anything reads the
        // LoanedBuffer, so these need no buffer double — see KernelHandlerTestGenerator.
        type.addMethod(factory("post", "POST"));
        type.addMethod(factory("put", "PUT"));

        type.addMethod(MethodSpec.methodBuilder("withPathParam")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(supportPackage(basePackage), RECORDING_EXCHANGE))
                .addParameter(String.class, "name")
                .addParameter(String.class, "value")
                .addJavadoc("Binds one path parameter, the way the router binds {@code {id}}.\n")
                .addStatement("this.pathParams.put(name, value)")
                .addStatement("return this")
                .build());

        type.addMethod(MethodSpec.methodBuilder("status")
                .addModifiers(Modifier.PUBLIC)
                .returns(HTTP_STATUS)
                .addJavadoc("The status the handler responded with, or {@code null} if it never responded.\n")
                .addStatement("return status")
                .build());

        type.addMethod(MethodSpec.methodBuilder("body")
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addJavadoc("The response object the handler passed, or {@code null} for a bodyless response.\n")
                .addStatement("return body")
                .build());

        type.addMethod(MethodSpec.methodBuilder("request")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(HTTP_REQUEST)
                .addStatement("return request")
                .build());

        type.addMethod(MethodSpec.methodBuilder("pathParams")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(stringMap)
                .addStatement("return pathParams")
                .build());

        type.addMethod(MethodSpec.methodBuilder("respond")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_RESPONSE, "response")
                .addStatement("this.status = response.status()")
                .addStatement("this.body = null")
                .build());

        type.addMethod(MethodSpec.methodBuilder("respond")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_STATUS, "status")
                .addStatement("this.status = status")
                .addStatement("this.body = null")
                .build());

        type.addMethod(MethodSpec.methodBuilder("respond")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_STATUS, "status")
                .addParameter(Object.class, "body")
                .addStatement("this.status = status")
                .addStatement("this.body = body")
                .build());

        return new GeneratedFile(packageName, RECORDING_EXCHANGE,
                KernelScaffold.render(packageName, type.build()), ArtifactType.TEST);
    }

    /** {@code <basePackage>.testsupport} — where the shared doubles live. */
    public static String supportPackage(String basePackage) {
        return basePackage + "." + TEST_SUPPORT_PACKAGE;
    }

    /** A {@code static RecordingHttpExchange <verb>(String path)} factory for one HTTP method. */
    private MethodSpec factory(String name, String httpMethod) {
        return MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.bestGuess(RECORDING_EXCHANGE))
                .addParameter(String.class, "path")
                .addJavadoc("A bodyless {@code $L} exchange for {@code path}.\n", httpMethod)
                .addStatement("return new $L($T.noBody($T.$L, path, $T.HTTP_1_1, $T.of()))",
                        RECORDING_EXCHANGE, HTTP_REQUEST, HTTP_METHOD, httpMethod, HTTP_VERSION, LIST)
                .build();
    }
}
