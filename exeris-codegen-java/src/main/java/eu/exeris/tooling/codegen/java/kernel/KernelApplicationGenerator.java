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
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Kernel Application Generator.
 * <p>
 * Emits the application bootstrap skeleton: an {@code Application}
 * entry point that drives {@link
 * eu.exeris.kernel.core.bootstrap.KernelBootstrap} and a
 * {@code RuntimeLifecycle} that composes the per-entity Repository →
 * Service → Handler chain and registers HTTP routes. Canonical wiring
 * shape mirrors the working community benchmark app's
 * {@code ExerisCommunityApplication} + {@code CommunityBenchmarkRuntimeLifecycle}
 * pair (under {@code exeris-benchmarks/targets/exeris-community-app}).
 * <p>
 * Unlike the per-entity generators in {@link KernelGeneratorStrategy},
 * this generator emits <b>once per project</b> against the full domain
 * list. {@link #generate(DomainMetadata)} therefore returns {@code null}
 * — the real entry point is
 * {@link #generateAll(List, String)}, invoked by {@code CodegenMain}
 * after the per-entity strategy loop completes.
 * <p>
 * Emitted files (in the project base package):
 * <ul>
 *   <li>{@code Application.java} — {@code main()} entry. Builds a
 *       forwarding {@link eu.exeris.kernel.spi.http.HttpHandler},
 *       binds it via {@code ScopedValue.where(HTTP_SERVER_HANDLER)},
 *       and drives {@code KernelBootstrap.builder().selector(
 *       BootstrapSelector.forNames(subsystems())).build().boot(() -> new
 *       RuntimeLifecycle(handlerSlot, transactionalExecutor()).run())}.
 *       The {@code transactionalExecutor()} method is {@code protected}
 *       so consumers can subclass and substitute a custom
 *       {@link eu.exeris.kernel.spi.persistence.TransactionalExecutor}; the
 *       default body composes {@code new TransactionOrchestrator(
 *       KernelProviders.persistenceEngine())} once the kernel has bound
 *       the {@code PERSISTENCE_ENGINE} {@link java.lang.ScopedValue}.</li>
 *   <li>{@code RuntimeLifecycle.java} — Receives the bound
 *       {@code TransactionalExecutor}, instantiates each declared
 *       entity's {@code *Repository}, {@code *Service}, and
 *       {@code *Handler}, builds an
 *       {@link eu.exeris.kernel.core.http.routing.HttpRouter} with the
 *       five canonical CRUD routes per entity (GET-all / GET-by-id /
 *       POST-create / PUT-update / DELETE), sets the forwarding handler
 *       slot, and parks on a {@link java.util.concurrent.CountDownLatch}
 *       until the JVM shuts down.</li>
 * </ul>
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelApplicationGenerator implements KernelArtifactGenerator {

    private static final String SUBSYSTEMS = "http,persistence,graph,flow,events,crypto";
    private static final String TX_EXECUTOR_NAME = "transactionalExecutor";

    private static final ClassName ATOMIC_REFERENCE =
            ClassName.get("java.util.concurrent.atomic", "AtomicReference");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName COUNT_DOWN_LATCH =
            ClassName.get("java.util.concurrent", "CountDownLatch");
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");
    private static final ClassName RUNTIME = ClassName.get("java.lang", "Runtime");
    private static final ClassName THREAD = ClassName.get("java.lang", "Thread");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");

    private static final ClassName KERNEL_BOOTSTRAP =
            ClassName.get("eu.exeris.kernel.core.bootstrap", "KernelBootstrap");
    private static final ClassName BOOTSTRAP_SELECTOR =
            ClassName.get("eu.exeris.kernel.spi.bootstrap", "BootstrapSelector");
    private static final ClassName HTTP_HANDLER =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpHandler");
    private static final ClassName HTTP_KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpKernelProviders");
    private static final ClassName HTTP_METHOD =
            ClassName.get("eu.exeris.kernel.spi.http", "HttpMethod");
    private static final ClassName HTTP_ROUTER =
            ClassName.get("eu.exeris.kernel.core.http.routing", "HttpRouter");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get("eu.exeris.kernel.spi.persistence", "TransactionalExecutor");
    private static final ClassName TRANSACTION_ORCHESTRATOR =
            ClassName.get("eu.exeris.kernel.core.persistence", "TransactionOrchestrator");

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        // Application emission is project-wide, not per-entity. Real
        // entry point is generateAll(...).
        return null;
    }

    /**
     * Emits the two-file bootstrap skeleton for the project. Invoked by
     * {@code CodegenMain} after the per-entity strategy loop.
     *
     * @param domains the full set of domain metadata records in the project; never {@code null}
     * @param basePackage the project base package (e.g.\ {@code "com.example.foundation"});
     *                    {@code Application} and {@code RuntimeLifecycle} are emitted here
     * @return the two emitted files; always {@code [Application, RuntimeLifecycle]}
     */
    public List<GeneratedFile> generateAll(List<DomainMetadata> domains, String basePackage) {
        List<GeneratedFile> files = new ArrayList<>(2);
        files.add(buildApplication(basePackage));
        files.add(buildRuntimeLifecycle(domains, basePackage));
        return files;
    }

    private GeneratedFile buildApplication(String basePackage) {
        ClassName selfType = ClassName.get(basePackage, "Application");
        ClassName lifecycleType = ClassName.get(basePackage, "RuntimeLifecycle");
        TypeName atomicHttpHandler = ParameterizedTypeName.get(ATOMIC_REFERENCE, HTTP_HANDLER);

        MethodSpec mainMethod = MethodSpec.methodBuilder("main")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(String[].class, "args")
                .addStatement("new $T().run()", selfType)
                .build();

        MethodSpec runMethod = MethodSpec.methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addJavadoc("Application entry point. Boots the Kernel under a forwarding\n")
                .addJavadoc("HTTP handler bound via {@link $T} and hands control to\n", SCOPED_VALUE)
                .addJavadoc("{@link $T}.\n", lifecycleType)
                .addJavadoc("<p>While {@link $T#run()} is still composing the per-entity\n", lifecycleType)
                .addJavadoc("wiring (between bootstrap completion and the moment the\n")
                .addJavadoc("handler slot is set), inbound requests respond\n")
                .addJavadoc("{@link $T#SERVICE_UNAVAILABLE} rather than being silently dropped.\n", HTTP_STATUS)
                .addStatement("$T handlerSlot = new $T<>()", atomicHttpHandler, ATOMIC_REFERENCE)
                .addStatement("$T forwardingHandler = exchange -> {\n"
                                + "    $T h = handlerSlot.get();\n"
                                + "    if (h != null) {\n"
                                + "        h.handle(exchange);\n"
                                + "    } else {\n"
                                + "        exchange.respond($T.SERVICE_UNAVAILABLE);\n"
                                + "    }\n"
                                + "}",
                        HTTP_HANDLER, HTTP_HANDLER, HTTP_STATUS)
                .beginControlFlow("try")
                .addCode(CodeBlock.builder()
                        .add("$T.where($T.HTTP_SERVER_HANDLER, forwardingHandler).call(() -> {\n",
                                SCOPED_VALUE, HTTP_KERNEL_PROVIDERS)
                        .indent()
                        .add("$T.builder()\n", KERNEL_BOOTSTRAP)
                        .add("    .selector($T.forNames(subsystems().split($S)))\n", BOOTSTRAP_SELECTOR, ",")
                        .add("    .build()\n")
                        .add("    .boot(() -> new $T(handlerSlot, transactionalExecutor()).run());\n", lifecycleType)
                        .add("return null;\n")
                        .unindent()
                        .addStatement("})")
                        .build())
                .nextControlFlow("catch ($T e)", RUNTIME_EXCEPTION)
                .addStatement("throw e")
                .nextControlFlow("catch (Exception e)")
                .addStatement("throw new $T($S, e)", RUNTIME_EXCEPTION, "Bootstrap failed")
                .endControlFlow()
                .build();

        MethodSpec subsystemsMethod = MethodSpec.methodBuilder("subsystems")
                .addModifiers(Modifier.PROTECTED)
                .returns(String.class)
                .addJavadoc("Comma-separated Kernel subsystem list passed to\n")
                .addJavadoc("{@link $T#forNames(String...)}. Default is the canonical\n", BOOTSTRAP_SELECTOR)
                .addJavadoc("Open-Core selector.\n")
                .addJavadoc("<p>Subclass {@code Application} and override this method to\n")
                .addJavadoc("add/remove subsystems — e.g.\\ to drop {@code graph} when the\n")
                .addJavadoc("project has no graph projections. (It must be an instance\n")
                .addJavadoc("method, not a {@code static final} field, otherwise javac\n")
                .addJavadoc("inlines the constant and the override has no effect.)\n")
                .addStatement("return $S", SUBSYSTEMS)
                .build();

        MethodSpec transactionalExecutorMethod = MethodSpec.methodBuilder(TX_EXECUTOR_NAME)
                .addModifiers(Modifier.PROTECTED)
                .returns(TRANSACTIONAL_EXECUTOR)
                .addJavadoc("Provides the {@link $T} the generated repositories use.\n", TRANSACTIONAL_EXECUTOR)
                .addJavadoc("<p>The default composes\n")
                .addJavadoc("{@code new TransactionOrchestrator(KernelProviders.persistenceEngine())},\n")
                .addJavadoc("which only resolves correctly once the kernel has booted and the\n")
                .addJavadoc("{@code PERSISTENCE_ENGINE} {@link $T} has been bound — i.e.\\ inside\n", SCOPED_VALUE)
                .addJavadoc("the {@code KernelBootstrap.boot(...)} callback (where this method\n")
                .addJavadoc("is invoked).\n")
                .addJavadoc("<p>Subclass {@code Application} and override this method to plug in\n")
                .addJavadoc("a custom executor (test harness, alternative pool, etc.).\n")
                .addStatement("return new $T($T.persistenceEngine())",
                        TRANSACTION_ORCHESTRATOR, KERNEL_PROVIDERS)
                .build();

        TypeSpec applicationType = KernelScaffold.publicClass("Application")
                .addJavadoc("Generated application entry point.\n")
                .addJavadoc("<p>Drives {@link $T} with the canonical SPI subsystem set\n", KERNEL_BOOTSTRAP)
                .addJavadoc("({@code http,persistence,graph,flow,events,crypto}) and hands the\n")
                .addJavadoc("composed Handler/Service/Repository/Router stack off to\n")
                .addJavadoc("{@link $T#run()}.\n", lifecycleType)
                .addJavadoc("<p>Subclass to override {@link #transactionalExecutor()} or\n")
                .addJavadoc("{@link #subsystems()}.\n")
                .addJavadoc("<p>Runtime classpath requirements (in addition to\n")
                .addJavadoc("{@code exeris-kernel-spi} / {@code -core}): {@code org.slf4j:slf4j-api}\n")
                .addJavadoc("(used by the generated repositories/services/handlers) plus a\n")
                .addJavadoc("kernel persistence provider on the classpath (Community driver\n")
                .addJavadoc("with a configured PostgreSQL DataSource — bound by the kernel\n")
                .addJavadoc("bootstrap, not by this generated code).\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addMethod(mainMethod)
                .addMethod(runMethod)
                .addMethod(subsystemsMethod)
                .addMethod(transactionalExecutorMethod)
                .build();

        return new GeneratedFile(basePackage, "Application",
                KernelScaffold.render(basePackage, applicationType), ArtifactType.APPLICATION);
    }

    private GeneratedFile buildRuntimeLifecycle(List<DomainMetadata> domains, String basePackage) {
        ClassName selfType = ClassName.get(basePackage, "RuntimeLifecycle");
        TypeName atomicHttpHandler = ParameterizedTypeName.get(ATOMIC_REFERENCE, HTTP_HANDLER);

        TypeSpec.Builder type = KernelScaffold.publicClass("RuntimeLifecycle")
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated runtime-lifecycle wiring.\n")
                .addJavadoc("<p>Composes per-entity Repository → Service → Handler chains,\n")
                .addJavadoc("builds an {@link $T} with the canonical CRUD routes per\n", HTTP_ROUTER)
                .addJavadoc("entity, sets the forwarding handler slot, and parks the JVM\n")
                .addJavadoc("on a shutdown latch.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(atomicHttpHandler, "handlerSlot",
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(TRANSACTIONAL_EXECUTOR, TX_EXECUTOR_NAME,
                        Modifier.PRIVATE, Modifier.FINAL).build());

        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(atomicHttpHandler, "handlerSlot")
                .addParameter(TRANSACTIONAL_EXECUTOR, TX_EXECUTOR_NAME)
                .addStatement("this.handlerSlot = handlerSlot")
                .addStatement("this.$L = $L", TX_EXECUTOR_NAME, TX_EXECUTOR_NAME)
                .build());

        type.addMethod(buildRunMethod(domains, basePackage));

        return new GeneratedFile(basePackage, "RuntimeLifecycle",
                KernelScaffold.render(basePackage, type.build()), ArtifactType.APPLICATION);
    }

    private MethodSpec buildRunMethod(List<DomainMetadata> domains, String basePackage) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addJavadoc("Composes the application, sets the forwarding HTTP handler,\n")
                .addJavadoc("and parks on a shutdown latch until the JVM exits.\n");

        // Per-entity wiring: Repository → Service → Handler
        for (DomainMetadata domain : domains) {
            String entity = domain.entityName();
            String entityLower = lowerFirst(entity);
            String domainPkg = domain.packageName();
            if (!domainPkg.endsWith(".domain")) {
                throw new IllegalArgumentException(
                        "Domain package '" + domainPkg + "' for entity '" + entity
                                + "' does not end with '.domain'. The Application "
                                + "generator derives the .repository/.service/.handler "
                                + "package paths by replacing the '.domain' suffix; "
                                + "without it the per-entity wiring would resolve "
                                + "Repository/Service/Handler to the wrong location. "
                                + "Either rename the domain package to end with "
                                + "'.domain' or extend DomainMetadata to carry explicit "
                                + "infrastructure package paths.");
            }
            String repoPkg = domainPkg.replace(".domain", ".repository");
            String servicePkg = domainPkg.replace(".domain", ".service");
            String handlerPkg = domainPkg.replace(".domain", ".handler");

            ClassName repoType = ClassName.get(repoPkg, entity + "Repository");
            ClassName serviceType = ClassName.get(servicePkg, entity + "Service");
            ClassName handlerType = ClassName.get(handlerPkg, entity + "Handler");

            method.addStatement("$T $LRepository = new $T(transactionalExecutor)",
                    repoType, entityLower, repoType);
            method.addStatement("$T $LService = new $T($LRepository)",
                    serviceType, entityLower, serviceType, entityLower);
            method.addStatement("$T $LHandler = new $T($LService)",
                    handlerType, entityLower, handlerType, entityLower);
            // ADR-043 Slice 1: instantiate the per-entity SSE live-view stream
            // handler when @ExerisDomain(realTimeApi). Emitted by
            // KernelStreamHandlerGenerator into the same .handler package.
            if (domain.realTimeApi()) {
                ClassName streamHandlerType = ClassName.get(handlerPkg, entity + "StreamHandler");
                method.addStatement("$T $LStreamHandler = new $T()",
                        streamHandlerType, entityLower, streamHandlerType);
            }
        }

        // Router build
        method.addStatement("$T.Builder routerBuilder = $T.builder()", HTTP_ROUTER, HTTP_ROUTER);
        for (DomainMetadata domain : domains) {
            String entity = domain.entityName();
            String entityLower = lowerFirst(entity);
            String basePath = domain.effectivePath();
            method.addStatement("routerBuilder.route($T.GET, $S, $LHandler::handleGetAll)",
                    HTTP_METHOD, basePath, entityLower);
            method.addStatement("routerBuilder.route($T.GET, $S, $LHandler::handleGetById)",
                    HTTP_METHOD, basePath + "/{id}", entityLower);
            method.addStatement("routerBuilder.route($T.POST, $S, $LHandler::handleCreate)",
                    HTTP_METHOD, basePath, entityLower);
            method.addStatement("routerBuilder.route($T.PUT, $S, $LHandler::handleUpdate)",
                    HTTP_METHOD, basePath + "/{id}", entityLower);
            method.addStatement("routerBuilder.route($T.DELETE, $S, $LHandler::handleDelete)",
                    HTTP_METHOD, basePath + "/{id}", entityLower);
            // T1: one route per @Action, matching the OpenAPI path byte-for-byte
            // ({basePath}/{id}/actions/{kebab(name)}, POST — OpenAPI emits POST for
            // every action). The handler method name mirrors KernelHandlerGenerator's
            // "handle" + pascal(name).
            for (ActionMetadata action : domain.actions()) {
                method.addStatement("routerBuilder.route($T.POST, $S, $LHandler::$L)",
                        HTTP_METHOD, basePath + "/{id}/actions/" + NameCasing.kebab(action.name()),
                        entityLower, "handle" + NameCasing.pascal(action.name()));
            }
            // ADR-043 Slice 1: collection-level SSE live-view route. Uses the
            // router's typed streamRoute(...), distinct from route(...) — a
            // streaming route resolves to an HttpStreamExchange, never an
            // HttpExchange. GET {base}/stream, no custom headers (TS EventSource).
            if (domain.realTimeApi()) {
                method.addStatement("routerBuilder.streamRoute($T.GET, $S, $LStreamHandler::handle)",
                        HTTP_METHOD, basePath + "/stream", entityLower);
            }
        }
        method.addStatement("$T router = routerBuilder.build()", HTTP_ROUTER);
        method.addStatement("handlerSlot.set(router::handle)");
        method.addStatement("LOG.info($S, $L)",
                "Application bootstrap complete: {} entities wired", domains.size());

        // Shutdown latch
        method.addStatement("$T shutdownLatch = new $T($L)",
                        COUNT_DOWN_LATCH, COUNT_DOWN_LATCH, 1)
                .addStatement("$T.getRuntime().addShutdownHook(new $T(shutdownLatch::countDown, $S))",
                        RUNTIME, THREAD, "exeris-shutdown")
                .beginControlFlow("try")
                .addStatement("shutdownLatch.await()")
                .nextControlFlow("catch (InterruptedException e)")
                .addStatement("$T.currentThread().interrupt()", THREAD)
                .endControlFlow();

        return method.build();
    }

    private String lowerFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.APPLICATION;
    }
}
