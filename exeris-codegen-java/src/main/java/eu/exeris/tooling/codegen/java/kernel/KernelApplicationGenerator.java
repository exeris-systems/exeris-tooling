package eu.exeris.tooling.codegen.java.kernel;

import eu.exeris.tooling.codegen.core.generator.BackendGenerator;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import java.util.List;

/**
 * Generates complete Kernel application infrastructure from domain metadata.
 * <p>
 * Produces:
 * <ul>
 *   <li>{@code Application.java} - Entry point with Kernel bootstrap</li>
 *   <li>{@code CompositionRoot.java} - Manual DI wiring of all components</li>
 *   <li>{@code RouterConfig.java} - HTTP/3 route registration</li>
 * </ul>
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelApplicationGenerator implements BackendGenerator {

    /**
     * Generates Application.java - the entry point.
     */
    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        // This generator needs ALL domains, not just one
        // For single-domain generation, we create a minimal app
        return generateApplicationForSingleDomain(metadata);
    }

    /**
     * Generates complete application infrastructure from all domain metadata.
     * This is the main method for full application generation.
     */
    public List<GeneratedFile> generateAll(List<DomainMetadata> domains, String basePackage) {
        return List.of(
                generateApplication(domains, basePackage),
                generateCompositionRoot(domains, basePackage),
                generateRouterConfig(domains, basePackage)
        );
    }

    private GeneratedFile generateApplicationForSingleDomain(DomainMetadata metadata) {
        String basePackage = metadata.packageName().replace(".domain", "");
        String className = "Application";

        String code = generateApplicationCode(basePackage, List.of(metadata));
        return new GeneratedFile(basePackage, className, code, ArtifactType.CONFIGURATION);
    }

    private GeneratedFile generateApplication(List<DomainMetadata> domains, String basePackage) {
        String className = "Application";
        String code = generateApplicationCode(basePackage, domains);
        return new GeneratedFile(basePackage, className, code, ArtifactType.CONFIGURATION);
    }

    private String generateApplicationCode(String basePackage, List<DomainMetadata> domains) {
        StringBuilder sb = new StringBuilder();

        // Package and imports
        sb.append("package ").append(basePackage).append(";\n\n");
        sb.append("import eu.exeris.kernel.bootstrap.KernelBootstrap;\n");
        sb.append("import eu.exeris.kernel.config.KernelProfile;\n");
        sb.append("import eu.exeris.kernel.security.context.KernelContext;\n");
        sb.append("import eu.exeris.kernel.transport.carrier.CarrierConfig;\n");
        sb.append("import eu.exeris.kernel.transport.http3.server.Http3Router;\n");
        sb.append("import org.slf4j.Logger;\n");
        sb.append("import org.slf4j.LoggerFactory;\n\n");
        sb.append("import java.nio.file.Path;\n");
        sb.append("import java.util.concurrent.CountDownLatch;\n\n");

        // Javadoc
        sb.append("/**\n");
        sb.append(" * Generated Exeris Kernel Application Entry Point.\n");
        sb.append(" * <p>Generated from domain entities:\n");
        for (DomainMetadata domain : domains) {
            sb.append(" * <li>{@link ").append(domain.packageName()).append(".").append(domain.entityName()).append("}</li>\n");
        }
        sb.append(" *\n");
        sb.append(" * <p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");
        sb.append(" */\n");

        // Class declaration
        sb.append("public final class Application {\n\n");
        sb.append("    private static final Logger LOG = LoggerFactory.getLogger(Application.class);\n");
        sb.append("    private static final CountDownLatch SHUTDOWN_LATCH = new CountDownLatch(1);\n\n");

        // Main method
        sb.append("    public static void main(String[] args) {\n");
        sb.append("        LOG.info(\"═══════════════════════════════════════════════════════════════════\");\n");
        sb.append("        LOG.info(\"  Exeris Foundation - Generated Application\");\n");
        sb.append("        LOG.info(\"  Domains: ").append(domains.size()).append("\");\n");
        sb.append("        LOG.info(\"═══════════════════════════════════════════════════════════════════\");\n\n");

        sb.append("        try {\n");
        sb.append("            // 1. Load configuration from environment\n");
        sb.append("            KernelProfile profile = parseProfile(System.getenv().getOrDefault(\"EXERIS_PROFILE\", \"DEV\"));\n");
        sb.append("            int port = Integer.parseInt(System.getenv().getOrDefault(\"EXERIS_PORT\", \"8443\"));\n");
        sb.append("            String configPath = System.getenv().getOrDefault(\"EXERIS_CONFIG_PATH\", \"config\");\n");
        sb.append("            String certPath = System.getenv().getOrDefault(\"TLS_CERT_PATH\", \"config/tls/server.crt\");\n");
        sb.append("            String keyPath = System.getenv().getOrDefault(\"TLS_KEY_PATH\", \"config/tls/server.key\");\n\n");

        sb.append("            LOG.info(\"📦 Configuration: profile={}, port={}\", profile, port);\n\n");

        sb.append("            // 2. Build Kernel context\n");
        sb.append("            KernelContext context = KernelContext.builder()\n");
        sb.append("                    .profile(profile)\n");
        sb.append("                    .failurePolicy(KernelContext.FailurePolicy.FAIL_FAST)\n");
        sb.append("                    .configPath(Path.of(configPath))\n");
        sb.append("                    .build();\n\n");

        sb.append("            CarrierConfig carrierConfig = CarrierConfig.builder()\n");
        sb.append("                    .port(port)\n");
        sb.append("                    .certPath(certPath)\n");
        sb.append("                    .keyPath(keyPath)\n");
        sb.append("                    .build();\n\n");

        sb.append("            // 3. Bootstrap Kernel\n");
        sb.append("            KernelBootstrap kernel = KernelBootstrap.builder()\n");
        sb.append("                    .context(context)\n");
        sb.append("                    .transportConfig(carrierConfig)\n");
        sb.append("                    .build();\n\n");

        sb.append("            LOG.info(\"🚀 Initializing Kernel subsystems...\");\n");
        sb.append("            kernel.initialize();\n\n");

        sb.append("            // 4. Create Composition Root (Generated DI wiring)\n");
        sb.append("            LOG.info(\"🔧 Creating Composition Root...\");\n");
        sb.append("            CompositionRoot root = CompositionRoot.create(kernel);\n\n");

        sb.append("            // 5. Configure HTTP/3 Router (Generated routes)\n");
        sb.append("            LOG.info(\"🌐 Configuring HTTP/3 routes...\");\n");
        sb.append("            Http3Router router = RouterConfig.configure(root);\n");
        sb.append("            kernel.getTransportBootstrap().setHttp3Router(router);\n\n");

        sb.append("            // 6. Start Kernel\n");
        sb.append("            LOG.info(\"🎯 Starting Kernel...\");\n");
        sb.append("            kernel.start();\n\n");

        sb.append("            LOG.info(\"═══════════════════════════════════════════════════════════════════\");\n");
        sb.append("            LOG.info(\"  ✅ Application RUNNING on port {}\", port);\n");
        sb.append("            LOG.info(\"  📍 API: https://localhost:{}/api/v1\", port);\n");
        sb.append("            LOG.info(\"═══════════════════════════════════════════════════════════════════\");\n\n");

        sb.append("            // 7. Shutdown hook\n");
        sb.append("            Runtime.getRuntime().addShutdownHook(new Thread(() -> {\n");
        sb.append("                LOG.info(\"🛑 Shutting down...\");\n");
        sb.append("                try { kernel.shutdown(); } catch (Exception e) { LOG.error(\"Shutdown error\", e); }\n");
        sb.append("                SHUTDOWN_LATCH.countDown();\n");
        sb.append("            }));\n\n");

        sb.append("            SHUTDOWN_LATCH.await();\n\n");

        sb.append("        } catch (Exception e) {\n");
        sb.append("            LOG.error(\"❌ Fatal error\", e);\n");
        sb.append("            System.exit(1);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // Helper method
        sb.append("    private static KernelProfile parseProfile(String value) {\n");
        sb.append("        try { return KernelProfile.valueOf(value.toUpperCase()); }\n");
        sb.append("        catch (Exception e) { return KernelProfile.DEV; }\n");
        sb.append("    }\n");

        sb.append("}\n");

        return sb.toString();
    }

    private GeneratedFile generateCompositionRoot(List<DomainMetadata> domains, String basePackage) {
        String className = "CompositionRoot";
        StringBuilder sb = new StringBuilder();

        // Analyze what features we need
        boolean hasEvents = domains.stream().anyMatch(DomainMetadata::hasEvents);
        boolean hasSagas = domains.stream().anyMatch(DomainMetadata::isSaga);
        boolean hasGraph = domains.stream().anyMatch(DomainMetadata::hasGraphMetadata);

        // Package and imports
        sb.append("package ").append(basePackage).append(";\n\n");
        sb.append("import eu.exeris.kernel.bootstrap.KernelBootstrap;\n");
        sb.append("import org.slf4j.Logger;\n");
        sb.append("import org.slf4j.LoggerFactory;\n");
        sb.append("import javax.sql.DataSource;\n");

        // Event infrastructure imports
        if (hasEvents) {
            sb.append("import eu.exeris.kernel.events.store.EventStore;\n");
            sb.append("import eu.exeris.kernel.events.outbox.OutboxSignal;\n");
        }

        // Saga infrastructure imports
        if (hasSagas) {
            sb.append("import eu.exeris.kernel.flow.SagaEngine;\n");
        }

        // Graph infrastructure imports
        if (hasGraph) {
            sb.append("import eu.exeris.kernel.graph.GraphService;\n");
        }

        sb.append("\n");

        // Import generated classes for each domain
        for (DomainMetadata domain : domains) {
            String domainBase = domain.packageName().replace(".domain", "");
            String name = domain.entityName();
            sb.append("import ").append(domainBase).append(".repository.").append(name).append("Repository;\n");
            sb.append("import ").append(domainBase).append(".service.").append(name).append("Service;\n");
            sb.append("import ").append(domainBase).append(".handler.").append(name).append("Handler;\n");

            // Event imports
            if (domain.hasEvents()) {
                sb.append("import ").append(domainBase).append(".event.").append(name).append("Events;\n");
            }

            // Event handler imports (if has events AND saga)
            if (domain.hasEvents() && domain.isSaga()) {
                sb.append("import ").append(domainBase).append(".event.").append(name).append("EventHandler;\n");
            }

            // Saga imports
            if (domain.isSaga()) {
                String sagaName = domain.sagaMetadata().name() != null
                        ? domain.sagaMetadata().name()
                        : name + "Saga";
                sb.append("import ").append(domainBase).append(".saga.").append(sagaName).append(";\n");
            }

            // Graph imports
            if (domain.hasGraphMetadata()) {
                sb.append("import ").append(domainBase).append(".graph.").append(name).append("GraphSync;\n");
            }
        }
        sb.append("\n");

        // Javadoc
        sb.append("/**\n");
        sb.append(" * Generated Composition Root - Manual DI wiring.\n");
        sb.append(" * <p>Wires all generated components for ").append(domains.size()).append(" domain(s).\n");
        if (hasEvents) sb.append(" * <p>Includes: Event Store, Event Publishers\n");
        if (hasSagas) sb.append(" * <p>Includes: Saga Engine, Saga Orchestrators\n");
        if (hasGraph) sb.append(" * <p>Includes: Graph Synchronization\n");
        sb.append(" * <p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");
        sb.append(" */\n");

        // Class
        sb.append("public final class CompositionRoot {\n\n");
        sb.append("    private static final Logger LOG = LoggerFactory.getLogger(CompositionRoot.class);\n\n");

        // ═══════════════════════════════════════════════════════════════════
        // Fields
        // ═══════════════════════════════════════════════════════════════════
        sb.append("    // Infrastructure\n");
        sb.append("    private final DataSource dataSource;\n");
        if (hasEvents) {
            sb.append("    private final EventStore eventStore;\n");
            sb.append("    private final OutboxSignal outboxSignal;\n");
        }
        if (hasSagas) {
            sb.append("    private final SagaEngine sagaEngine;\n");
        }
        if (hasGraph) {
            sb.append("    private final GraphService graphService;\n");
        }
        sb.append("\n");

        sb.append("    // Repositories\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            sb.append("    private final ").append(name).append("Repository ").append(toLowerFirst(name)).append("Repository;\n");
        }
        sb.append("\n");

        sb.append("    // Services\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            sb.append("    private final ").append(name).append("Service ").append(toLowerFirst(name)).append("Service;\n");
        }
        sb.append("\n");

        sb.append("    // Handlers\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            sb.append("    private final ").append(name).append("Handler ").append(toLowerFirst(name)).append("Handler;\n");
        }
        sb.append("\n");

        // Event publishers
        if (hasEvents) {
            sb.append("    // Event Publishers\n");
            for (DomainMetadata domain : domains) {
                if (domain.hasEvents()) {
                    String name = domain.entityName();
                    sb.append("    private final ").append(name).append("Events.Publisher ").append(toLowerFirst(name)).append("EventPublisher;\n");
                }
            }
            sb.append("\n");
        }

        // Sagas
        if (hasSagas) {
            sb.append("    // Sagas\n");
            for (DomainMetadata domain : domains) {
                if (domain.isSaga()) {
                    String name = domain.entityName();
                    String sagaName = domain.sagaMetadata().name() != null
                            ? domain.sagaMetadata().name()
                            : name + "Saga";
                    sb.append("    private final ").append(sagaName).append(" ").append(toLowerFirst(name)).append("Saga;\n");
                }
            }
            sb.append("\n");

            // Event Handlers (only for entities with both events and saga)
            sb.append("    // Event Handlers (trigger sagas)\n");
            for (DomainMetadata domain : domains) {
                if (domain.hasEvents() && domain.isSaga()) {
                    String name = domain.entityName();
                    sb.append("    private final ").append(name).append("EventHandler ").append(toLowerFirst(name)).append("EventHandler;\n");
                }
            }
            sb.append("\n");
        }

        // Graph syncs
        if (hasGraph) {
            sb.append("    // Graph Sync\n");
            for (DomainMetadata domain : domains) {
                if (domain.hasGraphMetadata()) {
                    String name = domain.entityName();
                    sb.append("    private final ").append(name).append("GraphSync ").append(toLowerFirst(name)).append("GraphSync;\n");
                }
            }
            sb.append("\n");
        }

        // ═══════════════════════════════════════════════════════════════════
        // Constructor
        // ═══════════════════════════════════════════════════════════════════
        sb.append("    private CompositionRoot(KernelBootstrap kernel) {\n");
        sb.append("        LOG.info(\"🔧 Wiring generated components...\");\n\n");

        sb.append("        // DataSource\n");
        sb.append("        this.dataSource = kernel.getPersistenceBootstrap() != null \n");
        sb.append("                ? kernel.getPersistenceBootstrap().getDataSource() \n");
        sb.append("                : null;\n");
        sb.append("        if (dataSource == null) {\n");
        sb.append("            LOG.warn(\"⚠️ DataSource is null - degraded mode\");\n");
        sb.append("        }\n\n");

        // Event infrastructure
        if (hasEvents) {
            sb.append("        // Event Infrastructure\n");
            sb.append("        var eventsBootstrap = kernel.getEventsBootstrap();\n");
            sb.append("        this.eventStore = eventsBootstrap != null ? eventsBootstrap.getEventStore() : null;\n");
            sb.append("        this.outboxSignal = eventsBootstrap != null ? eventsBootstrap.getOutboxSignal() : null;\n");
            sb.append("        LOG.info(\"✅ Event Infrastructure wired\");\n\n");
        }

        // Saga infrastructure
        if (hasSagas) {
            sb.append("        // Saga Infrastructure\n");
            sb.append("        var flowBootstrap = kernel.getFlowBootstrap();\n");
            sb.append("        this.sagaEngine = flowBootstrap != null ? flowBootstrap.getSagaEngine() : null;\n");
            sb.append("        LOG.info(\"✅ Saga Engine wired\");\n\n");
        }

        // Graph infrastructure
        if (hasGraph) {
            sb.append("        // Graph Infrastructure\n");
            sb.append("        this.graphService = kernel.getGraphBootstrap() != null \n");
            sb.append("                ? kernel.getGraphBootstrap().getGraphService() \n");
            sb.append("                : null;\n");
            sb.append("        LOG.info(\"✅ Graph Service wired\");\n\n");
        }

        sb.append("        // Repositories\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            sb.append("        this.").append(lower).append("Repository = new ").append(name).append("Repository(dataSource);\n");
        }
        sb.append("        LOG.info(\"✅ Repositories wired\");\n\n");

        // Event publishers (before services, as services use them)
        if (hasEvents) {
            sb.append("        // Event Publishers\n");
            for (DomainMetadata domain : domains) {
                if (domain.hasEvents()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    sb.append("        this.").append(lower).append("EventPublisher = new ").append(name).append("Events.Publisher(eventStore, outboxSignal);\n");
                }
            }
            sb.append("        LOG.info(\"✅ Event Publishers wired\");\n\n");
        }

        sb.append("        // Services\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            if (domain.hasEvents()) {
                // Service with event publisher
                sb.append("        this.").append(lower).append("Service = new ").append(name).append("Service(").append(lower).append("Repository, ").append(lower).append("EventPublisher);\n");
            } else {
                sb.append("        this.").append(lower).append("Service = new ").append(name).append("Service(").append(lower).append("Repository);\n");
            }
        }
        sb.append("        LOG.info(\"✅ Services wired\");\n\n");

        sb.append("        // Handlers\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            sb.append("        this.").append(lower).append("Handler = new ").append(name).append("Handler(").append(lower).append("Service);\n");
        }
        sb.append("        LOG.info(\"✅ Handlers wired\");\n\n");

        // Sagas
        if (hasSagas) {
            sb.append("        // Sagas\n");
            for (DomainMetadata domain : domains) {
                if (domain.isSaga()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    String sagaName = domain.sagaMetadata().name() != null
                            ? domain.sagaMetadata().name()
                            : name + "Saga";
                    sb.append("        this.").append(lower).append("Saga = new ").append(sagaName).append("(sagaEngine, eventStore, ").append(lower).append("Repository);\n");
                }
            }
            sb.append("        LOG.info(\"✅ Sagas wired\");\n\n");

            // Event Handlers (trigger sagas)
            sb.append("        // Event Handlers (saga triggers)\n");
            for (DomainMetadata domain : domains) {
                if (domain.hasEvents() && domain.isSaga()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    sb.append("        this.").append(lower).append("EventHandler = new ").append(name).append("EventHandler(").append(lower).append("Saga, eventStore);\n");
                }
            }
            sb.append("        LOG.info(\"✅ Event Handlers wired\");\n\n");
        }

        // Graph syncs
        if (hasGraph) {
            sb.append("        // Graph Sync\n");
            for (DomainMetadata domain : domains) {
                if (domain.hasGraphMetadata()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    sb.append("        this.").append(lower).append("GraphSync = new ").append(name).append("GraphSync(graphService);\n");
                }
            }
            sb.append("        LOG.info(\"✅ Graph Sync wired\");\n\n");
        }

        sb.append("        LOG.info(\"🎯 Composition Root complete: ").append(domains.size()).append(" domain(s)\");\n");
        sb.append("    }\n\n");

        // ═══════════════════════════════════════════════════════════════════
        // Factory method
        // ═══════════════════════════════════════════════════════════════════
        sb.append("    public static CompositionRoot create(KernelBootstrap kernel) {\n");
        sb.append("        return new CompositionRoot(kernel);\n");
        sb.append("    }\n\n");

        // ═══════════════════════════════════════════════════════════════════
        // Getters
        // ═══════════════════════════════════════════════════════════════════

        // Infrastructure getters
        if (hasEvents) {
            sb.append("    public EventStore eventStore() { return eventStore; }\n");
        }
        if (hasSagas) {
            sb.append("    public SagaEngine sagaEngine() { return sagaEngine; }\n");
        }
        if (hasGraph) {
            sb.append("    public GraphService graphService() { return graphService; }\n");
        }
        sb.append("\n");

        // Handler getters
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            sb.append("    public ").append(name).append("Handler ").append(lower).append("Handler() { return ").append(lower).append("Handler; }\n");
        }
        sb.append("\n");

        // Service getters
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            sb.append("    public ").append(name).append("Service ").append(lower).append("Service() { return ").append(lower).append("Service; }\n");
        }
        sb.append("\n");

        // Event publisher getters
        if (hasEvents) {
            for (DomainMetadata domain : domains) {
                if (domain.hasEvents()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    sb.append("    public ").append(name).append("Events.Publisher ").append(lower).append("EventPublisher() { return ").append(lower).append("EventPublisher; }\n");
                }
            }
            sb.append("\n");
        }

        // Saga getters
        if (hasSagas) {
            for (DomainMetadata domain : domains) {
                if (domain.isSaga()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    String sagaName = domain.sagaMetadata().name() != null
                            ? domain.sagaMetadata().name()
                            : name + "Saga";
                    sb.append("    public ").append(sagaName).append(" ").append(lower).append("Saga() { return ").append(lower).append("Saga; }\n");
                }
            }
            sb.append("\n");

            // Event Handler getters
            for (DomainMetadata domain : domains) {
                if (domain.hasEvents() && domain.isSaga()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    sb.append("    public ").append(name).append("EventHandler ").append(lower).append("EventHandler() { return ").append(lower).append("EventHandler; }\n");
                }
            }
            sb.append("\n");
        }

        // Graph sync getters
        if (hasGraph) {
            for (DomainMetadata domain : domains) {
                if (domain.hasGraphMetadata()) {
                    String name = domain.entityName();
                    String lower = toLowerFirst(name);
                    sb.append("    public ").append(name).append("GraphSync ").append(lower).append("GraphSync() { return ").append(lower).append("GraphSync; }\n");
                }
            }
        }

        sb.append("}\n");

        return new GeneratedFile(basePackage, className, sb.toString(), ArtifactType.CONFIGURATION);
    }

    private GeneratedFile generateRouterConfig(List<DomainMetadata> domains, String basePackage) {
        String className = "RouterConfig";
        StringBuilder sb = new StringBuilder();

        // Package and imports
        sb.append("package ").append(basePackage).append(";\n\n");
        sb.append("import eu.exeris.kernel.transport.http3.server.Http3Router;\n");
        sb.append("import org.slf4j.Logger;\n");
        sb.append("import org.slf4j.LoggerFactory;\n\n");

        // Javadoc
        sb.append("/**\n");
        sb.append(" * Generated HTTP/3 Router Configuration.\n");
        sb.append(" * <p>Registers routes for ").append(domains.size()).append(" domain(s).\n");
        sb.append(" * <p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");
        sb.append(" */\n");

        // Class
        sb.append("public final class RouterConfig {\n\n");
        sb.append("    private static final Logger LOG = LoggerFactory.getLogger(RouterConfig.class);\n\n");

        sb.append("    private RouterConfig() {}\n\n");

        // Configure method
        sb.append("    public static Http3Router configure(CompositionRoot root) {\n");
        sb.append("        Http3Router router = new Http3Router();\n\n");

        // Health check
        sb.append("        // Health check\n");
        sb.append("        router.register(\"GET\", \"/health\", exchange -> {\n");
        sb.append("            exchange.response().sendHeaders(200, null);\n");
        sb.append("            exchange.response().sendText(\"{\\\"status\\\":\\\"UP\\\"}\");\n");
        sb.append("        });\n\n");

        // API info
        sb.append("        // API info\n");
        sb.append("        router.register(\"GET\", \"/api/v1\", exchange -> {\n");
        sb.append("            exchange.response().sendHeaders(200, null);\n");
        sb.append("            exchange.response().sendText(\"{\\\"name\\\":\\\"Exeris Foundation API\\\",\\\"version\\\":\\\"v1\\\",\\\"domains\\\":").append(domains.size()).append("}\");\n");
        sb.append("        });\n\n");

        // Routes for each domain
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String path = domain.effectivePath() != null && !domain.effectivePath().isEmpty() ? domain.effectivePath() : "/" + toKebabCase(name) + "s";
            String apiPath = "/api/v1" + path;

            sb.append("        // ═══ ").append(name).append(" routes ═══\n");
            sb.append("        router.register(\"GET\", \"").append(apiPath).append("\", root.").append(lower).append("Handler()::handleGetAll);\n");
            sb.append("        router.register(\"GET\", \"").append(apiPath).append("/*\", root.").append(lower).append("Handler()::handleGetById);\n");
            sb.append("        router.register(\"POST\", \"").append(apiPath).append("\", root.").append(lower).append("Handler()::handleCreate);\n");
            sb.append("        router.register(\"PUT\", \"").append(apiPath).append("/*\", root.").append(lower).append("Handler()::handleUpdate);\n");
            sb.append("        router.register(\"DELETE\", \"").append(apiPath).append("/*\", root.").append(lower).append("Handler()::handleDelete);\n");
            sb.append("        LOG.info(\"📍 Registered: CRUD ").append(apiPath).append("\");\n\n");
        }

        sb.append("        return router;\n");
        sb.append("    }\n");
        sb.append("}\n");

        return new GeneratedFile(basePackage, className, sb.toString(), ArtifactType.CONFIGURATION);
    }

    private String toLowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private String toKebabCase(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.CONFIGURATION;
    }
}

