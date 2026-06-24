package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import javax.lang.model.element.Modifier;
import java.util.List;

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
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
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

        TypeSpec.Builder handlerBuilder = KernelScaffold.publicClass(className)
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
                .addMethod(buildHandleCreate(entityLower, entityType, metadata.fields()))
                .addMethod(buildHandleUpdate(entityLower, entityType, metadata.fields()))
                .addMethod(buildHandleDelete(entityLower));

        // T1: serve @Action methods. Each action gets a handler that loads the
        // aggregate, decodes its @ActionParam body (when any), invokes the actual
        // entity method (effectiveMethodName), persists, and responds with the
        // updated aggregate. Routed by KernelApplicationGenerator at
        // {basePath}/{id}/actions/{kebab(name)}.
        //
        // ADR-044 Slice 2: a @Action(streaming) action is NOT served here. It is
        // emitted as a kernel HttpStreamHandler by KernelActionStreamHandlerGenerator
        // and bound to a streamRoute(...), so a respond-once handle<Action> method
        // for it would be dead (unrouted) code — skip it.
        boolean hasRespondOnceAction = false;
        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                if (action.streaming()) {
                    continue;
                }
                hasRespondOnceAction = true;
                if (action.hasParams()) {
                    handlerBuilder.addType(buildActionRequestRecord(action));
                }
                handlerBuilder.addMethod(
                        buildActionHandler(action, entityType, optionalOfEntity, selfType, entityLower));
            }
        }

        handlerBuilder.addMethod(buildExtractPathId());
        // extractActionPathId is only referenced by respond-once action handlers; an
        // entity whose only actions stream would otherwise carry it unused.
        if (hasRespondOnceAction) {
            handlerBuilder.addMethod(buildExtractActionPathId());
        }
        handlerBuilder.addMethod(buildParseBody());

        TypeSpec handler = handlerBuilder.build();

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

    private MethodSpec buildHandleCreate(String entityLower, ClassName entityType, List<FieldMetadata> fields) {
        MethodSpec.Builder method = crudHandler("handleCreate");
        appendBodyParseGuard(method, entityType);
        appendValidationGuard(method, fields);
        method.beginControlFlow("try")
                .addStatement("$T saved = service.save(entity)", entityType)
                .addStatement("exchange.respond($T.CREATED, saved)", HTTP_STATUS);
        return appendServerErrorCatch(method, "Failed to create " + entityLower).build();
    }

    private MethodSpec buildHandleUpdate(String entityLower, ClassName entityType, List<FieldMetadata> fields) {
        MethodSpec.Builder method = crudHandler("handleUpdate");
        appendPathIdGuard(method);
        appendBodyParseGuard(method, entityType);
        appendValidationGuard(method, fields);
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

    /** Emits the per-action handler: parse {@code id} from the action path, decode the
     *  {@code @ActionParam} body (when any) into the action request record, load the
     *  aggregate (404 if absent), invoke the actual entity method
     *  ({@link ActionMetadata#effectiveMethodName()}), persist, and respond with the
     *  updated aggregate. The action method's return value (if any) is invoked as a
     *  statement and not surfaced in v1 — the response carries the updated state.
     *
     *  <p>v1 limitation (tracked, T1 follow-up): a domain exception thrown by the entity
     *  method surfaces as 500 via {@link #appendServerErrorCatch}, not a 4xx — the handler
     *  cannot tell a domain rejection (e.g. "already cancelled") apart from an
     *  infrastructure failure. The generated method carries a Javadoc note to that effect
     *  so downstream readers know why domain exceptions are not mapped to 4xx yet.
     *
     *  <p>The field-level {@code @Validation} guard (T10) is intentionally NOT applied here:
     *  an action decodes its own {@code @ActionParam} record and invokes an entity method,
     *  it does not accept the field-shaped create/update body those rules describe. */
    private MethodSpec buildActionHandler(ActionMetadata action, ClassName entityType,
                                          TypeName optionalOfEntity, ClassName selfType,
                                          String entityLower) {
        MethodSpec.Builder method = crudHandler("handle" + NameCasing.pascal(action.name()));
        method.addJavadoc("Serves the {@code $L} action. NOTE (v1): a domain exception from "
                + "the entity method surfaces as 500, not 4xx.\n", action.name());

        // id from {basePath}/{id}/actions/{name}
        method.addStatement("String idStr = extractActionPathId(exchange)")
                .addStatement("$T id", UUID)
                .beginControlFlow("try")
                .addStatement("id = $T.fromString(idStr)", UUID)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow();

        if (action.hasParams()) {
            ClassName requestType = selfType.nestedClass(actionRequestName(action));
            method.addStatement("$T request", requestType)
                    .beginControlFlow("try")
                    .addStatement("request = parseBody(exchange, $T.class)", requestType)
                    .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                    .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                    .addStatement("return")
                    .endControlFlow();
        }

        method.beginControlFlow("try")
                .addStatement("$T found = service.findById(id)", optionalOfEntity)
                .beginControlFlow("if (found.isEmpty())")
                .addStatement("exchange.respond($T.NOT_FOUND)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .addStatement("$T entity = found.get()", entityType);

        if (action.hasParams()) {
            String args = action.params().stream()
                    .map(p -> "request." + p.name() + "()")
                    .collect(java.util.stream.Collectors.joining(", "));
            method.addStatement("entity.$L($L)", action.effectiveMethodName(), args);
        } else {
            method.addStatement("entity.$L()", action.effectiveMethodName());
        }

        method.addStatement("$T updated = service.update(id, entity)", entityType)
                .addStatement("exchange.respond($T.OK, updated)", HTTP_STATUS);
        return appendServerErrorCatch(method,
                "Failed to execute action " + action.name() + " on " + entityLower).build();
    }

    /** Emits the per-action request record (canonical constructor = {@code @ActionParam}
     *  components, in declaration order). Decoded by {@code parseBody} via the ADR-036
     *  codec SPI, exactly like the CRUD body. */
    private TypeSpec buildActionRequestRecord(ActionMetadata action) {
        MethodSpec.Builder canonical = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for (ActionParamMetadata p : action.params()) {
            canonical.addParameter(ParameterSpec.builder(typeNameOf(p.type()), p.name()).build());
        }
        return TypeSpec.recordBuilder(actionRequestName(action))
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Request body for the {@code $L} action.\n", action.name())
                .recordConstructor(canonical.build())
                .build();
    }

    /** Extracts the entity {@code id} from an action path
     *  {@code {basePath}/{id}/actions/{name}} — the segment immediately before
     *  {@code /actions/} (distinct from {@link #buildExtractPathId}, which takes the
     *  last segment and would return the action name here). */
    private MethodSpec buildExtractActionPathId() {
        return MethodSpec.methodBuilder("extractActionPathId")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addParameter(HTTP_EXCHANGE, "exchange")
                .addStatement("String path = exchange.request().path()")
                .addStatement("int q = path.indexOf('?')")
                .beginControlFlow("if (q >= 0)")
                .addStatement("path = path.substring(0, q)")
                .endControlFlow()
                .addStatement("int actionsIdx = path.indexOf($S)", "/actions/")
                .beginControlFlow("if (actionsIdx < 0)")
                .addComment("not an action path — no id to extract (yields 400 on parse)")
                .addStatement("return $S", "")
                .endControlFlow()
                .addStatement("path = path.substring(0, actionsIdx)")
                .addStatement("int lastSlash = path.lastIndexOf('/')")
                .addStatement("return lastSlash >= 0 ? path.substring(lastSlash + 1) : path")
                .build();
    }

    private static String actionRequestName(ActionMetadata action) {
        return NameCasing.pascal(action.name()) + "Request";
    }

    /** Maps a processor-recorded param type (a FQN from {@code TypeMirror.toString()},
     *  or a primitive keyword) to a JavaPoet {@link TypeName}. Parameterized types fall
     *  back to their raw type (the body decoder binds structurally). */
    private static TypeName typeNameOf(String type) {
        return switch (type) {
            case "boolean" -> TypeName.BOOLEAN;
            case "byte" -> TypeName.BYTE;
            case "short" -> TypeName.SHORT;
            case "int" -> TypeName.INT;
            case "long" -> TypeName.LONG;
            case "char" -> TypeName.CHAR;
            case "float" -> TypeName.FLOAT;
            case "double" -> TypeName.DOUBLE;
            default -> {
                int lt = type.indexOf('<');
                yield ClassName.bestGuess(lt >= 0 ? type.substring(0, lt) : type);
            }
        };
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

    /** T10 — server-side {@code @Validation}. After the body decodes into {@code entity},
     *  reject with 400 (before persisting) if a field violates a metadata rule, restoring
     *  parity with the client Zod schema, which enforces the same rules. Only rules that
     *  are type-safe to emit are checked: {@code required} → not-null on reference types;
     *  {@code minLength}/{@code maxLength}/{@code pattern} on String; {@code min}/{@code max}
     *  on numeric (BigDecimal via {@code compareTo}, other numerics via operators).
     *  Anything else is skipped (no check emitted). */
    private static void appendValidationGuard(MethodSpec.Builder method, List<FieldMetadata> fields) {
        for (FieldMetadata f : fields) {
            boolean nullCheck = f.required() && !isPrimitive(f.type());
            boolean strChecks = isStringType(f.type())
                    && (f.minLength() != null || f.maxLength() != null || f.pattern() != null);
            boolean numChecks = (f.min() != null || f.max() != null) && isNumeric(f.type());
            if (!nullCheck && !strChecks && !numChecks) {
                continue;
            }

            // Read the value once into a local (avoids re-invoking the getter per check).
            String v = f.name();
            method.addStatement("var $L = entity.get$L()", v, NameCasing.pascal(f.name()));

            if (nullCheck) {
                reject400(method, v + " == null");
            }
            if (strChecks) {
                if (f.minLength() != null) {
                    reject400(method, v + " != null && " + v + ".length() < " + f.minLength());
                }
                if (f.maxLength() != null) {
                    reject400(method, v + " != null && " + v + ".length() > " + f.maxLength());
                }
                if (f.pattern() != null) {
                    method.beginControlFlow("if ($L != null && !$L.matches($S))", v, v, f.pattern())
                            .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                            .addStatement("return")
                            .endControlFlow();
                }
            }
            if (numChecks && f.min() != null) {
                appendNumericBound(method, f.type(), v, "<", f.min());
            }
            if (numChecks && f.max() != null) {
                appendNumericBound(method, f.type(), v, ">", f.max());
            }
        }
    }

    private static boolean isNumeric(String type) {
        return isBigDecimal(type) || isPrimitiveNumeric(type) || isBoxedNumeric(type);
    }

    private static void reject400(MethodSpec.Builder method, String condition) {
        method.beginControlFlow("if ($L)", condition)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow();
    }

    /** Numeric bound check; {@code op} is {@code "<"} for min, {@code ">"} for max.
     *  BigDecimal compares via {@code compareTo}; primitives compare directly; boxed
     *  numerics get a null guard; non-numeric field types emit nothing. */
    private static void appendNumericBound(MethodSpec.Builder method, String type, String expr, String op, long bound) {
        if (isBigDecimal(type)) {
            method.beginControlFlow("if ($L != null && $L.compareTo($T.valueOf($L)) $L 0)",
                            expr, expr, BIG_DECIMAL, bound + "L", op)
                    .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                    .addStatement("return")
                    .endControlFlow();
        } else if (isPrimitiveNumeric(type)) {
            reject400(method, expr + " " + op + " " + bound + "L");
        } else if (isBoxedNumeric(type)) {
            reject400(method, expr + " != null && " + expr + " " + op + " " + bound + "L");
        }
        // else: not a numeric field — skip.
    }

    private static String simpleTypeName(String type) {
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "boolean", "char", "float", "double" -> true;
            default -> false;
        };
    }

    private static boolean isStringType(String type) {
        return "String".equals(simpleTypeName(type));
    }

    private static boolean isBigDecimal(String type) {
        return "BigDecimal".equals(simpleTypeName(type));
    }

    private static boolean isPrimitiveNumeric(String type) {
        return switch (type) {
            case "int", "long", "short", "byte", "float", "double" -> true;
            default -> false;
        };
    }

    private static boolean isBoxedNumeric(String type) {
        return switch (simpleTypeName(type)) {
            case "Integer", "Long", "Short", "Byte", "Float", "Double" -> true;
            default -> false;
        };
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
