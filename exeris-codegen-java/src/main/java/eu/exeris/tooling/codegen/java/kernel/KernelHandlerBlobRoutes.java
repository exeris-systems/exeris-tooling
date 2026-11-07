package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;
import static eu.exeris.tooling.codegen.java.support.DataScopeSupport.isTenantPartitioned;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.tooling.codegen.java.support.BlobFields;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.NameCasing;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Locale;

/**
 * The handler half of an entity's {@code @Blob} fields (ADR-108): the store the handler holds, the
 * three methods each field is served by, and the object deletes a hard delete of the row runs.
 *
 * <p>Each field {@code f} is served by {@code handleUpload<F>} ({@code PUT}),
 * {@code handleDownload<F>} ({@code GET}, and {@code HEAD}, which the kernel router answers with
 * the {@code GET} route) and {@code handleDelete<F>} ({@code DELETE}), on
 * {@code {base}/{id}/blobs/<f>}. The object's {@code BlobRef} is the field's container and
 * {@code <id>/<f>}, so the object of a field is found from the row alone and a second upload
 * replaces the first. Every method guards the tenant and the path id as the CRUD methods do, and
 * answers {@code 404} for a row the service does not find, before it touches the store.
 *
 * <p>An entity without a {@code @Blob} field gets none of this, and its handler is unchanged.
 */
final class KernelHandlerBlobRoutes {

    /** The handler field and constructor parameter holding the store. */
    static final String BLOB_STORE = "blobStore";

    private static final ClassName BLOB_STORE_TYPE =
            ClassName.get("eu.exeris.kernel.spi.storage.blob", "BlobStore");
    private static final ClassName BLOB_REF = ClassName.get("eu.exeris.kernel.spi.storage.blob", "BlobRef");
    private static final ClassName BLOB_METADATA =
            ClassName.get("eu.exeris.kernel.spi.storage.blob", "BlobMetadata");
    private static final ClassName BLOB_UPLOAD_HANDLE =
            ClassName.get("eu.exeris.kernel.spi.storage.blob", "BlobUploadHandle");
    private static final ClassName BLOB_DOWNLOAD_HANDLE =
            ClassName.get("eu.exeris.kernel.spi.storage.blob", "BlobDownloadHandle");
    private static final ClassName HTTP_EXCHANGE = ClassName.get("eu.exeris.kernel.spi.http", "HttpExchange");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName HTTP_METHOD = ClassName.get("eu.exeris.kernel.spi.http", "HttpMethod");
    private static final ClassName HTTP_RESPONSE = ClassName.get("eu.exeris.kernel.spi.http", "HttpResponse");
    private static final ClassName HTTP_HEADER = ClassName.get("eu.exeris.kernel.spi.http", "HttpHeader");
    private static final ClassName LOANED_BUFFER = ClassName.get("eu.exeris.kernel.spi.memory", "LoanedBuffer");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName LOCALE = ClassName.get("java.util", "Locale");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    /** The emitted constant holding the 415 status, which the kernel names no constant for. */
    private static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    private static final String EXCHANGE = "exchange";
    private static final String NOT_FOUND = "exchange.respond($T.NOT_FOUND)";
    private static final String BAD_REQUEST = "exchange.respond($T.BAD_REQUEST)";

    private KernelHandlerBlobRoutes() {
    }

    /**
     * Adds the store field, its constructor parameter and the constants to the handler of an entity
     * with {@code @Blob} fields; adds nothing to any other handler.
     *
     * @param handler     the handler under construction
     * @param constructor its constructor, whose parameters end with the store's
     * @param metadata    the entity
     */
    static void addStore(TypeSpec.Builder handler, MethodSpec.Builder constructor, DomainMetadata metadata) {
        List<FieldMetadata> fields = BlobFields.of(metadata);
        if (fields.isEmpty()) {
            return;
        }
        handler.addField(FieldSpec.builder(BLOB_STORE_TYPE, BLOB_STORE, Modifier.PRIVATE, Modifier.FINAL).build());
        constructor.addParameter(BLOB_STORE_TYPE, BLOB_STORE)
                .addStatement("this.$L = $T.requireNonNull($L, $S)", BLOB_STORE, OBJECTS, BLOB_STORE,
                        "blobStore must not be null - RuntimeComponents captures it from "
                                + "KernelProviders.BLOB_STORE inside the bootstrap callback, which the "
                                + "storage subsystem binds when storage.blob.provider names a driver");
        boolean restricted = false;
        for (FieldMetadata field : fields) {
            handler.addField(FieldSpec.builder(String.class, containerConstant(field),
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc("The container the $L objects are stored in.\n", field.name())
                    .initializer("$S", BlobFields.container(metadata, field))
                    .build());
            if (field.blob().hasContentTypes()) {
                restricted = true;
                handler.addField(FieldSpec.builder(ParameterizedTypeName.get(LIST, ClassName.get(String.class)),
                                contentTypesConstant(field), Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .addJavadoc("The media types an upload of $L may carry, lower-case.\n", field.name())
                        .initializer("$T.of($L)", LIST, field.blob().contentTypes().stream()
                                .map(type -> CodeBlock.of("$S", type.strip().toLowerCase(Locale.ROOT)))
                                .collect(CodeBlock.joining(", ")))
                        .build());
            }
        }
        if (restricted) {
            handler.addField(FieldSpec.builder(HTTP_STATUS, UNSUPPORTED_MEDIA_TYPE,
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc("The kernel names no 415 constant; RFC 9110 §15.5.16 names the status.\n")
                    .initializer("new $T(415, $S)", HTTP_STATUS, "Unsupported Media Type")
                    .build());
        }
    }

    /**
     * Adds the three methods of each {@code @Blob} field, in declaration order, and the helpers
     * they share, to the handler of an entity with {@code @Blob} fields; adds nothing to any other
     * handler.
     *
     * @param handler  the handler under construction
     * @param metadata the entity
     */
    static void addRoutes(TypeSpec.Builder handler, DomainMetadata metadata) {
        List<FieldMetadata> fields = BlobFields.of(metadata);
        if (fields.isEmpty()) {
            return;
        }
        String entityLower = Character.toLowerCase(metadata.entityName().charAt(0))
                + metadata.entityName().substring(1);
        boolean tenantPartitioned = isTenantPartitioned(metadata);
        for (FieldMetadata field : fields) {
            handler.addMethod(upload(field, entityLower, tenantPartitioned))
                    .addMethod(download(field, entityLower, tenantPartitioned))
                    .addMethod(delete(field, entityLower, tenantPartitioned));
        }
        handler.addMethod(mediaTypeMethod())
                .addMethod(respondObjectMethod());
        if (!metadata.softDelete()) {
            handler.addMethod(deleteObjectsMethod(fields, entityLower));
        }
        if (cleansUpAfterFailedDelete(metadata)) {
            handler.addMethod(deleteObjectsOfAGoneRowMethod());
        }
    }

    /**
     * Whether the delete handler deletes the row's objects when {@code service.delete(id)} fails
     * and the row is gone: a hard-deleted entity with {@code @Blob} fields whose service projects
     * into the graph after the repository commits, so a graph failure leaves the row deleted and
     * a retried {@code DELETE} answering {@code 404}.
     *
     * @param metadata the entity
     * @return {@code true} when the delete handler wraps {@code service.delete(id)}
     */
    static boolean cleansUpAfterFailedDelete(DomainMetadata metadata) {
        return !metadata.softDelete() && BlobFields.any(metadata)
                && KernelGraphSyncGenerator.syncedByService(metadata);
    }

    /**
     * Appends {@code service.delete(id)} to the delete handler; for an entity that
     * {@link #cleansUpAfterFailedDelete} admits, wrapped so that a failure deletes the objects of a
     * row that is gone before it propagates to the route's catches.
     *
     * @param method   the delete handler, inside its {@code try}
     * @param metadata the entity
     */
    static void appendServiceDelete(MethodSpec.Builder method, DomainMetadata metadata) {
        if (!cleansUpAfterFailedDelete(metadata)) {
            method.addStatement("service.delete(id)");
            return;
        }
        method.beginControlFlow("try")
                .addStatement("service.delete(id)")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("deleteBlobObjectsIfRowGone(id, e)")
                .addStatement("throw e")
                .endControlFlow();
    }

    /**
     * Appends the call that deletes a hard-deleted row's objects, for an entity with {@code @Blob}
     * fields that is not soft-deleted. A soft delete keeps the row, and so its objects.
     *
     * @param method   the delete handler, after the row delete succeeded
     * @param metadata the entity
     */
    static void appendRowDelete(MethodSpec.Builder method, DomainMetadata metadata) {
        if (!metadata.softDelete() && BlobFields.any(metadata)) {
            method.addStatement("deleteBlobObjects(id)");
        }
    }

    /**
     * Starts a {@code public void <name>(HttpExchange exchange)} method with the tenant guard and
     * the path-id guard of the by-id CRUD routes, leaving {@code UUID id} in scope.
     */
    private static MethodSpec.Builder open(String name, boolean tenantPartitioned) {
        MethodSpec.Builder method = KernelHandlerGenerator.crudHandler(name);
        KernelHandlerGenerator.appendTenantGuard(method, tenantPartitioned);
        KernelHandlerGenerator.appendPathIdGuard(method);
        return method;
    }

    private static MethodSpec upload(FieldMetadata field, String entityLower, boolean tenantPartitioned) {
        MethodSpec.Builder method = open("handleUpload" + NameCasing.pascal(field.name()), tenantPartitioned)
                .addJavadoc("Stores the request body as the $L of the row the path names: {@code 204}, or\n",
                        field.name())
                .addJavadoc("{@code 400} for an empty body, {@code 404} for an absent row")
                .addJavadoc(field.blob().hasContentTypes()
                        ? CodeBlock.of(", {@code 415} for a\nmedia type the field does not accept")
                        : CodeBlock.of(""))
                .addJavadoc(". A second upload replaces the first.\n")
                .beginControlFlow("if (!exchange.request().hasBody() || exchange.request().body().size() == 0)")
                .addStatement(BAD_REQUEST, HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .addStatement("String contentType = exchange.request().firstHeader($S).map(String::strip)"
                        + ".filter(value -> !value.isEmpty()).orElse($S)", "content-type", "application/octet-stream");
        if (field.blob().hasContentTypes()) {
            method.beginControlFlow("if (!$L.contains(mediaType(contentType)))",
                            contentTypesConstant(field))
                    .addStatement("exchange.respond($L)", UNSUPPORTED_MEDIA_TYPE)
                    .addStatement("return")
                    .endControlFlow();
        }
        method.beginControlFlow("try");
        appendRowGuard(method);
        method.addStatement("$T body = exchange.request().body()", LOANED_BUFFER)
                .beginControlFlow("try ($T upload = $L.beginUpload($L, body.size(), contentType))",
                        BLOB_UPLOAD_HANDLE, BLOB_STORE, ref(field))
                .addStatement("upload.write(body.segment(), $T.toIntExact(body.size()))", Math.class)
                .addStatement("upload.commit()")
                .endControlFlow()
                .addStatement("exchange.respond($T.NO_CONTENT)", HTTP_STATUS);
        return KernelHandlerGenerator.appendServerErrorCatch(method, "Failed to store the " + field.name() + " of "
                + entityLower).build();
    }

    private static MethodSpec download(FieldMetadata field, String entityLower, boolean tenantPartitioned) {
        MethodSpec.Builder method = open("handleDownload" + NameCasing.pascal(field.name()), tenantPartitioned)
                .addJavadoc("Answers the stored $L of the row the path names, with its stored content type;\n",
                        field.name())
                .addJavadoc("to {@code HEAD}, which the router serves from this route, no body and the\n")
                .addJavadoc("object's {@code Content-Type} and {@code Content-Length}. {@code 404} for an\n")
                .addJavadoc("absent row or object.\n")
                .beginControlFlow("try");
        appendRowGuard(method);
        method.addStatement("$T ref = $L", BLOB_REF, ref(field))
                .addStatement("$T<$T> stored = $L.stat(ref)", OPTIONAL, BLOB_METADATA, BLOB_STORE)
                .beginControlFlow("if (stored.isEmpty())")
                .addStatement(NOT_FOUND, HTTP_STATUS)
                .addStatement("return")
                .endControlFlow()
                .beginControlFlow("if (exchange.request().method() == $T.HEAD)", HTTP_METHOD)
                .addStatement("exchange.respond($T.noBody($T.OK, exchange.request().version(), $T.of(\n"
                                + "new $T($S, stored.get().contentType()),\n"
                                + "new $T($S, $T.toString(stored.get().sizeBytes())))))",
                        HTTP_RESPONSE, HTTP_STATUS, LIST, HTTP_HEADER, "Content-Type", HTTP_HEADER,
                        "Content-Length", Long.class)
                .addStatement("return")
                .endControlFlow()
                .addStatement("respondObject(exchange, ref)");
        return KernelHandlerGenerator.appendServerErrorCatch(method, "Failed to read the " + field.name() + " of "
                + entityLower).build();
    }

    private static MethodSpec delete(FieldMetadata field, String entityLower, boolean tenantPartitioned) {
        MethodSpec.Builder method = open("handleDelete" + NameCasing.pascal(field.name()), tenantPartitioned)
                .addJavadoc("Deletes the stored $L of the row the path names: {@code 204} whether or not\n",
                        field.name())
                .addJavadoc("one was stored, {@code 404} for an absent row.\n")
                .beginControlFlow("try");
        appendRowGuard(method);
        method.addStatement("$L.delete($L)", BLOB_STORE, ref(field))
                .addStatement("exchange.respond($T.NO_CONTENT)", HTTP_STATUS);
        return KernelHandlerGenerator.appendServerErrorCatch(method, "Failed to delete the " + field.name() + " of "
                + entityLower).build();
    }

    /** Answers {@code 404} and returns when the service finds no row for {@code id}. */
    private static void appendRowGuard(MethodSpec.Builder method) {
        method.beginControlFlow("if (service.findById(id).isEmpty())")
                .addStatement(NOT_FOUND, HTTP_STATUS)
                .addStatement("return")
                .endControlFlow();
    }

    private static CodeBlock ref(FieldMetadata field) {
        return CodeBlock.of("new $T($L, id + $S)", BLOB_REF, containerConstant(field), "/" + field.name());
    }

    /** The media type of a {@code Content-Type} value: before any parameter, stripped, lower-case. */
    private static MethodSpec mediaTypeMethod() {
        return MethodSpec.methodBuilder("mediaType")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "contentType")
                .addStatement("int parameters = contentType.indexOf(';')")
                .addStatement("String type = parameters < 0 ? contentType : contentType.substring(0, parameters)")
                .addStatement("return type.strip().toLowerCase($T.ROOT)", LOCALE)
                .build();
    }

    /**
     * Reads the whole object into one buffer from the handler's allocator and answers it. The
     * buffer passes to the engine on {@code respond}; when anything fails before that, it is
     * released here.
     */
    private static MethodSpec respondObjectMethod() {
        return MethodSpec.methodBuilder("respondObject")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(HTTP_EXCHANGE, EXCHANGE)
                .addParameter(BLOB_REF, "ref")
                .addJavadoc("Answers the object {@code ref} names, read into one response buffer.\n")
                .beginControlFlow("try ($T download = $L.openDownload(ref))", BLOB_DOWNLOAD_HANDLE, BLOB_STORE)
                .addStatement("$T stored = download.metadata()", BLOB_METADATA)
                .addStatement("int size = $T.toIntExact(stored.sizeBytes())", Math.class)
                .addStatement("$T body = allocator.allocateNetwork($T.max(size, 1))", LOANED_BUFFER, Math.class)
                .beginControlFlow("try")
                .addStatement("int read = 0")
                .beginControlFlow("while (read < size)")
                .addStatement("int n = download.read(body.segment().asSlice(read), size - read)")
                .beginControlFlow("if (n < 0)")
                .addStatement("break")
                .endControlFlow()
                .addStatement("read += n")
                .endControlFlow()
                .addStatement("body.setSize(read)")
                .addStatement("exchange.respond(new $T($T.OK, exchange.request().version(),\n"
                                + "$T.of(new $T($S, stored.contentType())), body))",
                        HTTP_RESPONSE, HTTP_STATUS, LIST, HTTP_HEADER, "Content-Type")
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("body.close()")
                .addStatement("throw e")
                .endControlFlow()
                .endControlFlow()
                .build();
    }

    /**
     * Deletes every object of a hard-deleted row, each on its own: the row is gone, so a failed
     * object delete is logged at warning level and fails nothing, and leaves the object in the
     * store.
     */
    private static MethodSpec deleteObjectsOfAGoneRowMethod() {
        return MethodSpec.methodBuilder("deleteBlobObjectsIfRowGone")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(UUID, "id")
                .addParameter(RUNTIME_EXCEPTION, "failure")
                .addJavadoc("Deletes the stored objects of a row whose delete failed, when the row is gone.\n")
                .addJavadoc("<p>The service projects the delete into the graph after the repository commits,\n")
                .addJavadoc("so a graph failure leaves no row for a retried {@code DELETE} to find. A row\n")
                .addJavadoc("that is still stored keeps its objects. A failure here is suppressed into\n")
                .addJavadoc("{@code failure}, which the route answers.\n")
                .beginControlFlow("try")
                .beginControlFlow("if (service.findById(id).isEmpty())")
                .addStatement("deleteBlobObjects(id)")
                .endControlFlow()
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("failure.addSuppressed(e)")
                .endControlFlow()
                .build();
    }

    private static MethodSpec deleteObjectsMethod(List<FieldMetadata> fields, String entityLower) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("deleteBlobObjects")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(UUID, "id")
                .addJavadoc("Deletes the stored objects of a deleted row; a failure is logged and fails\n")
                .addJavadoc("nothing, since the row is already gone.\n");
        for (FieldMetadata field : fields) {
            method.beginControlFlow("try")
                    .addStatement("$L.delete($L)", BLOB_STORE, ref(field))
                    .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                    .addStatement("LOG.log($T.WARNING, $S, e)", KernelScaffold.LOGGER_LEVEL,
                            "A deleted " + entityLower + " row kept its stored " + field.name()
                                    + ": deleting the object failed, so it stays in the blob store")
                    .endControlFlow();
        }
        return method.build();
    }

    private static String containerConstant(FieldMetadata field) {
        return constantCase(field.name()) + "_CONTAINER";
    }

    private static String contentTypesConstant(FieldMetadata field) {
        return constantCase(field.name()) + "_CONTENT_TYPES";
    }

    private static String constantCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }
}
