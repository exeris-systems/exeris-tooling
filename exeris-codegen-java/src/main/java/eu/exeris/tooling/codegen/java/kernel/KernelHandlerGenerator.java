package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;

/**
 * Kernel Handler Generator.
 * <p>
 * Generates HTTP handlers for Exeris Kernel runtime (HTTP/3).
 * Handlers receive requests from Http3ServerExchange and delegate to services.
 * <p>
 * Phase 1 of ADR-015: emission is JavaPoet-based. Output style is owned by
 * JavaPoet's pretty-printer; substring assertions in the E2E suite still hold,
 * compile-gate verifies semantics.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelHandlerGenerator implements KernelArtifactGenerator {

    private static final ClassName HTTP3_EXCHANGE =
            ClassName.get("eu.exeris.kernel.transport.http3.server", "Http3ServerExchange");
    private static final ClassName OBJECT_MAPPER =
            ClassName.get("com.fasterxml.jackson.databind", "ObjectMapper");
    private static final ClassName JAVA_TIME_MODULE =
            ClassName.get("com.fasterxml.jackson.datatype.jsr310", "JavaTimeModule");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName STANDARD_CHARSETS =
            ClassName.get("java.nio.charset", "StandardCharsets");
    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION =
            ClassName.get("java.lang", "IllegalArgumentException");
    private static final ClassName EXCEPTION = ClassName.get("java.lang", "Exception");

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

        TypeSpec handler = KernelScaffold.publicClass(className)
                .addJavadoc("Generated HTTP Handler for $L.\n", entity)
                .addJavadoc("<p>Source: {@link $T}\n", entityType)
                .addJavadoc("<p>Path: $L\n", metadata.effectivePath())
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(OBJECT_MAPPER, "MAPPER",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("createMapper()")
                        .build())
                .addField(FieldSpec.builder(serviceType, "service", Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(serviceType, "service")
                        .addStatement("this.service = service")
                        .build())
                .addMethod(buildHandleGetAll(entityLower, listOfEntity))
                .addMethod(buildHandleGetById(entityLower))
                .addMethod(buildHandleCreate(entityLower, entityType))
                .addMethod(buildHandleUpdate(entityLower, entityType))
                .addMethod(buildHandleDelete(entityLower))
                .addMethod(buildExtractPathId())
                .addMethod(buildReadBody())
                .addMethod(buildSendJson())
                .addMethod(buildSendError())
                .addMethod(buildCreateMapper())
                .build();

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, handler), ArtifactType.CONTROLLER);
    }

    private MethodSpec buildHandleGetAll(String entityLower, TypeName listOfEntity) {
        return MethodSpec.methodBuilder("handleGetAll")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addException(EXCEPTION)
                .addStatement("LOG.debug($S)", "GET all " + entityLower + "s")
                .beginControlFlow("try")
                .addStatement("$T entities = service.findAll()", listOfEntity)
                .addStatement("sendJson(exchange, 200, entities)")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to get all " + entityLower + "s")
                .addStatement("sendError(exchange, 500, $S)", "Internal error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleGetById(String entityLower) {
        return MethodSpec.methodBuilder("handleGetById")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addException(EXCEPTION)
                .addStatement("String idStr = extractPathId(exchange)")
                .addStatement("LOG.debug($S, idStr)", "GET " + entityLower + " by id: {}")
                .beginControlFlow("try")
                .addStatement("$T id = $T.fromString(idStr)", UUID, UUID)
                .addCode(CodeBlock.builder()
                        .add("service.findById(id).ifPresentOrElse(\n")
                        .indent()
                        .add("entity -> sendJson(exchange, 200, entity),\n")
                        .add("() -> sendError(exchange, 404, $S)\n", "Not found")
                        .unindent()
                        .addStatement(")")
                        .build())
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("sendError(exchange, 400, $S)", "Invalid UUID")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to get " + entityLower)
                .addStatement("sendError(exchange, 500, $S)", "Internal error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleCreate(String entityLower, ClassName entityType) {
        return MethodSpec.methodBuilder("handleCreate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addException(EXCEPTION)
                .addStatement("LOG.debug($S)", "POST create " + entityLower)
                .beginControlFlow("try")
                .addStatement("String body = readBody(exchange)")
                .addStatement("$T entity = MAPPER.readValue(body, $T.class)", entityType, entityType)
                .addStatement("$T saved = service.save(entity)", entityType)
                .addStatement("sendJson(exchange, 201, saved)")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to create " + entityLower)
                .addStatement("sendError(exchange, 500, $S)", "Internal error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleUpdate(String entityLower, ClassName entityType) {
        return MethodSpec.methodBuilder("handleUpdate")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addException(EXCEPTION)
                .addStatement("String idStr = extractPathId(exchange)")
                .addStatement("LOG.debug($S, idStr)", "PUT update " + entityLower + " id: {}")
                .beginControlFlow("try")
                .addStatement("$T id = $T.fromString(idStr)", UUID, UUID)
                .addStatement("String body = readBody(exchange)")
                .addStatement("$T entity = MAPPER.readValue(body, $T.class)", entityType, entityType)
                .addStatement("$T updated = service.update(id, entity)", entityType)
                .addStatement("sendJson(exchange, 200, updated)")
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("sendError(exchange, 400, $S)", "Invalid UUID")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to update " + entityLower)
                .addStatement("sendError(exchange, 500, $S)", "Internal error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildHandleDelete(String entityLower) {
        return MethodSpec.methodBuilder("handleDelete")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addException(EXCEPTION)
                .addStatement("String idStr = extractPathId(exchange)")
                .addStatement("LOG.debug($S, idStr)", "DELETE " + entityLower + " id: {}")
                .beginControlFlow("try")
                .addStatement("$T id = $T.fromString(idStr)", UUID, UUID)
                .addStatement("service.delete(id)")
                .addStatement("exchange.response().sendHeaders(204, null)")
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("sendError(exchange, 400, $S)", "Invalid UUID")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to delete " + entityLower)
                .addStatement("sendError(exchange, 500, $S)", "Internal error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildExtractPathId() {
        return MethodSpec.methodBuilder("extractPathId")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addStatement("String path = exchange.request().uri().getPath()")
                .addStatement("int lastSlash = path.lastIndexOf('/')")
                .addStatement("return lastSlash >= 0 ? path.substring(lastSlash + 1) : path")
                .build();
    }

    private MethodSpec buildReadBody() {
        return MethodSpec.methodBuilder("readBody")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addException(EXCEPTION)
                .addStatement("byte[] bytes = exchange.request().bodyAsBytes()")
                .addStatement("return new String(bytes, $T.UTF_8)", STANDARD_CHARSETS)
                .build();
    }

    private MethodSpec buildSendJson() {
        return MethodSpec.methodBuilder("sendJson")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.VOID)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addParameter(TypeName.INT, "status")
                .addParameter(Object.class, "data")
                .beginControlFlow("try")
                .addStatement("String json = MAPPER.writeValueAsString(data)")
                .addStatement("exchange.response().sendHeaders(status, null)")
                .addStatement("exchange.response().sendText(json)")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to serialize response")
                .addStatement("sendError(exchange, 500, $S)", "Serialization error")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildSendError() {
        return MethodSpec.methodBuilder("sendError")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.VOID)
                .addParameter(HTTP3_EXCHANGE, "exchange")
                .addParameter(TypeName.INT, "status")
                .addParameter(String.class, "message")
                .beginControlFlow("try")
                .addStatement("String json = MAPPER.writeValueAsString($T.of($S, message))", MAP, "error")
                .addStatement("exchange.response().sendHeaders(status, null)")
                .addStatement("exchange.response().sendText(json)")
                .nextControlFlow("catch ($T e)", EXCEPTION)
                .addStatement("LOG.error($S, e)", "Failed to send error response")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildCreateMapper() {
        return MethodSpec.methodBuilder("createMapper")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(OBJECT_MAPPER)
                .addStatement("$T mapper = new $T()", OBJECT_MAPPER, OBJECT_MAPPER)
                .addStatement("mapper.registerModule(new $T())", JAVA_TIME_MODULE)
                .addStatement("return mapper")
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
