package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
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
 * <p>Phase 4e of ADR-015: emission is JavaPoet-based via Palantir's fork.
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelApplicationGenerator implements KernelArtifactGenerator {

    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName KERNEL_BOOTSTRAP = ClassName.get("eu.exeris.kernel.bootstrap", "KernelBootstrap");
    private static final ClassName KERNEL_PROFILE = ClassName.get("eu.exeris.kernel.config", "KernelProfile");
    private static final ClassName KERNEL_CONTEXT = ClassName.get("eu.exeris.kernel.security.context", "KernelContext");
    private static final ClassName CARRIER_CONFIG = ClassName.get("eu.exeris.kernel.transport.carrier", "CarrierConfig");
    private static final ClassName HTTP3_ROUTER = ClassName.get("eu.exeris.kernel.transport.http3.server", "Http3Router");
    private static final ClassName PATH = ClassName.get("java.nio.file", "Path");
    private static final ClassName COUNT_DOWN_LATCH = ClassName.get("java.util.concurrent", "CountDownLatch");
    private static final ClassName DATASOURCE = ClassName.get("javax.sql", "DataSource");
    private static final ClassName EVENT_STORE = ClassName.get("eu.exeris.kernel.events.store", "EventStore");
    private static final ClassName OUTBOX_SIGNAL = ClassName.get("eu.exeris.kernel.events.outbox", "OutboxSignal");
    private static final ClassName SAGA_ENGINE = ClassName.get("eu.exeris.kernel.flow", "SagaEngine");
    private static final ClassName GRAPH_SERVICE = ClassName.get("eu.exeris.kernel.graph", "GraphService");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        // Single-domain path emits Application.java only.
        String basePackage = metadata.packageName().replace(".domain", "");
        return generateApplication(List.of(metadata), basePackage);
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

    private GeneratedFile generateApplication(List<DomainMetadata> domains, String basePackage) {
        String className = "Application";
        ClassName selfType = ClassName.get(basePackage, className);
        ClassName compositionRootType = ClassName.get(basePackage, "CompositionRoot");
        ClassName routerConfigType = ClassName.get(basePackage, "RouterConfig");
        ClassName failurePolicyType = KERNEL_CONTEXT.nestedClass("FailurePolicy");

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated Exeris Kernel Application Entry Point.\n")
                .addJavadoc("<p>Generated from domain entities:\n");
        for (DomainMetadata domain : domains) {
            type.addJavadoc("<li>{@link $L.$L}</li>\n", domain.packageName(), domain.entityName());
        }
        type.addJavadoc("\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(COUNT_DOWN_LATCH, "SHUTDOWN_LATCH",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T(1)", COUNT_DOWN_LATCH)
                        .build());

        String banner = "═══════════════════════════════════════════════════════════════════";

        CodeBlock.Builder body = CodeBlock.builder()
                .addStatement("LOG.info($S)", banner)
                .addStatement("LOG.info($S)", "  Exeris Foundation - Generated Application")
                .addStatement("LOG.info($S)", "  Domains: " + domains.size())
                .addStatement("LOG.info($S)", banner)
                .add("\n")
                .beginControlFlow("try")
                .add("// 1. Load configuration from environment\n")
                .addStatement("$T profile = parseProfile($T.getenv().getOrDefault($S, $S))",
                        KERNEL_PROFILE, System.class, "EXERIS_PROFILE", "DEV")
                .addStatement("int port = $T.parseInt($T.getenv().getOrDefault($S, $S))",
                        Integer.class, System.class, "EXERIS_PORT", "8443")
                .addStatement("String configPath = $T.getenv().getOrDefault($S, $S)",
                        System.class, "EXERIS_CONFIG_PATH", "config")
                .addStatement("String certPath = $T.getenv().getOrDefault($S, $S)",
                        System.class, "TLS_CERT_PATH", "config/tls/server.crt")
                .addStatement("String keyPath = $T.getenv().getOrDefault($S, $S)",
                        System.class, "TLS_KEY_PATH", "config/tls/server.key")
                .add("\n")
                .addStatement("LOG.info($S, profile, port)", "📦 Configuration: profile={}, port={}")
                .add("\n")
                .add("// 2. Build Kernel context\n")
                .add("$T context = $T.builder()\n", KERNEL_CONTEXT, KERNEL_CONTEXT)
                .add("        .profile(profile)\n")
                .add("        .failurePolicy($T.FAIL_FAST)\n", failurePolicyType)
                .add("        .configPath($T.of(configPath))\n", PATH)
                .add("        .build();\n")
                .add("\n")
                .add("$T carrierConfig = $T.builder()\n", CARRIER_CONFIG, CARRIER_CONFIG)
                .add("        .port(port)\n")
                .add("        .certPath(certPath)\n")
                .add("        .keyPath(keyPath)\n")
                .add("        .build();\n")
                .add("\n")
                .add("// 3. Bootstrap Kernel\n")
                .add("$T kernel = $T.builder()\n", KERNEL_BOOTSTRAP, KERNEL_BOOTSTRAP)
                .add("        .context(context)\n")
                .add("        .transportConfig(carrierConfig)\n")
                .add("        .build();\n")
                .add("\n")
                .addStatement("LOG.info($S)", "🚀 Initializing Kernel subsystems...")
                .addStatement("kernel.initialize()")
                .add("\n")
                .add("// 4. Create Composition Root (Generated DI wiring)\n")
                .addStatement("LOG.info($S)", "🔧 Creating Composition Root...")
                .addStatement("$T root = $T.create(kernel)", compositionRootType, compositionRootType)
                .add("\n")
                .add("// 5. Configure HTTP/3 Router (Generated routes)\n")
                .addStatement("LOG.info($S)", "🌐 Configuring HTTP/3 routes...")
                .addStatement("$T router = $T.configure(root)", HTTP3_ROUTER, routerConfigType)
                .addStatement("kernel.getTransportBootstrap().setHttp3Router(router)")
                .add("\n")
                .add("// 6. Start Kernel\n")
                .addStatement("LOG.info($S)", "🎯 Starting Kernel...")
                .addStatement("kernel.start()")
                .add("\n")
                .addStatement("LOG.info($S)", banner)
                .addStatement("LOG.info($S, port)", "  ✅ Application RUNNING on port {}")
                .addStatement("LOG.info($S, port)", "  📍 API: https://localhost:{}/api/v1")
                .addStatement("LOG.info($S)", banner)
                .add("\n")
                .add("// 7. Shutdown hook\n")
                .add("$T.getRuntime().addShutdownHook(new $T(() -> {\n", Runtime.class, Thread.class)
                .add("    LOG.info($S);\n", "🛑 Shutting down...")
                .add("    try { kernel.shutdown(); } catch ($T e) { LOG.error($S, e); }\n",
                        Exception.class, "Shutdown error")
                .add("    SHUTDOWN_LATCH.countDown();\n")
                .add("}));\n")
                .add("\n")
                .addStatement("SHUTDOWN_LATCH.await()")
                .nextControlFlow("catch ($T e)", Exception.class)
                .addStatement("LOG.error($S, e)", "❌ Fatal error")
                .addStatement("$T.exit(1)", System.class)
                .endControlFlow();

        type.addMethod(MethodSpec.methodBuilder("main")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(String[].class, "args")
                .addCode(body.build())
                .build());

        type.addMethod(MethodSpec.methodBuilder("parseProfile")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(KERNEL_PROFILE)
                .addParameter(String.class, "value")
                .addCode("try { return $T.valueOf(value.toUpperCase()); }\n", KERNEL_PROFILE)
                .addCode("catch ($T e) { return $T.DEV; }\n", Exception.class, KERNEL_PROFILE)
                .build());

        return new GeneratedFile(basePackage, className,
                KernelScaffold.render(basePackage, type.build()), ArtifactType.CONFIGURATION);
    }

    private GeneratedFile generateCompositionRoot(List<DomainMetadata> domains, String basePackage) {
        String className = "CompositionRoot";
        ClassName selfType = ClassName.get(basePackage, className);

        boolean hasEvents = domains.stream().anyMatch(DomainMetadata::hasEvents);
        boolean hasSagas = domains.stream().anyMatch(DomainMetadata::isSaga);
        boolean hasGraph = domains.stream().anyMatch(DomainMetadata::hasGraphMetadata);

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated Composition Root - Manual DI wiring.\n")
                .addJavadoc("<p>Wires all generated components for $L domain(s).\n", domains.size());
        if (hasEvents) type.addJavadoc("<p>Includes: Event Store, Event Publishers\n");
        if (hasSagas) type.addJavadoc("<p>Includes: Saga Engine, Saga Orchestrators\n");
        if (hasGraph) type.addJavadoc("<p>Includes: Graph Synchronization\n");
        type.addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                .build());

        // Infrastructure fields
        type.addField(FieldSpec.builder(DATASOURCE, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build());
        if (hasEvents) {
            type.addField(FieldSpec.builder(EVENT_STORE, "eventStore", Modifier.PRIVATE, Modifier.FINAL).build());
            type.addField(FieldSpec.builder(OUTBOX_SIGNAL, "outboxSignal", Modifier.PRIVATE, Modifier.FINAL).build());
        }
        if (hasSagas) {
            type.addField(FieldSpec.builder(SAGA_ENGINE, "sagaEngine", Modifier.PRIVATE, Modifier.FINAL).build());
        }
        if (hasGraph) {
            type.addField(FieldSpec.builder(GRAPH_SERVICE, "graphService", Modifier.PRIVATE, Modifier.FINAL).build());
        }

        // Per-domain fields
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            type.addField(FieldSpec.builder(
                    ClassName.get(domainBase + ".repository", name + "Repository"),
                    lower + "Repository", Modifier.PRIVATE, Modifier.FINAL).build());
        }
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            type.addField(FieldSpec.builder(
                    ClassName.get(domainBase + ".service", name + "Service"),
                    lower + "Service", Modifier.PRIVATE, Modifier.FINAL).build());
        }
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            type.addField(FieldSpec.builder(
                    ClassName.get(domainBase + ".handler", name + "Handler"),
                    lower + "Handler", Modifier.PRIVATE, Modifier.FINAL).build());
        }
        if (hasEvents) {
            for (DomainMetadata domain : domains) {
                if (!domain.hasEvents()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                ClassName publisherType = ClassName.get(domainBase + ".event", name + "Events", "Publisher");
                type.addField(FieldSpec.builder(publisherType,
                        lower + "EventPublisher", Modifier.PRIVATE, Modifier.FINAL).build());
            }
        }
        if (hasSagas) {
            for (DomainMetadata domain : domains) {
                if (!domain.isSaga()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                String sagaName = sagaTypeName(domain);
                type.addField(FieldSpec.builder(
                        ClassName.get(domainBase + ".saga", sagaName),
                        lower + "Saga", Modifier.PRIVATE, Modifier.FINAL).build());
            }
            for (DomainMetadata domain : domains) {
                if (!(domain.hasEvents() && domain.isSaga())) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                type.addField(FieldSpec.builder(
                        ClassName.get(domainBase + ".event", name + "EventHandler"),
                        lower + "EventHandler", Modifier.PRIVATE, Modifier.FINAL).build());
            }
        }
        if (hasGraph) {
            for (DomainMetadata domain : domains) {
                if (!domain.hasGraphMetadata()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                type.addField(FieldSpec.builder(
                        ClassName.get(domainBase + ".graph", name + "GraphSync"),
                        lower + "GraphSync", Modifier.PRIVATE, Modifier.FINAL).build());
            }
        }

        // Constructor
        type.addMethod(buildCompositionRootConstructor(domains, hasEvents, hasSagas, hasGraph));

        // Factory
        type.addMethod(MethodSpec.methodBuilder("create")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(selfType)
                .addParameter(KERNEL_BOOTSTRAP, "kernel")
                .addStatement("return new $T(kernel)", selfType)
                .build());

        // Getters
        if (hasEvents) {
            type.addMethod(simpleGetter(EVENT_STORE, "eventStore"));
        }
        if (hasSagas) {
            type.addMethod(simpleGetter(SAGA_ENGINE, "sagaEngine"));
        }
        if (hasGraph) {
            type.addMethod(simpleGetter(GRAPH_SERVICE, "graphService"));
        }
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            type.addMethod(simpleGetter(
                    ClassName.get(domainBase + ".handler", name + "Handler"), lower + "Handler"));
        }
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            type.addMethod(simpleGetter(
                    ClassName.get(domainBase + ".service", name + "Service"), lower + "Service"));
        }
        if (hasEvents) {
            for (DomainMetadata domain : domains) {
                if (!domain.hasEvents()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                type.addMethod(simpleGetter(
                        ClassName.get(domainBase + ".event", name + "Events", "Publisher"),
                        lower + "EventPublisher"));
            }
        }
        if (hasSagas) {
            for (DomainMetadata domain : domains) {
                if (!domain.isSaga()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                type.addMethod(simpleGetter(
                        ClassName.get(domainBase + ".saga", sagaTypeName(domain)), lower + "Saga"));
            }
            for (DomainMetadata domain : domains) {
                if (!(domain.hasEvents() && domain.isSaga())) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                type.addMethod(simpleGetter(
                        ClassName.get(domainBase + ".event", name + "EventHandler"),
                        lower + "EventHandler"));
            }
        }
        if (hasGraph) {
            for (DomainMetadata domain : domains) {
                if (!domain.hasGraphMetadata()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                type.addMethod(simpleGetter(
                        ClassName.get(domainBase + ".graph", name + "GraphSync"),
                        lower + "GraphSync"));
            }
        }

        return new GeneratedFile(basePackage, className,
                KernelScaffold.render(basePackage, type.build()), ArtifactType.CONFIGURATION);
    }

    private MethodSpec buildCompositionRootConstructor(List<DomainMetadata> domains,
                                                       boolean hasEvents,
                                                       boolean hasSagas,
                                                       boolean hasGraph) {
        CodeBlock.Builder body = CodeBlock.builder()
                .addStatement("LOG.info($S)", "🔧 Wiring generated components...")
                .add("\n")
                .add("// DataSource\n")
                .add("this.dataSource = kernel.getPersistenceBootstrap() != null \n")
                .add("        ? kernel.getPersistenceBootstrap().getDataSource() \n")
                .add("        : null;\n")
                .beginControlFlow("if (dataSource == null)")
                .addStatement("LOG.warn($S)", "⚠️ DataSource is null - degraded mode")
                .endControlFlow()
                .add("\n");

        if (hasEvents) {
            body.add("// Event Infrastructure\n")
                    .addStatement("var eventsBootstrap = kernel.getEventsBootstrap()")
                    .addStatement("this.eventStore = eventsBootstrap != null ? eventsBootstrap.getEventStore() : null")
                    .addStatement("this.outboxSignal = eventsBootstrap != null ? eventsBootstrap.getOutboxSignal() : null")
                    .addStatement("LOG.info($S)", "✅ Event Infrastructure wired")
                    .add("\n");
        }
        if (hasSagas) {
            body.add("// Saga Infrastructure\n")
                    .addStatement("var flowBootstrap = kernel.getFlowBootstrap()")
                    .addStatement("this.sagaEngine = flowBootstrap != null ? flowBootstrap.getSagaEngine() : null")
                    .addStatement("LOG.info($S)", "✅ Saga Engine wired")
                    .add("\n");
        }
        if (hasGraph) {
            body.add("// Graph Infrastructure\n")
                    .add("this.graphService = kernel.getGraphBootstrap() != null \n")
                    .add("        ? kernel.getGraphBootstrap().getGraphService() \n")
                    .add("        : null;\n")
                    .addStatement("LOG.info($S)", "✅ Graph Service wired")
                    .add("\n");
        }

        body.add("// Repositories\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            ClassName repoType = ClassName.get(domainBase + ".repository", name + "Repository");
            body.addStatement("this.$LRepository = new $T(dataSource)", lower, repoType);
        }
        body.addStatement("LOG.info($S)", "✅ Repositories wired").add("\n");

        if (hasEvents) {
            body.add("// Event Publishers\n");
            for (DomainMetadata domain : domains) {
                if (!domain.hasEvents()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                ClassName publisherType = ClassName.get(domainBase + ".event", name + "Events", "Publisher");
                body.addStatement("this.$LEventPublisher = new $T(eventStore, outboxSignal)", lower, publisherType);
            }
            body.addStatement("LOG.info($S)", "✅ Event Publishers wired").add("\n");
        }

        body.add("// Services\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            ClassName serviceType = ClassName.get(domainBase + ".service", name + "Service");
            if (domain.hasEvents()) {
                body.addStatement("this.$LService = new $T($LRepository, $LEventPublisher)",
                        lower, serviceType, lower, lower);
            } else {
                body.addStatement("this.$LService = new $T($LRepository)", lower, serviceType, lower);
            }
        }
        body.addStatement("LOG.info($S)", "✅ Services wired").add("\n");

        body.add("// Handlers\n");
        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String domainBase = domain.packageName().replace(".domain", "");
            ClassName handlerType = ClassName.get(domainBase + ".handler", name + "Handler");
            body.addStatement("this.$LHandler = new $T($LService)", lower, handlerType, lower);
        }
        body.addStatement("LOG.info($S)", "✅ Handlers wired").add("\n");

        if (hasSagas) {
            body.add("// Sagas\n");
            for (DomainMetadata domain : domains) {
                if (!domain.isSaga()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                ClassName sagaType = ClassName.get(domainBase + ".saga", sagaTypeName(domain));
                body.addStatement("this.$LSaga = new $T(sagaEngine, eventStore, $LRepository)",
                        lower, sagaType, lower);
            }
            body.addStatement("LOG.info($S)", "✅ Sagas wired").add("\n");

            body.add("// Event Handlers (saga triggers)\n");
            for (DomainMetadata domain : domains) {
                if (!(domain.hasEvents() && domain.isSaga())) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                ClassName handlerType = ClassName.get(domainBase + ".event", name + "EventHandler");
                body.addStatement("this.$LEventHandler = new $T($LSaga, eventStore)",
                        lower, handlerType, lower);
            }
            body.addStatement("LOG.info($S)", "✅ Event Handlers wired").add("\n");
        }

        if (hasGraph) {
            body.add("// Graph Sync\n");
            for (DomainMetadata domain : domains) {
                if (!domain.hasGraphMetadata()) continue;
                String name = domain.entityName();
                String lower = toLowerFirst(name);
                String domainBase = domain.packageName().replace(".domain", "");
                ClassName syncType = ClassName.get(domainBase + ".graph", name + "GraphSync");
                body.addStatement("this.$LGraphSync = new $T(graphService)", lower, syncType);
            }
            body.addStatement("LOG.info($S)", "✅ Graph Sync wired").add("\n");
        }

        body.addStatement("LOG.info($S)", "🎯 Composition Root complete: " + domains.size() + " domain(s)");

        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(KERNEL_BOOTSTRAP, "kernel")
                .addCode(body.build())
                .build();
    }

    private GeneratedFile generateRouterConfig(List<DomainMetadata> domains, String basePackage) {
        String className = "RouterConfig";
        ClassName selfType = ClassName.get(basePackage, className);
        ClassName compositionRootType = ClassName.get(basePackage, "CompositionRoot");

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated HTTP/3 Router Configuration.\n")
                .addJavadoc("<p>Registers routes for $L domain(s).\n", domains.size())
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());

        CodeBlock.Builder body = CodeBlock.builder()
                .addStatement("$T router = new $T()", HTTP3_ROUTER, HTTP3_ROUTER)
                .add("\n")
                .add("// Health check\n")
                .add("router.register($S, $S, exchange -> {\n", "GET", "/health")
                .add("    exchange.response().sendHeaders(200, null);\n")
                .add("    exchange.response().sendText($S);\n", "{\"status\":\"UP\"}")
                .add("});\n")
                .add("\n")
                .add("// API info\n")
                .add("router.register($S, $S, exchange -> {\n", "GET", "/api/v1")
                .add("    exchange.response().sendHeaders(200, null);\n")
                .add("    exchange.response().sendText($S);\n",
                        "{\"name\":\"Exeris Foundation API\",\"version\":\"v1\",\"domains\":" + domains.size() + "}")
                .add("});\n")
                .add("\n");

        for (DomainMetadata domain : domains) {
            String name = domain.entityName();
            String lower = toLowerFirst(name);
            String path = (domain.effectivePath() != null && !domain.effectivePath().isEmpty())
                    ? domain.effectivePath()
                    : "/" + toKebabCase(name) + "s";
            String apiPath = "/api/v1" + path;
            String apiPathStar = apiPath + "/*";

            body.add("// ═══ $L routes ═══\n", name)
                    .addStatement("router.register($S, $S, root.$LHandler()::handleGetAll)", "GET", apiPath, lower)
                    .addStatement("router.register($S, $S, root.$LHandler()::handleGetById)", "GET", apiPathStar, lower)
                    .addStatement("router.register($S, $S, root.$LHandler()::handleCreate)", "POST", apiPath, lower)
                    .addStatement("router.register($S, $S, root.$LHandler()::handleUpdate)", "PUT", apiPathStar, lower)
                    .addStatement("router.register($S, $S, root.$LHandler()::handleDelete)", "DELETE", apiPathStar, lower)
                    .addStatement("LOG.info($S)", "📍 Registered: CRUD " + apiPath)
                    .add("\n");
        }

        body.addStatement("return router");

        type.addMethod(MethodSpec.methodBuilder("configure")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(HTTP3_ROUTER)
                .addParameter(compositionRootType, "root")
                .addCode(body.build())
                .build());

        return new GeneratedFile(basePackage, className,
                KernelScaffold.render(basePackage, type.build()), ArtifactType.CONFIGURATION);
    }

    private MethodSpec simpleGetter(TypeName returnType, String fieldName) {
        return MethodSpec.methodBuilder(fieldName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType)
                .addStatement("return $L", fieldName)
                .build();
    }

    private String sagaTypeName(DomainMetadata domain) {
        String configured = domain.sagaMetadata() != null ? domain.sagaMetadata().name() : null;
        return configured != null ? configured : domain.entityName() + "Saga";
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
