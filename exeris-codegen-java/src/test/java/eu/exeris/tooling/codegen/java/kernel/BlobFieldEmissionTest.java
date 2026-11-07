package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.sdk.sourcemodel.ast.BlobMetadata;
import eu.exeris.sdk.sourcemodel.ast.DataScope;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.FieldMetadata;
import eu.exeris.sdk.sourcemodel.ast.GraphMetadata;
import eu.exeris.sdk.sourcemodel.ast.RouteAccess;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.openapi.OpenApiGenerator;
import eu.exeris.tooling.codegen.java.support.ListQuerySupport;
import eu.exeris.tooling.codegen.java.support.ServerOwnedFields;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Java emitters write for a {@code @Blob} field (ADR-108 and its Amendment 1): no column,
 * no body member, three handler methods and routes per field, a store taken at composition, and an
 * object delete after a hard row delete.
 */
@DisplayName("@Blob field emission (ADR-108)")
class BlobFieldEmissionTest {

    private static final String BLOB_REF = "eu.exeris.kernel.spi.storage.blob.BlobRef";

    private static FieldMetadata receipt() {
        return FieldMetadata.builder("receipt", BLOB_REF)
                .filterable(true)
                .sortable(true)
                .blob(BlobMetadata.ofContentTypes(List.of("application/pdf", "Image/PNG")))
                .build();
    }

    private static FieldMetadata scanImage() {
        return FieldMetadata.builder("scanImage", BLOB_REF)
                .blob(new BlobMetadata("scans", List.of()))
                .build();
    }

    private static DomainMetadata.Builder order() {
        return DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .dataScope(DataScope.TENANT)
                .fields(List.of(
                        FieldMetadata.builder("note", "String").filterable(true).sortable(true).build(),
                        receipt(),
                        scanImage()));
    }

    private static String file(List<GeneratedFile> files, String className) {
        return files.stream().filter(f -> className.equals(f.className())).map(GeneratedFile::content)
                .findFirst().orElseThrow(() -> new AssertionError(className + " is not emitted"));
    }

    private static List<GeneratedFile> perEntity(DomainMetadata metadata) {
        return new KernelGeneratorStrategy().generate(metadata);
    }

    private static String noIndent(String source) {
        return source.replaceAll("(?m)^[ \\t]+", "");
    }

    @Test
    @DisplayName("a @Blob field is no column, no finder, no filter or sort property, and no member of the migration")
    void blobFieldIsNoColumn() {
        DomainMetadata metadata = order().build();
        List<GeneratedFile> files = perEntity(metadata);
        String repository = file(files, "OrderRepository");

        assertThat(repository).contains("note").doesNotContain("receipt").doesNotContain("scan_image")
                .doesNotContain("findByReceipt");
        assertThat(KernelRepositoryGenerator.finderSpecs(metadata))
                .extracting(KernelRepositoryGenerator.FinderSpec::paramName).containsExactly("note");
        assertThat(ListQuerySupport.filters(metadata)).extracting(ListQuerySupport.Property::name)
                .containsExactly("note");
        assertThat(ListQuerySupport.sortable(metadata)).extracting(ListQuerySupport.Property::name)
                .containsExactly("note");
        String migration = files.stream().filter(f -> f.className().contains("create_orders"))
                .map(GeneratedFile::content).findFirst().orElseThrow();
        assertThat(migration).contains("note").doesNotContain("receipt").doesNotContain("scan_image");
    }

    @Test
    @DisplayName("a @Blob field is in neither body and is reset to null after create and update decode")
    void blobFieldIsServerOwned() {
        DomainMetadata metadata = order().build();

        assertThat(ServerOwnedFields.notInCreateBody(metadata)).contains("receipt", "scanImage");
        assertThat(ServerOwnedFields.notInUpdateBody(metadata)).contains("receipt", "scanImage");
        assertThat(ServerOwnedFields.fixedOnRequestUpdate(metadata)).doesNotContain("receipt");
        assertThat(KernelRepositoryGenerator.hasRequestUpdate(metadata)).isFalse();
        String handler = noIndent(file(perEntity(metadata), "OrderHandler"));
        assertThat(handler)
                .contains("entity = parseBody(exchange, Order.class);")
                .contains("entity.setReceipt(null);\nentity.setScanImage(null);\ntry {\nOrder saved = service.save(entity);")
                .contains("entity.setReceipt(null);\nentity.setScanImage(null);\ntry {\nOrder updated = service.update(id, entity);");
    }

    @Test
    @DisplayName("the handler takes the store last and serves three routes per field, in declaration order")
    void handlerServesTheFieldRoutes() {
        String handler = file(perEntity(order().build()), "OrderHandler");
        String flat = noIndent(handler);

        assertThat(handler)
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator, BlobStore blobStore)")
                .contains("private static final String RECEIPT_CONTAINER = \"orders\";")
                .contains("private static final String SCAN_IMAGE_CONTAINER = \"scans\";")
                .contains("private static final List<String> RECEIPT_CONTENT_TYPES = "
                        + "List.of(\"application/pdf\", \"image/png\");")
                .doesNotContain("SCAN_IMAGE_CONTENT_TYPES")
                .contains("new HttpStatus(415, \"Unsupported Media Type\")");
        assertThat(handler.indexOf("public void handleDelete(HttpExchange exchange)"))
                .isLessThan(handler.indexOf("public void handleUploadReceipt(HttpExchange exchange)"));
        assertThat(handler.indexOf("public void handleUploadReceipt("))
                .isLessThan(handler.indexOf("public void handleDownloadReceipt("));
        assertThat(handler.indexOf("public void handleDeleteReceipt("))
                .isLessThan(handler.indexOf("public void handleUploadScanImage("));
        assertThat(flat)
                .contains("if (!exchange.request().hasBody() || exchange.request().body().size() == 0) {\n"
                        + "exchange.respond(HttpStatus.BAD_REQUEST);")
                .contains(".filter(value -> !value.isEmpty()).orElse(\"application/octet-stream\");")
                .contains("if (!RECEIPT_CONTENT_TYPES.contains(mediaType(contentType))) {\n"
                        + "exchange.respond(UNSUPPORTED_MEDIA_TYPE);")
                .contains("try (BlobUploadHandle upload = blobStore.beginUpload("
                        + "new BlobRef(RECEIPT_CONTAINER, id + \"/receipt\"), body.size(), contentType)) {")
                .contains("if (exchange.request().method() == HttpMethod.HEAD) {")
                .contains("new HttpHeader(\"Content-Length\", Long.toString(stored.get().sizeBytes()))")
                .contains("blobStore.delete(new BlobRef(SCAN_IMAGE_CONTAINER, id + \"/scanImage\"));\n"
                        + "exchange.respond(HttpStatus.NO_CONTENT);")
                .contains("if (!KernelProviders.STORAGE_CONTEXT.isBound()) {");
        // The upload of an unrestricted field checks no media type.
        String scanUpload = flat.substring(flat.indexOf("public void handleUploadScanImage("),
                flat.indexOf("public void handleDownloadScanImage("));
        assertThat(scanUpload).doesNotContain("mediaType(").doesNotContain("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("a hard delete deletes the row's objects after the row, each failure logged at warning")
    void hardDeleteDeletesTheObjects() {
        String flat = noIndent(file(perEntity(order().build()), "OrderHandler"));

        assertThat(flat)
                .contains("service.delete(id);\ndeleteBlobObjects(id);\nexchange.respond(HttpStatus.NO_CONTENT);")
                .contains("try {\nblobStore.delete(new BlobRef(RECEIPT_CONTAINER, id + \"/receipt\"));\n"
                        + "} catch (RuntimeException e) {\nLOG.log(System.Logger.Level.WARNING,");
    }

    @Test
    @DisplayName("a graph-synced hard delete deletes the objects of a gone row when service.delete fails")
    void graphSyncedHardDeleteCleansUpAfterAFailedDelete() {
        String flat = noIndent(file(perEntity(order().dataScope(DataScope.GLOBAL)
                .graphMetadata(new GraphMetadata("Order", List.of(), List.of(), List.of())).build()),
                "OrderHandler"));

        assertThat(flat)
                .contains("try {\nservice.delete(id);\n} catch (RuntimeException e) {\n"
                        + "deleteBlobObjectsIfRowGone(id, e);\nthrow e;\n}\ndeleteBlobObjects(id);\n")
                .contains("private void deleteBlobObjectsIfRowGone(UUID id, RuntimeException failure) {\n"
                        + "try {\nif (service.findById(id).isEmpty()) {\ndeleteBlobObjects(id);\n}\n"
                        + "} catch (RuntimeException e) {\nfailure.addSuppressed(e);\n}\n}");
        assertThat(file(perEntity(order()
                .graphMetadata(new GraphMetadata("Order", List.of(), List.of(), List.of())).build()), "OrderHandler"))
                .as("a tenant-partitioned graph entity is not synced by its service")
                .doesNotContain("deleteBlobObjectsIfRowGone");
        assertThat(file(perEntity(order().dataScope(DataScope.GLOBAL).softDelete(true)
                .graphMetadata(new GraphMetadata("Order", List.of(), List.of(), List.of())).build()),
                "OrderHandler")).doesNotContain("deleteBlobObjectsIfRowGone");
    }

    @Test
    @DisplayName("a soft delete keeps the objects")
    void softDeleteKeepsTheObjects() {
        String handler = file(perEntity(order().softDelete(true).build()), "OrderHandler");

        assertThat(handler).doesNotContain("deleteBlobObjects").contains("handleDeleteReceipt");
    }

    @Test
    @DisplayName("composition passes the bound store, records the read, and registers the routes before the actions")
    void applicationWiresTheStore() {
        List<GeneratedFile> app = new KernelApplicationGenerator().generateAll(
                List.of(order().routeAccess(RouteAccess.AUTHENTICATED).build()), "com.example");
        String components = file(app, "RuntimeComponents");
        String lifecycle = noIndent(file(app, "RuntimeLifecycle"));
        String policy = noIndent(file(app, "GeneratedRoutePolicy"));

        assertThat(components)
                .contains("new OrderHandler(orderService(), KernelProviders.MEMORY_ALLOCATOR.get(), "
                        + "KernelProviders.BLOB_STORE.get())")
                .contains("List.of(KernelProviders.MEMORY_ALLOCATOR, KernelProviders.BLOB_STORE)")
                .contains("{@link KernelProviders#BLOB_STORE} — read by {@link #createOrderHandler()}");
        assertThat(lifecycle).contains(
                "routerBuilder.route(HttpMethod.DELETE, \"/orders/{id}\", orderHandler::handleDelete);\n"
                        + "routerBuilder.route(HttpMethod.PUT, \"/orders/{id}/blobs/receipt\", "
                        + "orderHandler::handleUploadReceipt);\n"
                        + "routerBuilder.route(HttpMethod.GET, \"/orders/{id}/blobs/receipt\", "
                        + "orderHandler::handleDownloadReceipt);\n"
                        + "routerBuilder.route(HttpMethod.DELETE, \"/orders/{id}/blobs/receipt\", "
                        + "orderHandler::handleDeleteReceipt);\n"
                        + "routerBuilder.route(HttpMethod.PUT, \"/orders/{id}/blobs/scanImage\", "
                        + "orderHandler::handleUploadScanImage);");
        assertThat(policy).contains("PUT_ROWS = {\nrow(\"/orders/{id}\", ORDER_ACCESS),\n"
                + "row(\"/orders/{id}/blobs/receipt\", ORDER_ACCESS),\n"
                + "row(\"/orders/{id}/blobs/scanImage\", ORDER_ACCESS)\n}");
        assertThat(file(app, "Application")).contains("\"http,persistence,security,storage,crypto\"");
    }

    @Test
    @DisplayName("an entity without a @Blob field is emitted exactly as an entity whose fields hold no blob facet")
    void entityWithoutBlobFieldIsUnchanged() {
        DomainMetadata plain = DomainMetadata.builder("Order", "com.example.domain")
                .path("/orders")
                .fields(List.of(FieldMetadata.simple("note", "String")))
                .build();
        List<GeneratedFile> files = new ArrayList<>(perEntity(plain));
        files.addAll(new KernelApplicationGenerator().generateAll(List.of(plain), "com.example"));

        for (GeneratedFile f : files) {
            assertThat(f.content()).as(f.className())
                    .doesNotContain("BlobStore").doesNotContain("BLOB_STORE").doesNotContain("/blobs/");
        }
        assertThat(file(files, "OrderHandler"))
                .contains("public OrderHandler(OrderService service, MemoryAllocator allocator) {");
    }

    @Test
    @DisplayName("the generated handler test builds the handler over an empty blob store")
    void handlerTestSuppliesAStore() {
        String test = new KernelHandlerTestGenerator().generate(order().build(), "com.example").content();

        assertThat(test)
                .contains("return new OrderHandler(service, allocator, new EmptyBlobStore());")
                .contains("private static final class EmptyBlobStore implements BlobStore");
    }

    @Test
    @DisplayName("OpenAPI: the field is a read-only null, out of both bodies, and its path carries four operations")
    void openApiDescribesTheRoutes() throws Exception {
        String yaml = new OpenApiGenerator().generateYaml(order().build());
        SwaggerParseResult parsed = new OpenAPIV3Parser().readContents(yaml);
        assertThat(parsed.getMessages()).as("the document parses without messages").isEmpty();
        assertThat(parsed.getOpenAPI().getPaths().get("/orders/{id}/blobs/receipt").getHead()).isNotNull();

        assertThat(yaml)
                .contains("/orders/{id}/blobs/receipt:")
                .contains("operationId: uploadOrderReceipt")
                .contains("operationId: downloadOrderReceipt")
                .contains("operationId: statOrderReceipt")
                .contains("operationId: deleteOrderReceipt")
                .contains("application/pdf:")
                .contains("format: binary")
                .contains("\"415\":")
                .contains("Content-Length:")
                .contains("type: \"null\"");
        String scanPath = yaml.substring(yaml.indexOf("/orders/{id}/blobs/scanImage:"));
        assertThat(scanPath.substring(0, scanPath.indexOf("operationId: deleteOrderScanImage")))
                .contains("application/octet-stream:").doesNotContain("\"415\":");
        String createDto = yaml.substring(yaml.indexOf("OrderCreateDto:"), yaml.indexOf("OrderUpdateDto:"));
        assertThat(createDto).doesNotContain("receipt").doesNotContain("scanImage");
        String updateDto = yaml.substring(yaml.indexOf("OrderUpdateDto:"), yaml.indexOf("OrderPage:"));
        assertThat(updateDto).doesNotContain("receipt").doesNotContain("scanImage");
    }
}
