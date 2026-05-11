package eu.exeris.tooling.codegen.java.kernel;

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
 * Request bodies are parsed via Jackson 3 ({@code tools.jackson.*}): the
 * {@code LoanedBuffer}'s {@code MemorySegment} is copied into a heap
 * {@code byte[]} (size-bounded by {@code Integer.MAX_VALUE}), decoded as
 * UTF-8, then passed to {@code ObjectMapper.readValue(String, Class)} —
 * portable across Jackson 3.0.x overloads.
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
    private static final ClassName MEMORY_SEGMENT =
            ClassName.get("java.lang.foreign", "MemorySegment");
    private static final ClassName VALUE_LAYOUT =
            ClassName.get("java.lang.foreign", "ValueLayout");
    private static final ClassName OBJECT_MAPPER =
            ClassName.get("tools.jackson.databind", "ObjectMapper");
    private static final ClassName JACKSON_EXCEPTION =
            ClassName.get("tools.jackson.core", "JacksonException");
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
                .addField(FieldSpec.builder(OBJECT_MAPPER, "MAPPER",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", OBJECT_MAPPER)
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
        return MethodSpec.methodBuilder("handleGetAll")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .beginControlFlow("try")
                .addStatement("$T entities = service.findAll()", listOfEntity)
                .addStatement("exchange.respond($T.OK, entities)", HTTP_STATUS)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to get all " + entityLower + "s")
                .addStatement("exchange.respond($T.INTERNAL_SERVER_ERROR)", HTTP_STATUS)
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleGetById(String entityLower, TypeName optionalOfEntity) {
        return MethodSpec.methodBuilder("handleGetById")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addStatement("String idStr = extractPathId(exchange)")
                .addStatement("$T id", UUID)
                .beginControlFlow("try")
                .addStatement("id = $T.fromString(idStr)", UUID)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("$T result = service.findById(id)", optionalOfEntity)
                .beginControlFlow("if (result.isPresent())")
                .addStatement("exchange.respond($T.OK, result.get())", HTTP_STATUS)
                .nextControlFlow("else")
                .addStatement("exchange.respond($T.NOT_FOUND)", HTTP_STATUS)
                .endControlFlow()
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to get " + entityLower)
                .addStatement("exchange.respond($T.INTERNAL_SERVER_ERROR)", HTTP_STATUS)
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleCreate(String entityLower, ClassName entityType) {
        return MethodSpec.methodBuilder("handleCreate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addStatement("$T entity", entityType)
                .beginControlFlow("try")
                .addStatement("entity = parseBody(exchange, $T.class)", entityType)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("$T saved = service.save(entity)", entityType)
                .addStatement("exchange.respond($T.CREATED, saved)", HTTP_STATUS)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to create " + entityLower)
                .addStatement("exchange.respond($T.INTERNAL_SERVER_ERROR)", HTTP_STATUS)
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleUpdate(String entityLower, ClassName entityType) {
        return MethodSpec.methodBuilder("handleUpdate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addStatement("String idStr = extractPathId(exchange)")
                .addStatement("$T id", UUID)
                .beginControlFlow("try")
                .addStatement("id = $T.fromString(idStr)", UUID)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .addStatement("$T entity", entityType)
                .beginControlFlow("try")
                .addStatement("entity = parseBody(exchange, $T.class)", entityType)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("$T updated = service.update(id, entity)", entityType)
                .addStatement("exchange.respond($T.OK, updated)", HTTP_STATUS)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to update " + entityLower)
                .addStatement("exchange.respond($T.INTERNAL_SERVER_ERROR)", HTTP_STATUS)
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleDelete(String entityLower) {
        return MethodSpec.methodBuilder("handleDelete")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addStatement("String idStr = extractPathId(exchange)")
                .addStatement("$T id", UUID)
                .beginControlFlow("try")
                .addStatement("id = $T.fromString(idStr)", UUID)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("service.delete(id)")
                .addStatement("exchange.respond($T.NO_CONTENT)", HTTP_STATUS)
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to delete " + entityLower)
                .addStatement("exchange.respond($T.INTERNAL_SERVER_ERROR)", HTTP_STATUS)
                .endControlFlow()
                .build();
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
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class), tVar), "type")
                .addJavadoc("Reads and JSON-decodes the request body.\n")
                .addJavadoc("<p>Per the {@link eu.exeris.kernel.spi.http.HttpRequest} contract,\n")
                .addJavadoc("the {@code LoanedBuffer} body is owned by the transport/codec and\n")
                .addJavadoc("released when the exchange ends — handlers MUST NOT close it.\n")
                .beginControlFlow("if (!exchange.request().hasBody())")
                .addStatement("throw new $T($S)", ILLEGAL_ARGUMENT_EXCEPTION, "Missing body")
                .endControlFlow()
                .addStatement("$T body = exchange.request().body()", LOANED_BUFFER)
                .addStatement("long bodySize = body.size()")
                .beginControlFlow("if (bodySize > $T.MAX_VALUE)", Integer.class)
                .addStatement("throw new $T($S)", ILLEGAL_ARGUMENT_EXCEPTION, "Request body too large")
                .endControlFlow()
                .addStatement("int size = (int) bodySize")
                .addStatement("byte[] bytes = new byte[size]")
                .addStatement("$T.copy(body.segment(), $T.JAVA_BYTE, 0L, bytes, 0, size)",
                        MEMORY_SEGMENT, VALUE_LAYOUT)
                .addStatement("String json = new String(bytes, $T.UTF_8)",
                        ClassName.get("java.nio.charset", "StandardCharsets"))
                .beginControlFlow("try")
                .addStatement("return MAPPER.readValue(json, type)")
                .nextControlFlow("catch ($T e)", JACKSON_EXCEPTION)
                .addStatement("throw new $T($S, e)", ILLEGAL_ARGUMENT_EXCEPTION, "Invalid JSON")
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
