package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;

/**
 * Kernel Handler Generator.
 * <p>
 * Emits a per-entity {@code *Handler} class whose methods match the
 * {@link eu.exeris.kernel.spi.http.HttpHandler} functional interface
 * (each handler method is wired into the router individually by reference).
 * <p>
 * Wired against Open-Core SPI:
 * <ul>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpExchange} — request/response lifecycle</li>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpStatus} — status codes</li>
 *   <li>{@code eu.exeris.kernel.spi.memory.LoanedBuffer} — zero-copy request body</li>
 * </ul>
 * Response bodies are serialised by the exchange's typed-response encoder
 * via {@link eu.exeris.kernel.spi.http.HttpExchange#respond(eu.exeris.kernel.spi.http.HttpStatus, Object)};
 * the handler does not run its own response writer.
 * <p>
 * Request bodies are decoded through the server-side request-body codec SPI
 * (ADR-036): the handler resolves an {@code HttpRequestBodyDecoder} from
 * {@link eu.exeris.kernel.spi.http.HttpKernelProviders#httpRequestBodyDecoderRegistry()}
 * and hands it the {@code LoanedBuffer} directly (no heap {@code byte[] + String}
 * round-trip). No Jackson type is emitted into generated code — the JSON binding
 * lives behind the SPI in the active codec driver (Community Jackson today,
 * alternative bindings tomorrow).
 *
 * @implNote Emission is JavaPoet-based (ADR-015). Output style is owned by
 * JavaPoet's pretty-printer; substring assertions in the E2E suite still hold,
 * compile-gate verifies semantics against real {@code exeris-kernel-spi}.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelHandlerGenerator implements KernelArtifactGenerator {

    private static final ClassName HTTP_EXCHANGE =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpExchange");
    private static final ClassName HTTP_STATUS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName LOANED_BUFFER =
            ClassName.get("eu.exeris.kernel.spi.memory", "LoanedBuffer");
    private static final ClassName HTTP_KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpKernelProviders");
    private static final ClassName HTTP_REQUEST_BODY_DECODER =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequestBodyDecoder");
    private static final ClassName HTTP_REQUEST_BODY_DECODER_REGISTRY =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequestBodyDecoderRegistry");
    private static final ClassName HTTP_REQUEST_DECODING_CONTEXT =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpRequestDecodingContext");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName ILLEGAL_STATE_EXCEPTION =
            ClassName.get("java.lang", "IllegalStateException");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION =
            ClassName.get("java.lang", "IllegalArgumentException");
    private static final ClassName RUNTIME_EXCEPTION =
            ClassName.get("java.lang", "RuntimeException");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".handler";
        String entity = metadata.entityName();
        String className = entity + "Handler";
        String entityLower = toLowerFirst(entity);
        String serviceSimpleName = entity + "Service";

        ClassName entityType = ClassName.get(metadata.packageName(), entity);
        ClassName serviceType = ClassName.get(basePackage + ".service", serviceSimpleName);
        ClassName selfType = ClassName.get(packageName, className);
        TypeName listOfEntity = ParameterizedTypeName.get(LIST, entityType);
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);

        TypeSpec handler = KernelScaffold.publicClass(className)
                .addJavadoc("Generated HTTP Handler for $L.\n", entity)
                .addJavadoc("<p>Source: {@link $T}\n", entityType)
                .addJavadoc("<p>Path: $L\n", metadata.effectivePath())
                .addJavadoc("<p>Each {@code handleX(HttpExchange)} method matches the\n")
                .addJavadoc("{@link eu.exeris.kernel.spi.http.HttpHandler} functional interface\n")
                .addJavadoc("and is wired into the router individually.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(serviceType, "service", Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(serviceType, "service")
                        .addStatement("this.service = service")
                        .build())
                .addMethod(buildHandleGetAll(entityLower, listOfEntity))
                .addMethod(buildHandleGetById(entityLower, optionalOfEntity))
                .addMethod(buildHandleCreate(entityLower, entityType))
                .addMethod(buildHandleUpdate(entityLower, entityType))
                .addMethod(buildHandleDelete(entityLower))
                .addMethod(buildExtractPathId())
                .addMethod(buildParseBody())
                .build();

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, handler), ArtifactType.CONTROLLER);
    }

    private MethodSpec buildHandleGetAll(String entityLower, TypeName listOfEntity) {
        MethodSpec.Builder method = crudHandler("handleGetAll")
                .beginControlFlow("try")
                .addStatement("$T entities = service.findAll()", listOfEntity)
                .addStatement("exchange.respond($T.OK, entities)", HTTP_STATUS);
        return appendServerErrorCatch(method, "Failed to get all " + entityLower + "s").build();
    }

    private MethodSpec buildHandleGetById(String entityLower, TypeName optionalOfEntity) {
        MethodSpec.Builder method = crudHandler("handleGetById");
        appendPathIdGuard(method);
        method.beginControlFlow("try")
                .addStatement("$T result = service.findById(id)", optionalOfEntity)
                .beginControlFlow("if (result.isPresent())")
                .addStatement("exchange.respond($T.OK, result.get())", HTTP_STATUS)
                .nextControlFlow("else")
                .addStatement("exchange.respond($T.NOT_FOUND)", HTTP_STATUS)
                .endControlFlow();
        return appendServerErrorCatch(method, "Failed to get " + entityLower).build();
    }

    private MethodSpec buildHandleCreate(String entityLower, ClassName entityType) {
        MethodSpec.Builder method = crudHandler("handleCreate");
        appendBodyParseGuard(method, entityType);
        method.beginControlFlow("try")
                .addStatement("$T saved = service.save(entity)", entityType)
                .addStatement("exchange.respond($T.CREATED, saved)", HTTP_STATUS);
        return appendServerErrorCatch(method, "Failed to create " + entityLower).build();
    }

    private MethodSpec buildHandleUpdate(String entityLower, ClassName entityType) {
        MethodSpec.Builder method = crudHandler("handleUpdate");
        appendPathIdGuard(method);
        appendBodyParseGuard(method, entityType);
        method.beginControlFlow("try")
                .addStatement("$T updated = service.update(id, entity)", entityType)
                .addStatement("exchange.respond($T.OK, updated)", HTTP_STATUS);
        return appendServerErrorCatch(method, "Failed to update " + entityLower).build();
    }

    private MethodSpec buildHandleDelete(String entityLower) {
        MethodSpec.Builder method = crudHandler("handleDelete");
        appendPathIdGuard(method);
        method.beginControlFlow("try")
                .addStatement("service.delete(id)")
                .addStatement("exchange.respond($T.NO_CONTENT)", HTTP_STATUS);
        return appendServerErrorCatch(method, "Failed to delete " + entityLower).build();
    }

    /** A {@code public void handle*(HttpExchange exchange)} skeleton — the shared
     *  signature of every CRUD handler. */
    private static MethodSpec.Builder crudHandler(String name) {
        return MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_EXCHANGE, "exchange");
    }

    /** Emits the shared "parse {@code id} from the path or 400" guard: declares a
     *  {@code UUID id} and parses it, responding {@code BAD_REQUEST} and returning
     *  on a malformed value. Leaves {@code id} in scope for the caller. */
    private static void appendPathIdGuard(MethodSpec.Builder method) {
        method.addStatement("String idStr = extractPathId(exchange)")
                .addStatement("$T id", UUID)
                .beginControlFlow("try")
                .addStatement("id = $T.fromString(idStr)", UUID)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow();
    }

    /** Emits the shared "decode the request body into {@code entity} or 400" guard.
     *  Leaves {@code entity} in scope for the caller. */
    private static void appendBodyParseGuard(MethodSpec.Builder method, ClassName entityType) {
        method.addStatement("$T entity", entityType)
                .beginControlFlow("try")
                .addStatement("entity = parseBody(exchange, $T.class)", entityType)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow();
    }

    /** Emits the shared "catch RuntimeException → log + 500" tail that closes the
     *  service-call {@code try} block of every CRUD handler. */
    private static MethodSpec.Builder appendServerErrorCatch(MethodSpec.Builder method, String failMessage) {
        return method.nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, e)", failMessage)
                .addStatement("exchange.respond($T.INTERNAL_SERVER_ERROR)", HTTP_STATUS)
                .endControlFlow();
    }

    private MethodSpec buildExtractPathId() {
        return MethodSpec.methodBuilder("extractPathId")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addStatement("String path = exchange.request().path()")
                .addStatement("int q = path.indexOf('?')")
                .beginControlFlow("if (q >= 0)")
                .addStatement("path = path.substring(0, q)")
                .endControlFlow()
                .addStatement("int lastSlash = path.lastIndexOf('/')")
                .addStatement("return lastSlash >= 0 ? path.substring(lastSlash + 1) : path")
                .build();
    }

    private MethodSpec buildParseBody() {
        TypeVariableName tVar = TypeVariableName.get("T");
        return MethodSpec.methodBuilder("parseBody")
                .addModifiers(Modifier.PRIVATE)
                .addTypeVariable(tVar)
                .returns(tVar)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), tVar), "type")
                .addJavadoc("Decodes the request body into {@code type} via the server-side\n")
                .addJavadoc("request-body codec SPI (ADR-036).\n")
                .addJavadoc("<p>Resolves an {@link eu.exeris.kernel.spi.http.HttpRequestBodyDecoder}\n")
                .addJavadoc("from the kernel-bound\n")
                .addJavadoc("{@link eu.exeris.kernel.spi.http.HttpRequestBodyDecoderRegistry} and hands it\n")
                .addJavadoc("the {@code LoanedBuffer} directly. Per the\n")
                .addJavadoc("{@link eu.exeris.kernel.spi.http.HttpRequest} contract, the body is owned by\n")
                .addJavadoc("the transport/codec and released when the exchange ends — neither this\n")
                .addJavadoc("method nor the decoder closes it.\n")
                .addJavadoc("<p>Status mapping is the handler's concern, not the SPI's (ADR-036 §2):\n")
                .addJavadoc("a decode failure — or any failure resolving/constructing the decode —\n")
                .addJavadoc("surfaces as {@link IllegalArgumentException} (the call sites map it to\n")
                .addJavadoc("{@code 400 BAD_REQUEST}); an unbound registry or unregistered decoder\n")
                .addJavadoc("surfaces as {@link IllegalStateException} (a server-side configuration\n")
                .addJavadoc("error → {@code 5xx}) and is re-thrown unchanged, never downgraded to 400.\n")
                .beginControlFlow("if (!exchange.request().hasBody())")
                .addStatement("throw new $T($S)", ILLEGAL_ARGUMENT_EXCEPTION, "Missing body")
                .endControlFlow()
                .addStatement("$T body = exchange.request().body()", LOANED_BUFFER)
                .addStatement("String contentType = exchange.request().firstHeader($S).orElse(null)",
                        "content-type")
                // Decoder resolution, context construction, and decode all run inside
                // one try so a RuntimeException from ANY of them (e.g. registry.resolve
                // on a hostile content-type, or the allocator) maps to BAD_REQUEST at
                // the call site rather than escaping parseBody unhandled. The
                // IllegalStateException catch re-throws unchanged so the intentional
                // 5xx mappings (unbound registry, unregistered decoder) survive per
                // ADR-036 §2 — they must NOT be downgraded to 400.
                .beginControlFlow("try")
                .addStatement("$T registry = $T.httpRequestBodyDecoderRegistry()\n"
                                + ".orElseThrow(() -> new $T($S))",
                        HTTP_REQUEST_BODY_DECODER_REGISTRY, HTTP_KERNEL_PROVIDERS,
                        ILLEGAL_STATE_EXCEPTION,
                        "No HttpRequestBodyDecoderRegistry is bound; cannot decode the request body")
                .addStatement("$T decoder = registry.resolve(type, contentType)", HTTP_REQUEST_BODY_DECODER)
                .beginControlFlow("if (decoder == null)")
                .addStatement("throw new $T($S + type.getName() + $S + (contentType != null ? contentType : $S))",
                        ILLEGAL_STATE_EXCEPTION,
                        "No request body decoder registered for target type ", " and content-type ", "(absent)")
                .endControlFlow()
                .addStatement("$T context = new $T(exchange.request().method(), "
                                + "exchange.request().path(), exchange.request().headers(), "
                                + "$T.MEMORY_ALLOCATOR.get())",
                        HTTP_REQUEST_DECODING_CONTEXT, HTTP_REQUEST_DECODING_CONTEXT, KERNEL_PROVIDERS)
                .addStatement("return ($T) decoder.decode(body, type, context)", tVar)
                .nextControlFlow("catch ($T e)", ILLEGAL_STATE_EXCEPTION)
                .addStatement("throw e")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("throw new $T($S, e)", ILLEGAL_ARGUMENT_EXCEPTION, "Invalid request body")
                .endControlFlow()
                .build();
    }

    private String toLowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.CONTROLLER;
    }
}
