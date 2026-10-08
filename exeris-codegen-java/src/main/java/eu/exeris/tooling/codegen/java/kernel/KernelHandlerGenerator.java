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
import static eu.exeris.tooling.codegen.java.support.DataScopeSupport.isTenantPartitioned;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelEventSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.PrimaryKeys;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.sdk.sourcemodel.ast.ActionParamMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainEventMetadata;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Kernel Handler Generator.
 * <p>
 * Emits a per-entity {@code *Handler} class whose methods match the
 * {@code eu.exeris.kernel.spi.http.HttpHandler} functional interface
 * (each handler method is wired into the router individually by reference).
 * <p>
 * Wired against Open-Core SPI:
 * <ul>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpExchange} — request/response lifecycle</li>
 *   <li>{@code eu.exeris.kernel.spi.http.HttpStatus} — status codes</li>
 *   <li>{@code eu.exeris.kernel.spi.memory.LoanedBuffer} — zero-copy request body</li>
 * </ul>
 * Response bodies are serialised by the exchange's typed-response encoder
 * via {@code eu.exeris.kernel.spi.http.HttpExchange#respond(eu.exeris.kernel.spi.http.HttpStatus, Object)};
 * the handler does not run its own response writer.
 * <p>
 * Request bodies are decoded through the server-side request-body codec SPI
 * (ADR-036): the handler resolves an {@code HttpRequestBodyDecoder} from
 * {@code eu.exeris.kernel.spi.http.HttpKernelProviders#httpRequestBodyDecoderRegistry()}
 * and hands it the {@code LoanedBuffer} directly (no heap {@code byte[] + String}
 * round-trip). No Jackson type is emitted into generated code — the JSON binding
 * lives behind the SPI in the active codec driver (Community Jackson today,
 * alternative bindings tomorrow).
 *
 * @implNote Emission is JavaPoet-based (ADR-015). Output style is owned by
 *     JavaPoet's pretty-printer; substring assertions in the E2E suite still hold,
 *     compile-gate verifies semantics against real {@code exeris-kernel-spi}.
 *
 * @since 0.1
 */
public class KernelHandlerGenerator implements KernelArtifactGenerator {

    /** The generated publisher's field and constructor-parameter name (T48). */
    private static final String PUBLISHER = "publisher";

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
    private static final ClassName MEMORY_ALLOCATOR =
            ClassName.get("eu.exeris.kernel.spi.memory", "MemoryAllocator");

    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName FAULT_ORIGIN =
            ClassName.get("eu.exeris.kernel.spi.exceptions", "FaultOrigin");
    private static final ClassName ILLEGAL_STATE_EXCEPTION =
            ClassName.get("java.lang", "IllegalStateException");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    private static final ClassName ILLEGAL_ARGUMENT_EXCEPTION =
            ClassName.get("java.lang", "IllegalArgumentException");
    private static final ClassName RUNTIME_EXCEPTION =
            ClassName.get("java.lang", "RuntimeException");
    /** JavaPoet parameter name for the {@code HttpExchange} every handler method takes. */
    private static final String EXCHANGE_PARAM = "exchange";
    /** The 500 tail every deployment-fault refusal ends on (T41, T52, and the service-call catch). */
    private static final String RESPOND_SERVER_ERROR = "exchange.respond($T.INTERNAL_SERVER_ERROR)";

    /**
     * Creates the generator. It keeps no per-domain state, so one instance serves every domain
     * in a build.
     */
    public KernelHandlerGenerator() {
        // no state to initialise
    }

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
        TypeName optionalOfEntity = ParameterizedTypeName.get(OPTIONAL, entityType);

        // A tenant-scoped entity cannot be served without a tenant; a global one can. The guard is
        // emitted only where its absence would produce a silently-empty response (finding T41).
        boolean tenantPartitioned = isTenantPartitioned(metadata);

        TypeSpec.Builder handlerBuilder = KernelScaffold.publicClass(className)
                .addJavadoc("Generated HTTP Handler for $L.\n", entity)
                .addJavadoc("<p>Source: {@link $T}\n", entityType)
                .addJavadoc("<p>Path: $L\n", metadata.effectivePath())
                .addJavadoc("<p>Each {@code handleX(HttpExchange)} method matches the\n")
                .addJavadoc("{@link eu.exeris.kernel.spi.http.HttpHandler} functional interface\n")
                .addJavadoc("and is wired into the router individually.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(KernelScaffold.loggerField(selfType))
                .addField(FieldSpec.builder(serviceType, "service", Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                // T43-follow-up: the allocator is captured, not resolved per request. See the
                // constructor Javadoc below for why the ScopedValue cannot be read from here.
                .addField(FieldSpec.builder(MEMORY_ALLOCATOR, "allocator", Modifier.PRIVATE, Modifier.FINAL)
                        .build());

        // T48 (ADR-075): the generated publisher becomes a constructor argument here
        // rather than of the service, because an action is invoked on the ENTITY by this
        // class and never reaches the service — so a service-held publisher cannot see
        // the ACTION trigger, which is the case T48 names.
        ClassName publisherType = publisherType(metadata);
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("<p><b>The {@code allocator} is a constructor argument, not a per-request\n")
                .addJavadoc("lookup.</b> {@code KernelProviders.MEMORY_ALLOCATOR} is a\n")
                .addJavadoc("{@link java.lang.ScopedValue} bound around the bootstrap callback that\n")
                .addJavadoc("constructs this handler. Resolving it there and holding the instance makes a\n")
                .addJavadoc("wiring fault fail the boot, with the composition on the stack, rather than the\n")
                .addJavadoc("first request that carries a body. The handler reads no binding per request.\n")
                .addParameter(serviceType, "service")
                .addParameter(MEMORY_ALLOCATOR, "allocator")
                .addStatement("this.service = service")
                .addStatement("this.allocator = $T.requireNonNull(allocator, $S)",
                        ClassName.get("java.util", "Objects"),
                        "allocator must not be null — RuntimeComponents captures it from "
                                + "KernelProviders.MEMORY_ALLOCATOR inside the bootstrap callback");
        if (publisherType != null) {
            handlerBuilder.addField(FieldSpec.builder(publisherType, PUBLISHER, Modifier.PRIVATE, Modifier.FINAL)
                    .build());
            constructor.addParameter(publisherType, PUBLISHER)
                    .addStatement("this.$L = $L", PUBLISHER, PUBLISHER);
        }

        handlerBuilder.addMethod(constructor.build())
                .addMethod(buildHandleGetAll(entityLower, metadata, tenantPartitioned))
                .addMethod(buildHandleGetById(entityLower, optionalOfEntity, tenantPartitioned))
                .addMethod(buildHandleCreate(entityLower, entityType, metadata, tenantPartitioned))
                .addMethod(buildHandleUpdate(entityLower, entityType, metadata, tenantPartitioned))
                .addMethod(buildHandleDelete(entityLower, optionalOfEntity, metadata, tenantPartitioned));

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
        if (metadata.hasActions()) {
            for (ActionMetadata action : metadata.actions()) {
                if (action.streaming()) {
                    continue;
                }
                if (action.hasParams()) {
                    handlerBuilder.addType(buildActionRequestRecord(action));
                }
                handlerBuilder.addMethod(buildActionHandler(
                        action, entityType, optionalOfEntity, selfType, entityLower, metadata, tenantPartitioned));
            }
        }

        // Both the by-id CRUD routes and the action routes capture the entity id as
        // the {id} path-template variable, so a single helper reads it from
        // exchange.pathParams() — no raw-path string surgery, and no separate action-aware extractor.
        if (tenantPartitioned) {
            handlerBuilder.addMethod(buildRespondTenantUnbound(entityLower));
        }
        handlerBuilder.addMethod(buildRawQuery());
        handlerBuilder.addMethod(buildExtractPathId());
        handlerBuilder.addMethod(buildRespondDecoderUnavailable(entityLower));
        handlerBuilder.addMethod(buildRespondDecodeFailed(entityLower));
        handlerBuilder.addMethod(parseBodyMethod(HTTP_EXCHANGE));

        TypeSpec handler = handlerBuilder.build();

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, handler), ArtifactType.CONTROLLER);
    }

    /**
     * The list route: parse the query string into an {@code <Entity>ListQuery}, read that page, and
     * answer it as an {@code <Entity>Page}.
     *
     * <p>A query the list query refuses — an unknown or repeated parameter, a property outside the
     * sort or filter whitelist, a value that does not parse, a negative page, a size out of range —
     * is the caller's fault and answers {@code 400} with no body and no log, like every other
     * malformed request this handler refuses. It is refused before the service is reached.
     */
    private MethodSpec buildHandleGetAll(String entityLower, DomainMetadata metadata, boolean tenantPartitioned) {
        ClassName queryType = KernelListQueryGenerator.listQueryType(metadata);
        MethodSpec.Builder method = crudHandler("handleGetAll");
        appendTenantGuard(method, tenantPartitioned);
        method.addStatement("$T query", queryType)
                .beginControlFlow("try")
                .addStatement("query = $T.parse(rawQuery(exchange))", queryType)
                .nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("try")
                .addStatement("$T page = service.findPage(query)", KernelListQueryGenerator.pageType(metadata))
                .addStatement("exchange.respond($T.OK, page)", HTTP_STATUS);
        return appendServerErrorCatch(method, "Failed to get all " + entityLower + "s").build();
    }

    /**
     * The query string of the request target — what follows {@code ?}, still percent-encoded, or
     * {@code ""}. The kernel carries the whole request target in {@code HttpRequest.path()}; its
     * router matches the route on the part before {@code ?}.
     */
    private static MethodSpec buildRawQuery() {
        return MethodSpec.methodBuilder("rawQuery")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(HTTP_EXCHANGE, EXCHANGE_PARAM)
                .addStatement("String target = exchange.request().path()")
                .addStatement("int start = target.indexOf('?')")
                .addStatement("return start < 0 ? $S : target.substring(start + 1)", "")
                .build();
    }

    private MethodSpec buildHandleGetById(String entityLower, TypeName optionalOfEntity, boolean tenantPartitioned) {
        MethodSpec.Builder method = crudHandler("handleGetById");
        appendTenantGuard(method, tenantPartitioned);
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

    private MethodSpec buildHandleCreate(String entityLower, ClassName entityType, DomainMetadata metadata,
                                     boolean tenantPartitioned) {
        MethodSpec.Builder method = crudHandler("handleCreate");
        appendTenantGuard(method, tenantPartitioned);
        appendBodyParseGuard(method, entityType);
        appendValidationGuard(method, metadata.fields());
        method.beginControlFlow("try")
                .addStatement("$T saved = service.save(entity)", entityType);
        appendPublishCalls(method, metadata, DomainEventMetadata.Trigger.CREATE, null,
                "saved." + PrimaryKeys.getter(metadata) + "()", "saved");
        method.addStatement("exchange.respond($T.CREATED, saved)", HTTP_STATUS);
        appendCallerFaultCatch(method, metadata);
        return appendServerErrorCatch(method, "Failed to create " + entityLower).build();
    }

    private MethodSpec buildHandleUpdate(String entityLower, ClassName entityType, DomainMetadata metadata,
                                     boolean tenantPartitioned) {
        MethodSpec.Builder method = crudHandler("handleUpdate");
        appendTenantGuard(method, tenantPartitioned);
        appendPathIdGuard(method);
        appendBodyParseGuard(method, entityType);
        appendValidationGuard(method, metadata.fields());
        method.beginControlFlow("try")
                .addStatement("$T updated = service.update(id, entity)", entityType);
        appendPublishCalls(method, metadata, DomainEventMetadata.Trigger.UPDATE, null,
                "id", "updated");
        method.addStatement("exchange.respond($T.OK, updated)", HTTP_STATUS);
        appendWriteRejectionCatch(method, metadata, true);
        appendCallerFaultCatch(method, metadata);
        return appendServerErrorCatch(method, "Failed to update " + entityLower).build();
    }

    private MethodSpec buildHandleDelete(String entityLower, TypeName optionalOfEntity,
                                         DomainMetadata metadata, boolean tenantPartitioned) {
        MethodSpec.Builder method = crudHandler("handleDelete");
        appendTenantGuard(method, tenantPartitioned);
        appendPathIdGuard(method);
        method.beginControlFlow("try");

        // A DELETE-triggered event that carries a payload needs the aggregate, and after
        // service.delete(id) there is none. The read is emitted only when some event
        // actually needs it, so an entity with no payload-bearing DELETE event keeps the
        // single-statement delete it has always had.
        //
        // Why no publish here needs a "did the row exist" guard, measured rather than
        // assumed: the emitted repository's deleteById throws <Entity>NotFoundException when
        // rowsAffected == 0 (KernelRepositoryGenerator#buildDeleteById), and the service
        // delegates straight to it. So a DELETE on an absent id — including a retried one,
        // since the second call affects no rows — leaves this try block through the
        // not-found catch below and answers 404 (ADR-076).
        // Every statement after service.delete(id), publish calls included, is reachable
        // only when a row was actually removed. The isPresent() check on the payload path
        // is defensive against a race between the read and the delete, not the thing that
        // makes the publish correct.
        boolean needsAggregate = triggered(metadata, DomainEventMetadata.Trigger.DELETE, null).stream()
                .anyMatch(event -> !KernelEventGenerator.payloadFields(event, metadata).isEmpty());
        if (needsAggregate) {
            method.addStatement("$T removed = service.findById(id)", optionalOfEntity);
        }
        method.addStatement("service.delete(id)");
        // Non-payload DELETE events publish straight after the delete; payload-bearing ones
        // inside the presence check, because they need an aggregate to hand over.
        appendPublishCalls(method, metadata, DomainEventMetadata.Trigger.DELETE, null, "id", null);
        if (needsAggregate) {
            method.beginControlFlow("if (removed.isPresent())");
            appendPayloadPublishCalls(method, metadata, DomainEventMetadata.Trigger.DELETE, null,
                    "id", "removed.get()");
            method.endControlFlow();
        }
        method.addStatement("exchange.respond($T.NO_CONTENT)", HTTP_STATUS);
        // Always the not-found type, never the conflict one: deleteById matches on id alone,
        // so a versioned entity has no stale-version failure mode on this route.
        appendWriteRejectionCatch(method, metadata, false);
        return appendServerErrorCatch(method, "Failed to delete " + entityLower).build();
    }

    /**
     * The {@code catch} that turns a write rejection into the status it deserves (ADR-076),
     * emitted ahead of the {@code RuntimeException} → 500 tail so it is reached first.
     *
     * <p>Exactly one catch is emitted, because exactly one type is reachable at each site.
     * {@code deleteById} matches on {@code id} alone and can only report a missing row, so
     * {@code fromUpdate} is false there. {@code update} on a {@code versioned} entity matches
     * on {@code id} <i>and</i> version in one statement and reports the pair as a conflict, so
     * a versioned update — and every action route, which persists through the same
     * {@code service.update} — answers {@code 409} and never {@code 404}. Emitting both
     * catches everywhere would put a clause on each method that nothing can throw into it.
     */
    private static void appendWriteRejectionCatch(MethodSpec.Builder method,
                                                  DomainMetadata metadata, boolean fromUpdate) {
        ClassName conflict = fromUpdate ? KernelErrorGenerator.versionConflictType(metadata) : null;
        if (conflict != null) {
            method.nextControlFlow("catch ($T e)", conflict)
                    .addStatement("exchange.respond($T.CONFLICT)", HTTP_STATUS);
            return;
        }
        // No log: this is the same answer handleGetById already gives for the same fact, and
        // that path logs nothing either. A missing row is not an event the server owns.
        method.nextControlFlow("catch ($T e)", KernelErrorGenerator.notFoundType(metadata))
                .addStatement("exchange.respond($T.NOT_FOUND)", HTTP_STATUS);
    }

    /**
     * The {@code catch} that answers a caller-fault write refusal with {@code 400} (kernel ADR-083),
     * emitted ahead of the {@code RuntimeException} → 500 tail. Only on the routes that hand the
     * repository an entity to write — create, update and every action — and only for an entity
     * whose repository can raise one: {@code <Entity>TenantMismatchException} on a tenant-partitioned
     * entity, joined by {@code <Entity>SharedScopeMismatchException} on a UNIVERSE entity with a
     * {@code @SharedScope} field.
     *
     * <p>400, and neither 403 nor 409. The body named a tenant (or a shared scope) the caller does
     * not act as, and repeating it unchanged fails the same way — the kernel's
     * {@code FaultOrigin.CALLER}, which the ADR-036 §2 mapping answers 4xx. 403 would claim an
     * authorization model the emitted application does not have (ADR-079), and 409 already means a
     * version conflict (ADR-076). No log, like the 404 beside it: a malformed request is not an event
     * the server owns.
     */
    private static void appendCallerFaultCatch(MethodSpec.Builder method, DomainMetadata metadata) {
        ClassName tenantMismatch = KernelErrorGenerator.tenantMismatchType(metadata);
        if (tenantMismatch == null) {
            return;
        }
        ClassName sharedScopeMismatch = KernelErrorGenerator.sharedScopeMismatchType(metadata);
        if (sharedScopeMismatch == null) {
            method.nextControlFlow("catch ($T e)", tenantMismatch);
        } else {
            method.nextControlFlow("catch ($T | $T e)", tenantMismatch, sharedScopeMismatch);
        }
        method.addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS);
    }

    /**
     * Whether any declared event has a trigger a handler method serves.
     *
     * <p>Deliberately narrower than {@code hasEvents()}: an entity whose only events are
     * {@code FIELD_CHANGED} / {@code STATE_TRANSITION} / {@code SCHEDULED} / {@code MANUAL} /
     * {@code SNAPSHOT}, or that carry no trigger at all, would otherwise get a publisher field
     * and constructor parameter no emitted line ever reads. The publisher still joins
     * {@code RuntimeComponents} in that case — a {@code MANUAL} event is published by the
     * consumer's own code, which needs to reach it — it just does not reach the handler.
     */
    static boolean publishesFromHandler(DomainMetadata metadata) {
        return metadata.events().stream()
                .anyMatch(event -> event.trigger() == DomainEventMetadata.Trigger.CREATE
                        || event.trigger() == DomainEventMetadata.Trigger.UPDATE
                        || event.trigger() == DomainEventMetadata.Trigger.DELETE
                        || event.trigger() == DomainEventMetadata.Trigger.ACTION);
    }

    /** The emitted publisher's type, or {@code null} when no handler method would call it. */
    private ClassName publisherType(DomainMetadata metadata) {
        if (!publishesFromHandler(metadata)) {
            return null;
        }
        return ClassName.get(metadata.packageName().replace(".domain", ".event"),
                metadata.entityName() + "EventPublisher");
    }

    /**
     * The events a given handler method owes a publish call, in declaration order.
     *
     * <p>{@code actionName} is the discriminator for {@link DomainEventMetadata.Trigger#ACTION}
     * only; for every other trigger it is {@code null} and ignored. An {@code ACTION} event whose
     * {@code actionName} names no declared action matches nothing and is silently unpublished —
     * deliberately, because refusing the build on it would make a typo in one event fail an
     * entity's whole CRUD surface, and the {@code -Aexeris.strict} audit is the place that kind of
     * "you wrote it and it does nothing" belongs.
     */
    static List<DomainEventMetadata> triggered(DomainMetadata metadata,
                                                DomainEventMetadata.Trigger trigger,
                                                String actionName) {
        return metadata.events().stream()
                .filter(event -> event.trigger() == trigger)
                .filter(event -> trigger != DomainEventMetadata.Trigger.ACTION
                        || (actionName != null && actionName.equals(event.actionName())))
                .toList();
    }

    /**
     * T48 (ADR-075): emits the publish calls for one handler method, after the mutation and
     * before the response.
     *
     * <p><b>After the commit, not inside it.</b> The transaction boundary lives in the repository,
     * below the service, so a publish from here necessarily runs post-commit: a crash between the
     * two loses the event. The descriptors carry {@code FLAG_PERSISTENT}, which makes *delivery*
     * durable once published — not the publish itself. Moving the call inside the transaction means
     * moving it below the service, which is exactly the seam that cannot see the {@code ACTION}
     * trigger (ADR-075).
     *
     * @param aggregateExpr expression yielding the aggregate for payload-bearing events, or
     *                      {@code null} when the caller has none to offer
     */
    static void appendPublishCalls(MethodSpec.Builder method, DomainMetadata metadata,
                                    DomainEventMetadata.Trigger trigger, String actionName,
                                    String idExpr, String aggregateExpr) {
        for (DomainEventMetadata event : triggered(metadata, trigger, actionName)) {
            if (!KernelEventGenerator.payloadFields(event, metadata).isEmpty()) {
                continue;
            }
            method.addStatement("$L.publish$L($L)", PUBLISHER,
                    KernelEventSupport.eventName(event, metadata.entityName()), idExpr);
        }
        if (aggregateExpr != null) {
            appendPayloadPublishCalls(method, metadata, trigger, actionName, idExpr, aggregateExpr);
        }
    }

    /** The payload-bearing half of {@link #appendPublishCalls}, separable because the delete
     *  path can only offer an aggregate inside a presence check. */
    private static void appendPayloadPublishCalls(MethodSpec.Builder method, DomainMetadata metadata,
                                           DomainEventMetadata.Trigger trigger, String actionName,
                                           String idExpr, String aggregateExpr) {
        for (DomainEventMetadata event : triggered(metadata, trigger, actionName)) {
            if (KernelEventGenerator.payloadFields(event, metadata).isEmpty()) {
                continue;
            }
            method.addStatement("$L.publish$L($L, $L)", PUBLISHER,
                    KernelEventSupport.eventName(event, metadata.entityName()), idExpr, aggregateExpr);
        }
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
     *  it does not accept the field-shaped create/update body those rules describe.
     *
     *  <p>The tenant guard IS applied. An action loads the aggregate through the same
     *  service the CRUD routes use, so with no tenant bound row-level security hides the row
     *  and the handler answers 404 — telling a caller the entity does not exist when it does
     *  and the real fault is missing wiring. That is the same undiagnosable
     *  answer T41 was opened for, wearing a different status code, so it gets the same refusal. */
    private MethodSpec buildActionHandler(ActionMetadata action, ClassName entityType,
                                          TypeName optionalOfEntity, ClassName selfType,
                                          String entityLower, DomainMetadata metadata,
                                          boolean tenantPartitioned) {
        MethodSpec.Builder method = crudHandler("handle" + NameCasing.pascal(action.name()));
        method.addJavadoc("Serves the {@code $L} action. NOTE (v1): a domain exception from "
                + "the entity method surfaces as 500, not 4xx.\n", action.name());

        // Before anything else, and before the body is even read: an action on a tenant-scoped
        // entity cannot be served without a tenant, and answering 404 would be a lie.
        appendTenantGuard(method, tenantPartitioned);

        // id from the {id} path-template variable — the same capture as the by-id
        // CRUD routes; the trailing /actions/{name} segment doesn't change it.
        appendPathIdGuard(method);

        if (action.hasParams()) {
            ClassName requestType = selfType.nestedClass(actionRequestName(action));
            method.addStatement("$T request", requestType)
                    .beginControlFlow("try")
                    .addStatement("request = parseBody(exchange, $T.class)", requestType);
            appendBodyDecodeCatches(method);
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

        method.addStatement("$T updated = service.update(id, entity)", entityType);
        appendPublishCalls(method, metadata, DomainEventMetadata.Trigger.ACTION, action.name(),
                "id", "updated");
        method.addStatement("exchange.respond($T.OK, updated)", HTTP_STATUS);
        // The action persists through service.update, so it inherits that route's rejection.
        // The findById above already answered 404 for an id that was never there; what this
        // catches is the row disappearing (or its version moving) between the read and the write.
        appendWriteRejectionCatch(method, metadata, true);
        // An action writes through the same service.update, so a partition-mate acting on a shared
        // row it can read but not own is refused there, as the caller fault it is (kernel ADR-083).
        appendCallerFaultCatch(method, metadata);
        return appendServerErrorCatch(method,
                "Failed to execute action " + action.name() + " on " + entityLower).build();
    }

    /** Emits the per-action request record (canonical constructor = {@code @ActionParam}
     *  components, in declaration order). Decoded by {@code parseBody} via the ADR-036
     *  codec SPI, exactly like the CRUD body. */
    static TypeSpec buildActionRequestRecord(ActionMetadata action) {
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

    static String actionRequestName(ActionMetadata action) {
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
                .addParameter(HTTP_EXCHANGE, EXCHANGE_PARAM);
    }

    /**
     * Emits the call site of the tenant guard, for a tenant-partitioned entity only.
     *
     * <p>A global entity needs no tenant to be readable, so guarding it would refuse requests that
     * are perfectly serviceable.
     */
    private static void appendTenantGuard(MethodSpec.Builder method, boolean tenantPartitioned) {
        if (!tenantPartitioned) {
            return;
        }
        method.beginControlFlow("if (!$T.STORAGE_CONTEXT.isBound())", KERNEL_PROVIDERS)
                .addStatement("respondTenantUnbound(exchange)")
                .addStatement("return")
                .endControlFlow();
    }

    /**
     * Emits the shared "no tenant is bound" refusal.
     *
     * <p>Without this, a request that reaches a tenant-scoped handler with no {@code STORAGE_CONTEXT}
     * is served: the persistence layer falls back to a system-scope context whose isolation key is
     * empty, the RLS policy matches no row, and the caller receives {@code 200 []}. Data exists and
     * the response says there is none — indistinguishable from an empty database, and the single
     * hardest failure to diagnose in the whole stack.
     *
     * <p>500 rather than 401, because an unbound context is a <em>deployment</em> fault rather than
     * a caller fault: the kernel binds both contexts in {@code SecurityInterceptor}, which the
     * Community dispatcher runs only for a route whose {@code HttpRoutePolicy} requirement is not
     * {@code permitAll()} — and the emitted application binds no policy (ADR-079), so no emitted
     * route demands identity and the interceptor never runs. Hence also a message naming the
     * wiring rather than the request: the caller has nothing to correct.
     */
    private static MethodSpec buildRespondTenantUnbound(String entityLower) {
        return MethodSpec.methodBuilder("respondTenantUnbound")
                .addJavadoc("Refuses a tenant-scoped request that carries no tenant.\n")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_EXCHANGE, EXCHANGE_PARAM)
                .addStatement("LOG.log($T.ERROR, $S)", KernelScaffold.LOGGER_LEVEL,
                        tenantUnboundMessage(entityLower))
                .addStatement(RESPOND_SERVER_ERROR, HTTP_STATUS)
                .build();
    }

    /**
     * Emits the shared "no decoder could read this body" refusal (T52).
     *
     * <p>{@code parseBody} re-throws {@link IllegalStateException} unchanged so an absent
     * decoder registry is never downgraded to 400 (ADR-036), which makes this the one
     * {@code parseBody} failure a handler must answer as a deployment fault. Hence 500, and
     * hence a message naming the wiring rather than the request: a caller whose body was never
     * read has nothing to correct. The cause is logged rather than returned, so no internals
     * reach the client — the same contract as {@link #buildRespondTenantUnbound}.
     */
    private static MethodSpec buildRespondDecoderUnavailable(String entityLower) {
        return MethodSpec.methodBuilder("respondDecoderUnavailable")
                .addJavadoc("Refuses a request whose body no registered decoder could read.\n")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_EXCHANGE, EXCHANGE_PARAM)
                .addParameter(ILLEGAL_STATE_EXCEPTION, "cause")
                .addStatement("LOG.log($T.ERROR, $S, cause)", KernelScaffold.LOGGER_LEVEL,
                        decoderUnavailableMessage(entityLower))
                .addStatement(RESPOND_SERVER_ERROR, HTTP_STATUS)
                .build();
    }

    /**
     * Emits the shared "the decoder failed server-side" refusal.
     *
     * <p>{@code parseBody} wraps every decode failure that kernel ADR-083's
     * {@code FaultOrigin.classify} does not report as {@code CALLER} — a kernel exception left at
     * {@code SYSTEM}, a driver exception, a JDK exception — and this answers it. 500, because the
     * caller cannot fix it by changing the request (ADR-036 §2). Logged with its cause and returned
     * without it, like {@link #buildRespondDecoderUnavailable}; the message carries no request data,
     * because a body that failed to decode is the content most likely to hold a secret.
     */
    private static MethodSpec buildRespondDecodeFailed(String entityLower) {
        return MethodSpec.methodBuilder("respondDecodeFailed")
                .addJavadoc("Refuses a request whose body the decoder failed to read for a reason\n")
                .addJavadoc("the kernel does not attribute to the caller.\n")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_EXCHANGE, EXCHANGE_PARAM)
                .addParameter(RUNTIME_EXCEPTION, "cause")
                .addStatement("LOG.log($T.ERROR, $S, cause)", KernelScaffold.LOGGER_LEVEL,
                        decodeFailedMessage(entityLower))
                .addStatement(RESPOND_SERVER_ERROR, HTTP_STATUS)
                .build();
    }

    /**
     * The log line of the tenant guard's refusal, shared by every route that carries the guard.
     *
     * @param entityLower the entity name, first letter lower-case
     * @return the message, naming the bindings that would let the request through
     */
    static String tenantUnboundMessage(String entityLower) {
        return "Refusing " + entityLower + " request: no tenant is bound. This entity is "
                + "tenant-scoped, so row-level security would return no rows and the "
                + "response would be indistinguishable from an empty database. The "
                + "kernel binds PRINCIPAL_CONTEXT and STORAGE_CONTEXT from an "
                + "authenticated token in its SecurityInterceptor, which the HTTP "
                + "dispatcher runs only for a route whose HttpRoutePolicy requirement "
                + "is not permitAll() - and this application binds no policy, so no "
                + "route demands identity. Bind HttpKernelProviders.HTTP_ROUTE_POLICY "
                + "around boot, or bind KernelProviders.STORAGE_CONTEXT around the "
                + "dispatch.";
    }

    /**
     * The log line of the "no decoder" refusal, shared by every route that decodes a body.
     *
     * @param entityLower the entity name, first letter lower-case
     * @return the message, naming the registry the decoder is resolved from
     */
    static String decoderUnavailableMessage(String entityLower) {
        return "Refusing " + entityLower + " request: no request body decoder was "
                + "available. The kernel resolves one from HttpKernelProviders."
                + "HTTP_REQUEST_BODY_DECODER_REGISTRY, which the HTTP subsystem "
                + "binds around the dispatch. An absent registry, or no decoder "
                + "registered for the request content-type, is a deployment fault "
                + "rather than a malformed body - so it is answered 500 and never "
                + "downgraded to 400 (ADR-036). Bind the registry around boot, or "
                + "register a decoder for the content-type this route receives.";
    }

    /**
     * The log line of the "decoder failed server-side" refusal, shared by every route that decodes
     * a body.
     *
     * @param entityLower the entity name, first letter lower-case
     * @return the message, which carries no request data
     */
    static String decodeFailedMessage(String entityLower) {
        return "Refusing " + entityLower + " request: the request body decoder failed with "
                + "a fault the kernel does not classify as the caller's "
                + "(FaultOrigin.SYSTEM) - a decoder or driver defect, or a runtime "
                + "resource failure, rather than a malformed body. It is answered 500 "
                + "and never downgraded to 400 (ADR-036, kernel ADR-083). The logged "
                + "cause names the failure.";
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

    /** Emits the shared "decode the request body into {@code entity} or fail" guard.
     *  Leaves {@code entity} in scope for the caller. The catches are
     *  {@link #appendBodyDecodeCatches}'s. */
    private static void appendBodyParseGuard(MethodSpec.Builder method, ClassName entityType) {
        method.addStatement("$T entity", entityType)
                .beginControlFlow("try")
                .addStatement("entity = parseBody(exchange, $T.class)", entityType);
        appendBodyDecodeCatches(method);
    }

    /**
     * Closes a {@code parseBody} {@code try} with one catch per failure {@code parseBody} raises,
     * shared by every call site so the three answers cannot drift apart between them.
     *
     * <p>{@link IllegalArgumentException} is the caller's body — absent, or a decode failure the
     * kernel classifies {@code FaultOrigin.CALLER} — and answers 400. {@link IllegalStateException}
     * is a decoder this deployment did not bind, and any other {@link RuntimeException} is a decode
     * that failed server-side; both answer 500 and are never downgraded to 400 (ADR-036 §2). The
     * order is load-bearing: the {@code RuntimeException} catch must come last. An uncaught failure
     * would leave the handler and be answered by the dispatcher with no body and no log.
     */
    private static void appendBodyDecodeCatches(MethodSpec.Builder method) {
        method.nextControlFlow("catch ($T e)", ILLEGAL_ARGUMENT_EXCEPTION)
                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                .addStatement("return")
                .nextControlFlow("catch ($T e)", ILLEGAL_STATE_EXCEPTION)
                .addStatement("respondDecoderUnavailable(exchange, e)")
                .addStatement("return")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("respondDecodeFailed(exchange, e)")
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
        for (KernelValidationRules.FieldRules fr : KernelValidationRules.of(fields)) {
            // Read the value once into a local (avoids re-invoking the getter per check).
            // The local is prefixed so it can never collide with a handler-scope variable —
            // see KernelValidationRules.FieldRules#local for why (T22).
            String v = fr.local();
            method.addStatement("var $L = entity.$L()", v, fr.accessor());

            for (KernelValidationRules.Rule rule : fr.rules()) {
                switch (rule.kind()) {
                    case NOT_NULL -> reject400(method, v + " == null");
                    case MIN_LENGTH ->
                            reject400(method, v + " != null && " + v + ".length() < " + rule.bound());
                    case MAX_LENGTH ->
                            reject400(method, v + " != null && " + v + ".length() > " + rule.bound());
                    case PATTERN -> {
                        method.beginControlFlow("if ($L != null && !$L.matches($S))",
                                        v, v, rule.pattern())
                                .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                                .addStatement("return")
                                .endControlFlow();
                    }
                    case MIN -> appendNumericBound(method, fr.field().type(), v, "<", rule.bound());
                    case MAX -> appendNumericBound(method, fr.field().type(), v, ">", rule.bound());
                }
            }
        }
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
        if (KernelValidationRules.isBigDecimal(type)) {
            method.beginControlFlow("if ($L != null && $L.compareTo($T.valueOf($L)) $L 0)",
                            expr, expr, BIG_DECIMAL, bound + "L", op)
                    .addStatement("exchange.respond($T.BAD_REQUEST)", HTTP_STATUS)
                    .addStatement("return")
                    .endControlFlow();
        } else if (KernelValidationRules.isPrimitiveNumeric(type)) {
            reject400(method, expr + " " + op + " " + bound + "L");
        } else if (KernelValidationRules.isBoxedNumeric(type)) {
            reject400(method, expr + " != null && " + expr + " " + op + " " + bound + "L");
        }
        // else: not a numeric field — skip.
    }

    /** Emits the shared "catch RuntimeException → log + 500" tail that closes the
     *  service-call {@code try} block of every CRUD handler. */
    private static MethodSpec.Builder appendServerErrorCatch(MethodSpec.Builder method, String failMessage) {
        return method.nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("LOG.log($T.ERROR, $S, e)", KernelScaffold.LOGGER_LEVEL, failMessage)
                .addStatement(RESPOND_SERVER_ERROR, HTTP_STATUS)
                .endControlFlow();
    }

    /** Reads the entity {@code id} from the {@code {id}} path-template variable the
     *  kernel router captures into {@code exchange.pathParams()}. Falls back to {@code ""}
     *  (→ a 400 at {@code UUID.fromString}) when the variable is absent, so a mis-registered
     *  route fails closed rather than NPEing. One extractor serves the by-id CRUD routes and
     *  the action routes alike; there is no raw-path string surgery. */
    private MethodSpec buildExtractPathId() {
        return MethodSpec.methodBuilder("extractPathId")
                .addModifiers(Modifier.PRIVATE)
                .returns(String.class)
                .addParameter(HTTP_EXCHANGE, EXCHANGE_PARAM)
                .addStatement("return exchange.pathParams().getOrDefault($S, $S)", "id", "")
                .build();
    }

    /**
     * The {@code parseBody} method of a handler whose exchange is {@code exchangeType}.
     *
     * <p>Shared by the respond-once handler ({@code HttpExchange}) and the per-action stream
     * handler ({@code HttpStreamExchange}): both reach the request through {@code request()}, so
     * one body serves both, and the two cannot decode a body differently.
     *
     * @param exchangeType the type of the {@code exchange} parameter
     * @return the generic {@code parseBody(exchange, type)} method
     */
    static MethodSpec parseBodyMethod(ClassName exchangeType) {
        TypeVariableName tVar = TypeVariableName.get("T");
        return MethodSpec.methodBuilder("parseBody")
                .addModifiers(Modifier.PRIVATE)
                .addTypeVariable(tVar)
                .returns(tVar)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addParameter(exchangeType, EXCHANGE_PARAM)
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
                .addJavadoc("<p>Status mapping is the handler's concern, not the SPI's (ADR-036 §2),\n")
                .addJavadoc("and only the caller's fault answers 400. Three failures, three types:\n")
                .addJavadoc("<ul>\n")
                .addJavadoc("<li>{@link IllegalArgumentException} — an absent body, or a decode failure\n")
                .addJavadoc("{@link FaultOrigin#classify} reports as {@link FaultOrigin#CALLER} (a\n")
                .addJavadoc("malformed body, which the decoder SPI requires to surface as\n")
                .addJavadoc("{@code RequestBodyDecodeException}). The call sites answer\n")
                .addJavadoc("{@code 400 BAD_REQUEST}.</li>\n")
                .addJavadoc("<li>{@link IllegalStateException} — an unbound registry or an\n")
                .addJavadoc("unregistered decoder, re-thrown unchanged: a deployment fault,\n")
                .addJavadoc("{@code 500}.</li>\n")
                .addJavadoc("<li>any other {@link RuntimeException} — a decode failure of any other\n")
                .addJavadoc("origin, wrapped so that no JDK type the decoder throws can be read as the\n")
                .addJavadoc("caller's (kernel ADR-083). The call sites answer {@code 500}.</li>\n")
                .addJavadoc("</ul>\n")
                .addJavadoc("<p>The allocator is no longer among those failure modes: it is a\n")
                .addJavadoc("constructor-captured field, so an absent one cannot reach a request at all.\n")
                .beginControlFlow("if (!exchange.request().hasBody())")
                .addStatement("throw new $T($S)", ILLEGAL_ARGUMENT_EXCEPTION, "Missing body")
                .endControlFlow()
                .addStatement("$T body = exchange.request().body()", LOANED_BUFFER)
                .addStatement("String contentType = exchange.request().firstHeader($S).orElse(null)",
                        "content-type")
                // Decoder resolution, context construction, and decode all run inside
                // one try so a RuntimeException from ANY of them (e.g. registry.resolve
                // on a hostile content-type) is mapped at the call site rather than
                // escaping parseBody unhandled. The IllegalStateException catch re-throws
                // unchanged so the intentional 5xx mappings survive per ADR-036 §2.
                //
                // Every other failure is classified by the kernel, not by its JDK type
                // (kernel ADR-083 §4): only FaultOrigin.CALLER becomes the 400
                // IllegalArgumentException. Classify, rather than instanceof
                // RequestBodyDecodeException, so a kernel exception classified CALLER later
                // is answered 400 without a regeneration. The rest is wrapped, because a
                // re-thrown IllegalArgumentException from the decoder would reach the
                // call site's 400 catch. FaultOrigin may gain constants, so the test is an
                // equality against CALLER, never a switch.
                //
                // The allocator is not read here. It arrives as a constructor argument,
                // captured by RuntimeComponents inside the bootstrap callback where the
                // MEMORY_ALLOCATOR ScopedValue binding is live, so a missing binding fails the
                // boot instead of a request.
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
                                + "this.allocator)",
                        HTTP_REQUEST_DECODING_CONTEXT, HTTP_REQUEST_DECODING_CONTEXT)
                .addStatement("return ($T) decoder.decode(body, type, context)", tVar)
                .nextControlFlow("catch ($T e)", ILLEGAL_STATE_EXCEPTION)
                .addStatement("throw e")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .beginControlFlow("if ($T.classify(e) == $T.CALLER)", FAULT_ORIGIN, FAULT_ORIGIN)
                .addStatement("throw new $T($S, e)", ILLEGAL_ARGUMENT_EXCEPTION, "Invalid request body")
                .endControlFlow()
                .addStatement("throw new $T($S, e)", RUNTIME_EXCEPTION,
                        "Request body decoding failed with a server-side fault")
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
