package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

/**
 * Kernel Handler Generator.
 * <p>
 * Generates HTTP handlers for Exeris Kernel runtime (HTTP/3).
 * Handlers receive requests from Http3ServerExchange and delegate to services.
 *
 * @author Exeris Team
 * @since 0.2.0
 */
public class KernelHandlerGenerator implements KernelArtifactGenerator {

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String packageName = basePackage + ".handler";
        String className = metadata.entityName() + "Handler";
        String entity = metadata.entityName();
        String entityLower = toLowerFirst(entity);
        String serviceName = entity + "Service";

        StringBuilder code = new StringBuilder();

        // Package and imports
        code.append("package ").append(packageName).append(";\n\n");
        code.append("import ").append(metadata.packageName()).append(".").append(entity).append(";\n");
        code.append("import ").append(basePackage).append(".service.").append(serviceName).append(";\n");
        code.append("import eu.exeris.kernel.transport.http3.server.Http3ServerExchange;\n");
        code.append("import com.fasterxml.jackson.databind.ObjectMapper;\n");
        code.append("import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;\n");
        code.append("import org.slf4j.Logger;\n");
        code.append("import org.slf4j.LoggerFactory;\n\n");
        code.append("import java.util.List;\n");
        code.append("import java.util.Map;\n");
        code.append("import java.util.UUID;\n\n");

        // Javadoc
        code.append("/**\n");
        code.append(" * Generated HTTP Handler for ").append(entity).append(".\n");
        code.append(" * <p>Source: {@link ").append(metadata.packageName()).append(".").append(entity).append("}\n");
        code.append(" * <p>Path: ").append(metadata.effectivePath()).append("\n");
        code.append(" * <p><b>DO NOT EDIT</b> - Regenerate from domain model.\n");
        code.append(" */\n");

        // Class
        code.append("public class ").append(className).append(" {\n\n");
        code.append("    private static final Logger LOG = LoggerFactory.getLogger(").append(className).append(".class);\n");
        code.append("    private static final ObjectMapper MAPPER = createMapper();\n\n");
        code.append("    private final ").append(serviceName).append(" service;\n\n");

        // Constructor
        code.append("    public ").append(className).append("(").append(serviceName).append(" service) {\n");
        code.append("        this.service = service;\n");
        code.append("    }\n\n");

        // handleGetAll
        code.append("    public void handleGetAll(Http3ServerExchange exchange) throws Exception {\n");
        code.append("        LOG.debug(\"GET all ").append(entityLower).append("s\");\n");
        code.append("        try {\n");
        code.append("            List<").append(entity).append("> entities = service.findAll();\n");
        code.append("            sendJson(exchange, 200, entities);\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to get all ").append(entityLower).append("s\", e);\n");
        code.append("            sendError(exchange, 500, \"Internal error\");\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // handleGetById
        code.append("    public void handleGetById(Http3ServerExchange exchange) throws Exception {\n");
        code.append("        String idStr = extractPathId(exchange);\n");
        code.append("        LOG.debug(\"GET ").append(entityLower).append(" by id: {}\", idStr);\n");
        code.append("        try {\n");
        code.append("            UUID id = UUID.fromString(idStr);\n");
        code.append("            service.findById(id).ifPresentOrElse(\n");
        code.append("                entity -> sendJson(exchange, 200, entity),\n");
        code.append("                () -> sendError(exchange, 404, \"Not found\")\n");
        code.append("            );\n");
        code.append("        } catch (IllegalArgumentException e) {\n");
        code.append("            sendError(exchange, 400, \"Invalid UUID\");\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to get ").append(entityLower).append("\", e);\n");
        code.append("            sendError(exchange, 500, \"Internal error\");\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // handleCreate
        code.append("    public void handleCreate(Http3ServerExchange exchange) throws Exception {\n");
        code.append("        LOG.debug(\"POST create ").append(entityLower).append("\");\n");
        code.append("        try {\n");
        code.append("            String body = readBody(exchange);\n");
        code.append("            ").append(entity).append(" entity = MAPPER.readValue(body, ").append(entity).append(".class);\n");
        code.append("            ").append(entity).append(" saved = service.save(entity);\n");
        code.append("            sendJson(exchange, 201, saved);\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to create ").append(entityLower).append("\", e);\n");
        code.append("            sendError(exchange, 500, \"Internal error\");\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // handleUpdate
        code.append("    public void handleUpdate(Http3ServerExchange exchange) throws Exception {\n");
        code.append("        String idStr = extractPathId(exchange);\n");
        code.append("        LOG.debug(\"PUT update ").append(entityLower).append(" id: {}\", idStr);\n");
        code.append("        try {\n");
        code.append("            UUID id = UUID.fromString(idStr);\n");
        code.append("            String body = readBody(exchange);\n");
        code.append("            ").append(entity).append(" entity = MAPPER.readValue(body, ").append(entity).append(".class);\n");
        code.append("            ").append(entity).append(" updated = service.update(id, entity);\n");
        code.append("            sendJson(exchange, 200, updated);\n");
        code.append("        } catch (IllegalArgumentException e) {\n");
        code.append("            sendError(exchange, 400, \"Invalid UUID\");\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to update ").append(entityLower).append("\", e);\n");
        code.append("            sendError(exchange, 500, \"Internal error\");\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // handleDelete
        code.append("    public void handleDelete(Http3ServerExchange exchange) throws Exception {\n");
        code.append("        String idStr = extractPathId(exchange);\n");
        code.append("        LOG.debug(\"DELETE ").append(entityLower).append(" id: {}\", idStr);\n");
        code.append("        try {\n");
        code.append("            UUID id = UUID.fromString(idStr);\n");
        code.append("            service.delete(id);\n");
        code.append("            exchange.response().sendHeaders(204, null);\n");
        code.append("        } catch (IllegalArgumentException e) {\n");
        code.append("            sendError(exchange, 400, \"Invalid UUID\");\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to delete ").append(entityLower).append("\", e);\n");
        code.append("            sendError(exchange, 500, \"Internal error\");\n");
        code.append("        }\n");
        code.append("    }\n\n");

        // Helper methods
        code.append("    // ═══════════════════════════════════════════════════════════════════\n");
        code.append("    // Helper methods\n");
        code.append("    // ═══════════════════════════════════════════════════════════════════\n\n");

        code.append("    private String extractPathId(Http3ServerExchange exchange) {\n");
        code.append("        String path = exchange.request().uri().getPath();\n");
        code.append("        int lastSlash = path.lastIndexOf('/');\n");
        code.append("        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;\n");
        code.append("    }\n\n");

        code.append("    private String readBody(Http3ServerExchange exchange) throws Exception {\n");
        code.append("        // Read body bytes and convert to string\n");
        code.append("        byte[] bytes = exchange.request().bodyAsBytes();\n");
        code.append("        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);\n");
        code.append("    }\n\n");

        code.append("    private void sendJson(Http3ServerExchange exchange, int status, Object data) {\n");
        code.append("        try {\n");
        code.append("            String json = MAPPER.writeValueAsString(data);\n");
        code.append("            exchange.response().sendHeaders(status, null);\n");
        code.append("            exchange.response().sendText(json);\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to serialize response\", e);\n");
        code.append("            sendError(exchange, 500, \"Serialization error\");\n");
        code.append("        }\n");
        code.append("    }\n\n");

        code.append("    private void sendError(Http3ServerExchange exchange, int status, String message) {\n");
        code.append("        try {\n");
        code.append("            String json = MAPPER.writeValueAsString(Map.of(\"error\", message));\n");
        code.append("            exchange.response().sendHeaders(status, null);\n");
        code.append("            exchange.response().sendText(json);\n");
        code.append("        } catch (Exception e) {\n");
        code.append("            LOG.error(\"Failed to send error response\", e);\n");
        code.append("        }\n");
        code.append("    }\n\n");

        code.append("    private static ObjectMapper createMapper() {\n");
        code.append("        ObjectMapper mapper = new ObjectMapper();\n");
        code.append("        mapper.registerModule(new JavaTimeModule());\n");
        code.append("        return mapper;\n");
        code.append("    }\n");

        code.append("}\n");

        return new GeneratedFile(packageName, className, code.toString(), ArtifactType.CONTROLLER);
    }

    private String toLowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.CONTROLLER;
    }
}

