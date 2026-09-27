package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.DataScopeSupport;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.tooling.codegen.java.support.KernelStreamScaffold;
import eu.exeris.sdk.sourcemodel.ast.ActionMetadata;
import eu.exeris.tooling.codegen.java.support.NameCasing;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * {@link #generateAll(List, String, boolean)}, invoked by
 * {@link eu.exeris.tooling.codegen.java.CodegenPipeline} after the
 * per-entity strategy loop completes.
 * <p>
 * Emitted files (in the project base package):
 * <ul>
 *   <li>{@code Application.java} — {@code main()} entry. Builds the edge
 *       {@link eu.exeris.kernel.core.http.routing.HttpRouter} through
 *       {@code RuntimeLifecycle.edgeRouter(handlerSlot, componentsSlot)},
 *       binds it via {@code ScopedValue.where(HTTP_SERVER_HANDLER)},
 *       and drives {@code KernelBootstrap.builder().selector(
 *       BootstrapSelector.forNames(subsystems())).build().boot(() -> new
 *       RuntimeLifecycle(handlerSlot, componentsSlot,
 *       components(transactionalExecutor())).run())}.
 *       The {@code transactionalExecutor()} method is {@code protected}
 *       so consumers can subclass and substitute a custom
 *       {@link eu.exeris.kernel.spi.persistence.TransactionalExecutor}; the
 *       default body composes {@code new TransactionOrchestrator(
 *       KernelProviders.persistenceEngine())} once the kernel has bound
 *       the {@code PERSISTENCE_ENGINE} {@link java.lang.ScopedValue}.</li>
 *   <li>{@code RuntimeComponents.java} — The composition-root seam (T49).
 *       One {@code protected create*} factory, one memoising {@code public}
 *       accessor and one field per generated {@code *Repository},
 *       {@code *Service} and {@code *Handler}, plus a {@code configureRoutes}
 *       hook. A consumer subclasses it to install a hand-written service or
 *       to register a route the generator does not emit, and returns the
 *       subclass from {@code Application#components(TransactionalExecutor)}.
 *       Before it existed the emitted services were {@code public} and
 *       non-final — extensible by design — with nowhere to plug the
 *       extension in.</li>
 *   <li>{@code RuntimeLifecycle.java} — Owns the two route tables. Its static
 *       {@code edgeRouter(...)} builds, before boot, the router the kernel actually
 *       holds: every generated stream route, each resolving its handler from the
 *       composed {@code RuntimeComponents} when a stream opens, plus a
 *       {@code notFound} that forwards everything else to the handler slot. Its
 *       {@code run()} receives the {@code RuntimeComponents}, takes each declared
 *       entity's {@code *Handler} from it, builds the respond-once
 *       {@link eu.exeris.kernel.core.http.routing.HttpRouter} with the five canonical
 *       CRUD routes per entity (GET-all / GET-by-id / POST-create / PUT-update /
 *       DELETE) plus one per action, publishes the components and then the
 *       (decorated) router, and parks on a
 *       {@link java.util.concurrent.CountDownLatch} until the JVM shuts down.</li>
 * </ul>
 * <p>Why two routers: the http subsystem reads {@code HTTP_SERVER_HANDLER} once,
 * when it starts, which is before the boot callback in which composition must run
 * (ADR-070 obligation 4); and the kernel resolves a stream only on a handler that
 * <em>is</em> an {@code HttpRouter}. The stream half of the route table is known at
 * generation time, so it is built before boot with late-bound targets; the
 * respond-once half is built in {@code run()}, where it can be decorated.
 * See ADR-070, Amendment 2.
 * <p>When the build also carries a capability composition, the
 * {@code Application} boot callback additionally conducts that composition —
 * see {@link #generateAll(List, String, boolean)}. A build without capabilities
 * emits exactly what every release before 0.7.0 emitted.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelApplicationGenerator implements KernelArtifactGenerator {

    private static final String SUBSYSTEMS = "http,persistence,graph,flow,events,crypto";
    private static final String TX_EXECUTOR_NAME = "transactionalExecutor";
    // T49: the open half of the composition root. RuntimeLifecycle stops calling
    // `new XService(...)` and asks RuntimeComponents for it, so a consumer can
    // subclass RuntimeComponents, override one factory, and install it by
    // overriding Application#components(TransactionalExecutor).
    private static final String COMPONENTS_TYPE_NAME = "RuntimeComponents";
    private static final String COMPONENTS_METHOD = "components";
    private static final String COMPONENTS_FIELD = "components";
    private static final String CONFIGURE_ROUTES_METHOD = "configureRoutes";
    private static final String DECORATE_METHOD = "decorate";
    // The pre-boot edge router and the slot its stream targets read.
    private static final String EDGE_ROUTER_METHOD = "edgeRouter";
    private static final String LAZY_STREAM_METHOD = "lazyStream";
    private static final String HANDLER_SLOT = "handlerSlot";
    private static final String COMPONENTS_SLOT = "componentsSlot";

    private static final ClassName ATOMIC_REFERENCE =
            ClassName.get("java.util.concurrent.atomic", "AtomicReference");
    private static final ClassName HTTP_STATUS = ClassName.get("eu.exeris.kernel.spi.http", "HttpStatus");
    private static final ClassName COUNT_DOWN_LATCH =
            ClassName.get("java.util.concurrent", "CountDownLatch");
    private static final ClassName SCOPED_VALUE = ClassName.get("java.lang", "ScopedValue");
    private static final ClassName RUNTIME = ClassName.get("java.lang", "Runtime");
    private static final ClassName THREAD = ClassName.get("java.lang", "Thread");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");

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
    private static final ClassName HTTP_STREAM_HANDLER = KernelStreamScaffold.HTTP_STREAM_HANDLER;
    private static final ClassName FUNCTION = ClassName.get("java.util.function", "Function");
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get("eu.exeris.kernel.spi.persistence", "TransactionalExecutor");
    private static final ClassName TRANSACTION_ORCHESTRATOR =
            ClassName.get("eu.exeris.kernel.core.persistence", "TransactionOrchestrator");

    // ADR-024 ("Boot Conductor Call Site" amendment): the SKU-side
    // boot conductor. Emitted ONLY into a build that actually has a composition —
    // see buildApplication(String, boolean, boolean).
    private static final ClassName COMPOSITION_CONDUCTOR =
            ClassName.get("eu.exeris.sdk.composition.runtime", "CompositionConductor");
    private static final ClassName PATH = ClassName.get("java.nio.file", "Path");
    private static final String CAP_MANIFEST_METHOD = "capManifest";
    private static final String CAP_MANIFEST_FILE = "cap-manifest.json";
    private static final String CAP_MANIFEST_PROPERTY = "exeris.capManifest";

    // The two scope lists RuntimeComponents publishes.
    private static final String COMPOSITION_SCOPES = "COMPOSITION_SCOPES";
    private static final String REQUEST_SCOPES = "REQUEST_SCOPES";
    private static final ClassName LIST = ClassName.get("java.util", "List");

    /**
     * Every kernel {@code ScopedValue} emitted code reads, and when it is read. Declaration
     * order is emission order, so the lists and their Javadoc are stable whatever order the
     * domains arrive in.
     */
    private enum Scope {
        /** Read by every handler factory, which passes it to the handler's constructor. */
        MEMORY_ALLOCATOR(KERNEL_PROVIDERS, Phase.COMPOSITION),
        /** Read by the publisher, subscriber and EV1 stream-handler factories. */
        EVENT_ENGINE(KERNEL_PROVIDERS, Phase.COMPOSITION),
        /** Read by the saga-flow factories. */
        FLOW_ENGINE(KERNEL_PROVIDERS, Phase.COMPOSITION),
        /** Read by payload-bearing publisher factories; unbound only empties payloads. */
        EVENT_PAYLOAD_CODEC_REGISTRY(KERNEL_PROVIDERS, Phase.OPTIONAL_COMPOSITION),
        /** Read by every handler's {@code parseBody}; bound per request by the kernel. */
        HTTP_REQUEST_BODY_DECODER_REGISTRY(HTTP_KERNEL_PROVIDERS, Phase.REQUEST),
        /** Read by the tenant guard and the tenant stamp of a tenant-partitioned entity. */
        STORAGE_CONTEXT(KERNEL_PROVIDERS, Phase.REQUEST);

        private final ClassName holder;
        private final Phase phase;

        Scope(ClassName holder, Phase phase) {
            this.holder = holder;
            this.phase = phase;
        }
    }

    private enum Phase { COMPOSITION, OPTIONAL_COMPOSITION, REQUEST }

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        // Application emission is project-wide, not per-entity. Real
        // entry point is generateAll(...).
        return null;
    }

    /**
     * Emits the bootstrap skeleton for a project with <b>no</b>
     * composition — equivalent to {@link #generateAll(List, String, boolean)}
     * with {@code composed=false}.
     *
     * @param domains the full set of domain metadata records in the project; never {@code null}
     * @param basePackage the project base package (e.g.\ {@code "com.example.foundation"});
     *                    {@code Application}, {@code RuntimeComponents} and
     *                    {@code RuntimeLifecycle} are emitted here
     * @return the three emitted files; always
     *         {@code [Application, RuntimeComponents, RuntimeLifecycle]}
     */
    public List<GeneratedFile> generateAll(List<DomainMetadata> domains, String basePackage) {
        return generateAll(domains, basePackage, false);
    }

    /**
     * Emits the bootstrap skeleton for the project. Invoked by
     * {@link eu.exeris.tooling.codegen.java.CodegenPipeline} after the per-entity
     * strategy loop.
     *
     * @param domains the full set of domain metadata records in the project; never {@code null}
     * @param basePackage the project base package (e.g.\ {@code "com.example.foundation"});
     *                    {@code Application}, {@code RuntimeComponents} and
     *                    {@code RuntimeLifecycle} are emitted here
     * @param composed whether this build has a capability composition (at least one
     *                 {@code @CapabilityModule}). When {@code true}, {@code Application}
     *                 drives the SDK boot conductor around the runtime lifecycle; when
     *                 {@code false} not a single conductor symbol is emitted — see
     *                 {@link #buildApplication(String, boolean, boolean)}
     * @return the three emitted files; always
     *         {@code [Application, RuntimeComponents, RuntimeLifecycle]}
     * @since 0.7.0
     */
    public List<GeneratedFile> generateAll(List<DomainMetadata> domains, String basePackage,
                                           boolean composed) {
        List<GeneratedFile> files = new ArrayList<>(3);
        // The Jackson 3 sentence is emitted only when a repository in this tree imports it.
        boolean importsJackson = domains.stream().anyMatch(KernelRepositoryGenerator::importsJackson);
        files.add(buildApplication(basePackage, composed, importsJackson));
        files.add(buildRuntimeComponents(domains, basePackage));
        files.add(buildRuntimeLifecycle(domains, basePackage));
        return files;
    }

    // Flyway version for the single trailing FK-constraint migration. The
    // per-entity CREATE TABLE migrations occupy tier 1 (unscoped) and tier 2
    // (tenant-scoped) — see KernelFlywayGenerator#migrationVersion. This file
    // is pinned to tier 3 (V3000000) so it sorts strictly AFTER every
    // CREATE TABLE, guaranteeing every referenced table exists before its
    // FOREIGN KEY constraint is added (the create-order hazard T8 deferred).
    private static final String FK_MIGRATION_VERSION = "V3000000__foreign_keys";

    /**
     * Emits the single trailing Flyway migration that adds every cross-table
     * {@code FOREIGN KEY} constraint (T9). App-wide, like {@link
     * #generateAll(List, String)}: it needs the full domain set to resolve each
     * relationship's target table and to skip references to entities that are
     * not generated (an external target has no table — referencing it would
     * make the migration fail).
     *
     * <p>Why a single trailing migration rather than inline {@code REFERENCES}
     * in each {@code CREATE TABLE}: a {@code REFERENCES} to a table created in a
     * later migration fails. Emitting all constraints in one file pinned above
     * every {@code CREATE TABLE} (tier 3) sidesteps the ordering hazard — every
     * table exists by the time this runs.
     *
     * <p>For each entity, each {@code MANY_TO_ONE} relationship whose target
     * entity is in {@code domains} yields:
     * <pre>
     * ALTER TABLE &lt;table&gt; ADD CONSTRAINT fk_&lt;table&gt;_&lt;col&gt;
     *     FOREIGN KEY (&lt;col&gt;) REFERENCES &lt;target_table&gt;(id) ON DELETE &lt;policy&gt;;
     * </pre>
     * {@code <col>} uses the same convention as the T8 FK column
     * ({@link KernelTableNaming#foreignKeyColumn(String)}); {@code <policy>} is
     * {@code CASCADE} when the relationship cascade is {@code ALL}/{@code REMOVE},
     * otherwise {@code RESTRICT} (mirroring the kernel's delete semantics — a
     * non-cascading parent delete is refused while children exist).
     *
     * <p>Deterministic (hard-constraint #3): constraints are sorted by
     * {@code (table, constraint-name)} and the target-table lookup is a
     * pre-built map, so no {@code HashMap} iteration order leaks into the SQL.
     * A domain set with no in-scope {@code MANY_TO_ONE} relationship yields no
     * file ({@code null}) — additive, every per-entity artefact is untouched.
     *
     * @param domains the full set of domain metadata records in the project; never {@code null}
     * @return the FK migration file, or {@code null} when there is nothing to constrain
     */
    public GeneratedFile generateForeignKeys(List<DomainMetadata> domains) {
        // Target-table resolution: entityName → effective SQL table. Only
        // generated entities are referenceable; an external target is absent
        // here and therefore skipped below.
        Map<String, String> tableByEntity = new HashMap<>();
        for (DomainMetadata domain : domains) {
            tableByEntity.put(domain.entityName(), KernelTableNaming.effectiveTable(domain));
        }

        List<ForeignKey> foreignKeys = new ArrayList<>();
        for (DomainMetadata domain : domains) {
            if (!domain.hasRelationships()) {
                continue;
            }
            String table = KernelTableNaming.effectiveTable(domain);
            for (RelationshipMetadata rel : domain.relationships()) {
                if (rel.type() != RelationshipMetadata.RelationType.MANY_TO_ONE) {
                    continue;
                }
                String targetTable = tableByEntity.get(rel.targetEntity());
                if (targetTable == null) {
                    // External / non-generated target — never reference a table
                    // this build did not create.
                    continue;
                }
                String column = KernelTableNaming.foreignKeyColumn(rel.name());
                String constraint = "fk_" + table + "_" + column;
                foreignKeys.add(new ForeignKey(table, constraint, column, targetTable, deletePolicy(rel)));
            }
        }

        if (foreignKeys.isEmpty()) {
            return null;
        }

        // Sort by table then constraint name — stable, input-order-independent.
        foreignKeys.sort(Comparator.comparing(ForeignKey::table)
                .thenComparing(ForeignKey::constraint));

        StringBuilder sql = new StringBuilder("""
                -- Flyway migration: Foreign-key constraints
                -- Generated from @ExerisDomain MANY_TO_ONE relationships
                -- Added after every CREATE TABLE migration so each referenced table exists.

                """);
        for (ForeignKey fk : foreignKeys) {
            sql.append("ALTER TABLE ").append(fk.table())
                    .append(" ADD CONSTRAINT ").append(fk.constraint())
                    .append(" FOREIGN KEY (").append(fk.column())
                    .append(") REFERENCES ").append(fk.targetTable())
                    .append("(id) ON DELETE ").append(fk.deletePolicy())
                    .append(";\n");
        }

        return new GeneratedFile("db/migration", FK_MIGRATION_VERSION, sql.toString(),
                ArtifactType.CONFIGURATION, "sql");
    }

    /**
     * Maps a relationship's cascade to an {@code ON DELETE} policy:
     * {@code CASCADE} when the parent owns the child lifecycle
     * ({@code ALL}/{@code REMOVE}), {@code RESTRICT} otherwise (the default —
     * refuse to delete a parent that still has children).
     */
    private static String deletePolicy(RelationshipMetadata rel) {
        RelationshipMetadata.CascadeType cascade = rel.cascade();
        if (cascade == RelationshipMetadata.CascadeType.ALL
                || cascade == RelationshipMetadata.CascadeType.REMOVE) {
            return "CASCADE";
        }
        return "RESTRICT";
    }

    /** One resolved foreign-key constraint, ready to emit. */
    private record ForeignKey(String table, String constraint, String column,
                              String targetTable, String deletePolicy) {
    }

    /**
     * Emits {@code Application}. With {@code composed}, the {@code KernelBootstrap.boot(...)}
     * callback additionally drives the SDK {@code CompositionConductor} around the runtime
     * lifecycle — the call site ADR-024's 2026-07-21 "Boot Conductor Call Site" amendment
     * pins: inside {@code boot(...)} (so it runs after {@code KERNEL READY}), never as a
     * kernel {@code Subsystem}, because the kernel stays cap-blind (obligation 9).
     *
     * <p>Ordering follows from that placement rather than being a free choice.
     * {@code start()} runs every cap's {@code initialize} + {@code ready} <em>before</em>
     * {@link #buildRuntimeLifecycle(List, String) RuntimeLifecycle} sets the handler slot,
     * so no request is served against a half-initialized composition (until the slot is set
     * the edge router answers {@code SERVICE_UNAVAILABLE}). On the way out the
     * try-with-resources closes <em>after</em> {@code run()} returns from its shutdown latch
     * and <em>before</em> {@code boot(...)} returns — caps drain and terminate in reverse
     * {@code initOrder}, then the kernel stops. That is the SKU-entrypoint-driven shutdown
     * the conductor's contract requires.
     *
     * <p>Without {@code composed} not one conductor symbol is emitted — no import, no
     * {@code capManifest()}, no try-with-resources. A cap-less app is a Tier 3 consumer with
     * nothing to conduct, and emitting a conductor that would boot an empty (or missing)
     * manifest is inert wiring at best and a boot failure at worst.
     */
    private GeneratedFile buildApplication(String basePackage, boolean composed,
                                           boolean importsJackson) {
        ClassName selfType = ClassName.get(basePackage, "Application");
        ClassName lifecycleType = ClassName.get(basePackage, "RuntimeLifecycle");
        TypeName atomicHttpHandler = ParameterizedTypeName.get(ATOMIC_REFERENCE, HTTP_HANDLER);
        TypeName atomicComponents = ParameterizedTypeName.get(ATOMIC_REFERENCE,
                ClassName.get(basePackage, COMPONENTS_TYPE_NAME));

        MethodSpec mainMethod = MethodSpec.methodBuilder("main")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(String[].class, "args")
                .addJavadoc("Boots this class. Note the {@code new $T()} — it is not\n", selfType)
                .addJavadoc("polymorphic, so a subclass overriding {@link #$L($T)}\n",
                        COMPONENTS_METHOD, TRANSACTIONAL_EXECUTOR)
                .addJavadoc("(or any other hook) is <b>not</b> reached through this entry point.\n")
                .addJavadoc("Give the subclass its own {@code main}:\n")
                .addJavadoc("<pre>{@code\n")
                .addJavadoc("public static void main(String[] args) {\n")
                .addJavadoc("    new MyApplication().run();\n")
                .addJavadoc("}\n")
                .addJavadoc("}</pre>\n")
                .addJavadoc("and point the launcher at it.\n")
                .addStatement("new $T().run()", selfType)
                .build();

        MethodSpec.Builder run = MethodSpec.methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addJavadoc("Application entry point. Boots the Kernel under the edge router\n")
                .addJavadoc("{@link $T#$L} builds, bound via {@link $T}, and hands\n",
                        lifecycleType, EDGE_ROUTER_METHOD, SCOPED_VALUE)
                .addJavadoc("control to {@link $T}.\n", lifecycleType)
                .addJavadoc("<p>The edge router is built before boot because the kernel reads its\n")
                .addJavadoc("server handler once, when the http subsystem starts — ahead of the boot\n")
                .addJavadoc("callback in which the components are composed — and resolves a stream\n")
                .addJavadoc("only on a handler that is an {@code HttpRouter}. It carries the\n")
                .addJavadoc("generated stream routes, which read the components from\n")
                .addJavadoc("{@code componentsSlot}, and forwards every other request to\n")
                .addJavadoc("{@code handlerSlot}.\n")
                .addJavadoc("<p>While {@link $T#run()} is still composing the per-entity\n", lifecycleType)
                .addJavadoc("wiring (between bootstrap completion and the moment the\n")
                .addJavadoc("handler slot is set), respond-once requests respond\n")
                .addJavadoc("{@link $T#SERVICE_UNAVAILABLE} rather than being silently dropped,\n", HTTP_STATUS)
                .addJavadoc("and a stream opened in that window is closed at once.\n");
        if (composed) {
            run.addJavadoc("<p>This build has a capability composition, so the boot callback\n")
                    .addJavadoc("also drives the {@link $T}: every cap is\n", COMPOSITION_CONDUCTOR)
                    .addJavadoc("initialized and made ready before the handler slot is set, and\n")
                    .addJavadoc("drained + terminated in reverse {@code initOrder} once the\n")
                    .addJavadoc("shutdown latch releases — before the kernel itself stops.\n");
        }
        MethodSpec runMethod = run
                .addStatement("$T $L = new $T<>()", atomicHttpHandler, HANDLER_SLOT, ATOMIC_REFERENCE)
                .addStatement("$T $L = new $T<>()", atomicComponents, COMPONENTS_SLOT, ATOMIC_REFERENCE)
                .addStatement("$T edgeRouter = $T.$L($L, $L)", HTTP_ROUTER, lifecycleType,
                        EDGE_ROUTER_METHOD, HANDLER_SLOT, COMPONENTS_SLOT)
                .beginControlFlow("try")
                .addCode(bootBlock(lifecycleType, composed))
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

        ClassName componentsType = ClassName.get(basePackage, COMPONENTS_TYPE_NAME);
        MethodSpec componentsMethod = MethodSpec.methodBuilder(COMPONENTS_METHOD)
                .addModifiers(Modifier.PROTECTED)
                .returns(componentsType)
                .addParameter(TRANSACTIONAL_EXECUTOR, TX_EXECUTOR_NAME)
                .addJavadoc("Supplies the {@link $T} the runtime lifecycle wires from.\n", componentsType)
                .addJavadoc("<p>This is the application-logic seam. Every generated Repository,\n")
                .addJavadoc("Service and Handler is built by an overridable factory method on\n")
                .addJavadoc("{@link $T}; subclass it, override the one factory you\n", componentsType)
                .addJavadoc("care about, and return your subclass here:\n")
                .addJavadoc("<pre>{@code\n")
                .addJavadoc("class MyApplication extends Application {\n")
                .addJavadoc("    @Override protected $L $L($T $L) {\n",
                        COMPONENTS_TYPE_NAME, COMPONENTS_METHOD, TRANSACTIONAL_EXECUTOR, TX_EXECUTOR_NAME)
                .addJavadoc("        return new My$L($L);\n", COMPONENTS_TYPE_NAME, TX_EXECUTOR_NAME)
                .addJavadoc("    }\n")
                .addJavadoc("}\n")
                .addJavadoc("}</pre>\n")
                .addJavadoc("<p>Called inside the {@code KernelBootstrap.boot(...)} callback, so a\n")
                .addJavadoc("factory body may resolve any bound {@link $T} —\n", SCOPED_VALUE)
                .addJavadoc("{@code KernelProviders.flowEngine()}, {@code KernelProviders.eventEngine()}\n")
                .addJavadoc("and friends are all available by then.\n")
                .addJavadoc("<p>Which ones the generated factories read is listed in\n")
                .addJavadoc("{@link $T#$L}; what the generated code reads while serving\n",
                        componentsType, COMPOSITION_SCOPES)
                .addJavadoc("a request, in {@link $T#$L}. A harness that composes outside a\n",
                        componentsType, REQUEST_SCOPES)
                .addJavadoc("boot binds both, plus {@code KernelProviders.PERSISTENCE_ENGINE} if it\n")
                .addJavadoc("keeps the default {@link #$L()}, the one reader of that scope.\n",
                        TX_EXECUTOR_NAME)
                .addStatement("return new $T($L)", componentsType, TX_EXECUTOR_NAME)
                .build();

        TypeSpec.Builder applicationType = KernelScaffold.publicClass("Application")
                .addJavadoc("Generated application entry point.\n")
                .addJavadoc("<p>Drives {@link $T} with the canonical SPI subsystem set\n", KERNEL_BOOTSTRAP)
                .addJavadoc("({@code http,persistence,graph,flow,events,crypto}) and hands the\n")
                .addJavadoc("composed Handler/Service/Repository/Router stack off to\n")
                .addJavadoc("{@link $T#run()}.\n", lifecycleType);
        if (composed) {
            applicationType
                    .addJavadoc("<p>This application is a composition host: it conducts the\n")
                    .addJavadoc("capability lifecycle via {@link $T}\n", COMPOSITION_CONDUCTOR)
                    .addJavadoc("inside the kernel boot callback.\n")
                    .addJavadoc("<p>Subclass to override {@link #transactionalExecutor()},\n")
                    .addJavadoc("{@link #subsystems()}, {@link #$L($T)} or {@link #$L()}.\n",
                            COMPONENTS_METHOD, TRANSACTIONAL_EXECUTOR, CAP_MANIFEST_METHOD);
        } else {
            applicationType
                    .addJavadoc("<p>Subclass to override {@link #transactionalExecutor()},\n")
                    .addJavadoc("{@link #subsystems()} or {@link #$L($T)}.\n",
                            COMPONENTS_METHOD, TRANSACTIONAL_EXECUTOR);
        }
        // Every import in the generated tree is a requirement on the consumer's compile
        // classpath that no emitted pom declares, so this Javadoc names them by phase.
        applicationType
                .addJavadoc("<p>Compile classpath requirements: the generated sources import\n")
                .addJavadoc("{@code exeris-kernel-spi} and {@code -core}.\n");
        if (composed) {
            applicationType
                    .addJavadoc("Composition adds {@code eu.exeris:exeris-sdk-composition-runtime}:\n")
                    .addJavadoc("this class imports its boot conductor, so the tree does not compile\n")
                    .addJavadoc("without it.\n");
        }
        if (importsJackson) {
            applicationType
                    .addJavadoc("A repository for an entity with a {@code List<X>} field also imports\n")
                    .addJavadoc("Jackson 3 ({@code tools.jackson.databind} / {@code tools.jackson.core}),\n")
                    .addJavadoc("which the kernel SPI and core do not bring; that repository's Javadoc\n")
                    .addJavadoc("names it.\n");
        }
        applicationType
                .addJavadoc("<p>Runtime classpath requirements, in addition: a kernel persistence\n")
                .addJavadoc("provider (Community driver with a configured PostgreSQL DataSource —\n")
                .addJavadoc("bound by the kernel bootstrap, not by this generated code).\n")
                .addJavadoc("<p>Generated code logs through {@link System.Logger}, so it adds no\n")
                .addJavadoc("logging dependency of its own. To route it to a backend, put a\n")
                .addJavadoc("{@link System.LoggerFinder} provider on the classpath.\n");
        applicationType
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addMethod(mainMethod)
                .addMethod(runMethod)
                .addMethod(subsystemsMethod)
                .addMethod(transactionalExecutorMethod)
                .addMethod(componentsMethod);
        if (composed) {
            applicationType.addMethod(capManifestMethod());
        }

        return new GeneratedFile(basePackage, "Application",
                KernelScaffold.render(basePackage, applicationType.build()), ArtifactType.APPLICATION);
    }

    /**
     * The {@code KernelBootstrap.boot(...)} chain. The composed variant wraps the runtime
     * lifecycle in a try-with-resources over the conductor.
     */
    private CodeBlock bootBlock(ClassName lifecycleType, boolean composed) {
        CodeBlock.Builder block = CodeBlock.builder()
                .add("$T.where($T.HTTP_SERVER_HANDLER, edgeRouter).call(() -> {\n",
                        SCOPED_VALUE, HTTP_KERNEL_PROVIDERS)
                .indent()
                .add("$T.builder()\n", KERNEL_BOOTSTRAP)
                .add("    .selector($T.forNames(subsystems().split($S)))\n", BOOTSTRAP_SELECTOR, ",")
                .add("    .build()\n");
        if (composed) {
            // try-with-resources over the CONCRETE conductor type, not AutoCloseable: the
            // concrete close() declares no checked exception, which is what lets this sit
            // inside boot(Runnable) without a catch.
            block.add("    .boot(() -> {\n")
                    .add("        try ($T conductor = $T.from($L()).start()) {\n",
                            COMPOSITION_CONDUCTOR, COMPOSITION_CONDUCTOR, CAP_MANIFEST_METHOD)
                    .add("            new $T($L, $L, $L($L())).run();\n",
                            lifecycleType, HANDLER_SLOT, COMPONENTS_SLOT, COMPONENTS_METHOD, TX_EXECUTOR_NAME)
                    .add("        }\n")
                    .add("    });\n");
        } else {
            block.add("    .boot(() -> new $T($L, $L, $L($L())).run());\n",
                    lifecycleType, HANDLER_SLOT, COMPONENTS_SLOT, COMPONENTS_METHOD, TX_EXECUTOR_NAME);
        }
        return block.add("return null;\n")
                .unindent()
                .addStatement("})")
                .build();
    }

    /** The overridable manifest-location seam, emitted only into a composed application. */
    private MethodSpec capManifestMethod() {
        return MethodSpec.methodBuilder(CAP_MANIFEST_METHOD)
                .addModifiers(Modifier.PROTECTED)
                .returns(PATH)
                .addJavadoc("Location of the composition manifest ({@code $L}) the boot\n", CAP_MANIFEST_FILE)
                .addJavadoc("conductor replays. Read from the {@code $L} system\n", CAP_MANIFEST_PROPERTY)
                .addJavadoc("property, defaulting to {@code $L} in the process working\n", CAP_MANIFEST_FILE)
                .addJavadoc("directory.\n")
                .addJavadoc("<p>The build writes the manifest at the codegen output root, which is\n")
                .addJavadoc("a <i>source</i> root and therefore not on the runtime classpath — a\n")
                .addJavadoc("packaged SKU ships the manifest as a deployment artefact and points\n")
                .addJavadoc("this method (or the property) at it.\n")
                .addJavadoc("<p>Subclass {@code Application} and override to resolve it any other\n")
                .addJavadoc("way (a classpath extraction, a config service, a fixed install path).\n")
                .addStatement("return $T.of($T.getProperty($S, $S))",
                        PATH, System.class, CAP_MANIFEST_PROPERTY, CAP_MANIFEST_FILE)
                .build();
    }

    /** The infrastructure packages derived from an entity's {@code .domain} package. */
    private record InfraPackages(String repository, String service, String handler, String event) {}

    /**
     * Derives the {@code .repository} / {@code .service} / {@code .handler} package paths
     * from an entity's domain package. The {@code .domain} suffix substitution is the only
     * thing locating the generated infrastructure types, so a package without it is rejected
     * here rather than emitted as an unresolvable import.
     */
    private InfraPackages infraPackages(DomainMetadata domain) {
        String domainPkg = domain.packageName();
        if (!domainPkg.endsWith(".domain")) {
            throw new IllegalArgumentException(
                    "Domain package '" + domainPkg + "' for entity '" + domain.entityName()
                            + "' does not end with '.domain'. The Application "
                            + "generator derives the .repository/.service/.handler/.event "
                            + "package paths by replacing the '.domain' suffix; "
                            + "without it the per-entity wiring would resolve "
                            + "Repository/Service/Handler to the wrong location. "
                            + "Either rename the domain package to end with "
                            + "'.domain' or extend DomainMetadata to carry explicit "
                            + "infrastructure package paths.");
        }
        return new InfraPackages(
                domainPkg.replace(".domain", ".repository"),
                domainPkg.replace(".domain", ".service"),
                domainPkg.replace(".domain", ".handler"),
                domainPkg.replace(".domain", ".event"));
    }

    /**
     * Emits {@code RuntimeComponents} — the open half of the composition root (T49).
     *
     * <p>Each component gets three members: a private field, a {@code public} memoising
     * accessor, and a {@code protected create*} factory holding the default
     * {@code new}. Overriding the factory replaces the component everywhere, because
     * every downstream default resolves its dependency through the accessor rather than
     * through a local. Memoisation makes the accessor safe to call from an override, which
     * is what lets {@link #CONFIGURE_ROUTES_METHOD} build a hand-written collaborator out of
     * generated parts.
     *
     * <p>Construction is lazy but single-threaded by construction: everything runs on the
     * boot thread inside the {@code KernelBootstrap.boot(...)} callback, before the handler
     * slot is set and therefore before any request can be served. No synchronisation is
     * emitted and none is needed.
     *
     * <p><b>Consumer-build contract:</b> this file imports the same two kernel coordinates
     * {@code RuntimeLifecycle} already imported ({@code exeris-kernel-spi} for
     * {@link eu.exeris.kernel.spi.persistence.TransactionalExecutor}, {@code exeris-kernel-core}
     * for {@link eu.exeris.kernel.core.http.routing.HttpRouter}) plus the project's own
     * generated types. It adds no requirement to the consumer's build.
     */
    private GeneratedFile buildRuntimeComponents(List<DomainMetadata> domains, String basePackage) {
        ClassName lifecycleType = ClassName.get(basePackage, "RuntimeLifecycle");
        ClassName applicationType = ClassName.get(basePackage, "Application");

        TypeSpec.Builder type = KernelScaffold.publicClass(COMPONENTS_TYPE_NAME)
                .addJavadoc("Generated component factory — the seam where application logic\n")
                .addJavadoc("enters the generated runtime.\n")
                .addJavadoc("<p>{@link $T} asks this object for every repository,\n", lifecycleType)
                .addJavadoc("service, handler, publisher, subscriber and saga flow it wires or\n")
                .addJavadoc("starts, instead of constructing them itself.\n")
                .addJavadoc("Each component has a {@code protected create*} factory carrying the\n")
                .addJavadoc("default construction; override one, and every consumer of that\n")
                .addJavadoc("component sees the replacement.\n")
                .addJavadoc("<pre>{@code\n")
                .addJavadoc("class MyComponents extends $L {\n", COMPONENTS_TYPE_NAME)
                .addJavadoc("    MyComponents(TransactionalExecutor tx) { super(tx); }\n")
                .addJavadoc("\n")
                .addJavadoc("    @Override protected OrderService createOrderService() {\n")
                .addJavadoc("        return new MyOrderService(orderRepository(),\n")
                .addJavadoc("                new OrderEventPublisher(KernelProviders.eventEngine()));\n")
                .addJavadoc("    }\n")
                .addJavadoc("}\n")
                .addJavadoc("}</pre>\n")
                .addJavadoc("<p>Install it by overriding {@link $T#$L($T)}.\n",
                        applicationType, COMPONENTS_METHOD, TRANSACTIONAL_EXECUTOR)
                .addJavadoc("<p>Every factory runs on the boot thread inside the kernel boot\n")
                .addJavadoc("callback, so a body may resolve any bound provider\n")
                .addJavadoc("({@code KernelProviders.flowEngine()},\n")
                .addJavadoc("{@code KernelProviders.eventEngine()}, …); {@link #$L} lists the\n",
                        COMPOSITION_SCOPES)
                .addJavadoc("ones the generated factories read, and {@link #$L} the ones the\n", REQUEST_SCOPES)
                .addJavadoc("generated code reads while serving a request. Accessors memoise and are\n")
                .addJavadoc("deliberately unsynchronised: composition completes before the HTTP\n")
                .addJavadoc("handler slot is set, so no request can observe a half-built graph.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - subclass instead; this file is regenerated\n")
                .addJavadoc("from domain models.\n")
                .addField(FieldSpec.builder(TRANSACTIONAL_EXECUTOR, TX_EXECUTOR_NAME,
                        Modifier.PRIVATE, Modifier.FINAL).build());

        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TRANSACTIONAL_EXECUTOR, TX_EXECUTOR_NAME)
                .addStatement("this.$L = $L", TX_EXECUTOR_NAME, TX_EXECUTOR_NAME)
                .build());

        type.addMethod(MethodSpec.methodBuilder(TX_EXECUTOR_NAME)
                .addModifiers(Modifier.PUBLIC)
                .returns(TRANSACTIONAL_EXECUTOR)
                .addJavadoc("The bound {@link $T} every generated repository\n", TRANSACTIONAL_EXECUTOR)
                .addJavadoc("is constructed with. Supplied by\n")
                .addJavadoc("{@link $T#$L()}.\n", applicationType, TX_EXECUTOR_NAME)
                .addStatement("return $L", TX_EXECUTOR_NAME)
                .build());

        // Each branch below that emits a read of a kernel scope records its reader here, in
        // the same statement's neighbourhood — so the published lists are derived from the
        // emission, not restated beside it. EnumMap iterates in Scope order: deterministic.
        Map<Scope, List<CodeBlock>> readers = new EnumMap<>(Scope.class);

        for (DomainMetadata domain : domains) {
            String entity = domain.entityName();
            String entityLower = lowerFirst(entity);
            InfraPackages pkgs = infraPackages(domain);

            ClassName repoType = ClassName.get(pkgs.repository(), entity + "Repository");
            ClassName serviceType = ClassName.get(pkgs.service(), entity + "Service");
            ClassName handlerType = ClassName.get(pkgs.handler(), entity + "Handler");

            String repoName = entityLower + "Repository";
            String serviceName = entityLower + "Service";

            addComponent(type, repoType, repoName,
                    CodeBlock.of("new $T($L())", repoType, TX_EXECUTOR_NAME));
            addComponent(type, serviceType, serviceName,
                    CodeBlock.of("new $T($L())", serviceType, repoName));

            // T48 (ADR-070's own rule, applied to the one component that had been left out):
            // the publisher joins the seam, so a consumer can override how it is built — and
            // the handler takes it, because an action is invoked on the entity by the handler
            // and never reaches the service.
            // The publisher joins the seam whenever one is emitted at all, including for the
            // triggers no handler method serves — a MANUAL event is published by the
            // consumer's own code, and the seam is how that code reaches the publisher. The
            // handler only takes it when a handler method would actually call it, so an
            // entity with only unserved triggers does not carry a field nothing reads.
            String publisherName = entityLower + "EventPublisher";
            if (domain.hasEvents()) {
                ClassName publisherType = ClassName.get(pkgs.event(), entity + "EventPublisher");
                // A publisher that encodes payloads takes the codec registry here, inside the
                // boot callback, where the kernel binds it. It publishes on the request thread,
                // where the slot is unbound: resolved there, the registry would be absent and
                // every payload would publish empty, with only a DEBUG line to say so.
                read(readers, Scope.EVENT_ENGINE, factoryReference(publisherName));
                if (KernelEventGenerator.hasPayloadEvents(domain)) {
                    read(readers, Scope.EVENT_PAYLOAD_CODEC_REGISTRY, factoryReference(publisherName));
                }
                addComponent(type, publisherType, publisherName,
                        KernelEventGenerator.hasPayloadEvents(domain)
                                ? CodeBlock.of("new $T($T.eventEngine(), $T.eventPayloadCodecRegistry().orElse(null))",
                                        publisherType, KERNEL_PROVIDERS, KERNEL_PROVIDERS)
                                : CodeBlock.of("new $T($T.eventEngine())", publisherType, KERNEL_PROVIDERS));
                // The subscriber joins the seam (ADR-070 obligation 1), so a
                // consumer installs behaviour by overriding this factory with a subclass whose
                // handle<Event> methods do the work. RuntimeLifecycle subscribes it at boot.
                ClassName subscriberType = ClassName.get(pkgs.event(), entity + "EventSubscriber");
                read(readers, Scope.EVENT_ENGINE, factoryReference(entityLower + "EventSubscriber"));
                addComponent(type, subscriberType, entityLower + "EventSubscriber",
                        CodeBlock.of("new $T($T.eventEngine())", subscriberType, KERNEL_PROVIDERS));
            }
            // The saga flow joins the seam too: a hand-written `<Name>Saga extends
            // <Name>SagaFlow` is installed by overriding create<Name>SagaFlow().
            // RuntimeLifecycle compiles its plan at boot: the kernel resumes a parked saga only on
            // a registered plan version (ADR-064), so lazy compilation on the first schedule()
            // would strand every instance parked before a restart.
            ClassName sagaFlowType = KernelSagaGenerator.sagaFlowType(domain);
            if (sagaFlowType != null) {
                read(readers, Scope.FLOW_ENGINE, factoryReference(sagaAccessor(sagaFlowType)));
                addComponent(type, sagaFlowType, sagaAccessor(sagaFlowType),
                        CodeBlock.of("new $T($T.flowEngine())", sagaFlowType, KERNEL_PROVIDERS));
            }
            // T43-follow-up: KernelProviders.MEMORY_ALLOCATOR is resolved HERE and handed to the
            // handler, rather than read per request inside parseBody. This factory runs inside the
            // KernelBootstrap.boot(...) callback, which the kernel executes inside the enriched
            // ScopedValue carrier — so the binding is live at exactly this point and nowhere the
            // handler will later run: a request is served on a virtual thread started with
            // Thread.ofVirtual().start(), which inherits no ScopedValue binding. Same shape the
            // kernel's own CommunityBenchmarkRuntimeLifecycle uses, and it turns a wiring fault
            // into a boot failure instead of a 5xx on the first request with a body.
            CodeBlock allocator = CodeBlock.of("$T.MEMORY_ALLOCATOR.get()", KERNEL_PROVIDERS);
            read(readers, Scope.MEMORY_ALLOCATOR, factoryReference(entityLower + "Handler"));
            // Request scopes are read by the handler and repository emitters, under these same
            // predicates: parseBody is emitted into every handler (KernelHandlerGenerator), and
            // the tenant guard and acting-tenant stamp under isTenantPartitioned (the handler and
            // repository generators both branch on it).
            read(readers, Scope.HTTP_REQUEST_BODY_DECODER_REGISTRY,
                    CodeBlock.of("{@link $T}{@code .parseBody}", handlerType));
            if (DataScopeSupport.isTenantPartitioned(domain)) {
                read(readers, Scope.STORAGE_CONTEXT,
                        CodeBlock.of("the tenant guard in {@link $T}", handlerType));
                read(readers, Scope.STORAGE_CONTEXT,
                        CodeBlock.of("{@link $T}{@code .$L()}", repoType,
                                KernelRepositoryGenerator.ACTING_TENANT_METHOD));
            }
            if (KernelHandlerGenerator.publishesFromHandler(domain)) {
                addComponent(type, handlerType, entityLower + "Handler",
                        CodeBlock.of("new $T($L(), $L, $L())",
                                handlerType, serviceName, allocator, publisherName));
            } else {
                addComponent(type, handlerType, entityLower + "Handler",
                        CodeBlock.of("new $T($L(), $L)", handlerType, serviceName, allocator));
            }

            // ADR-043 Slice 1 / ADR-044 Slice 2: the SSE stream handlers go through the same
            // seam so that a consumer overriding one does not have to know which handlers take
            // constructor arguments. The EV1 producer takes its EventEngine here,
            // for the reason the handler takes its allocator: its handle() runs on the stream's
            // own thread, where the kernel binds MEMORY_ALLOCATOR and the decoder registry and
            // nothing else, so KernelProviders.eventEngine() read there would throw after the
            // response head was already on the wire. The keep-alive fallback subscribes to
            // nothing and stays no-arg.
            if (domain.realTimeApi()) {
                ClassName streamHandlerType = ClassName.get(pkgs.handler(), entity + "StreamHandler");
                if (KernelStreamHandlerGenerator.hasProducer(domain)) {
                    read(readers, Scope.EVENT_ENGINE, factoryReference(entityLower + "StreamHandler"));
                }
                addComponent(type, streamHandlerType, entityLower + "StreamHandler",
                        KernelStreamHandlerGenerator.hasProducer(domain)
                                ? CodeBlock.of("new $T($T.eventEngine())", streamHandlerType, KERNEL_PROVIDERS)
                                : CodeBlock.of("new $T()", streamHandlerType));
            }
            for (ActionMetadata action : domain.actions()) {
                if (action.streaming()) {
                    String actionPascal = NameCasing.pascal(action.name());
                    ClassName actionStreamHandlerType =
                            ClassName.get(pkgs.handler(), entity + actionPascal + "StreamHandler");
                    addComponent(type, actionStreamHandlerType,
                            entityLower + actionPascal + "StreamHandler",
                            CodeBlock.of("new $T()", actionStreamHandlerType));
                }
            }
        }

        type.addField(compositionScopesField(readers, applicationType));
        type.addField(requestScopesField(readers));

        type.addMethod(MethodSpec.methodBuilder(CONFIGURE_ROUTES_METHOD)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(HTTP_ROUTER.nestedClass("Builder"), "routes")
                .addJavadoc("Registers routes this generator does not emit.\n")
                .addJavadoc("<p>Called by {@link $T} after every generated route and\n", lifecycleType)
                .addJavadoc("before {@code build()}, so a hand-written route may shadow nothing\n")
                .addJavadoc("and add anything. The accessors above are already usable here — the\n")
                .addJavadoc("point of the hook is to build a collaborator out of generated parts:\n")
                .addJavadoc("<pre>{@code\n")
                .addJavadoc("@Override public void $L($T.Builder routes) {\n",
                        CONFIGURE_ROUTES_METHOD, HTTP_ROUTER)
                .addJavadoc("    var saga = new OrderSagaOrchestrator(KernelProviders.flowEngine(),\n")
                .addJavadoc("            orderRepository(), $L());\n", TX_EXECUTOR_NAME)
                .addJavadoc("    routes.route(HttpMethod.POST, \"/checkout\", new CheckoutHandler(saga)::handle);\n")
                .addJavadoc("}\n")
                .addJavadoc("}</pre>\n")
                .addJavadoc("<p><b>Respond-once routes only.</b> This builder becomes the router in the\n")
                .addJavadoc("handler slot, which the kernel reaches through the edge router's\n")
                .addJavadoc("{@code notFound} and never asks to resolve a stream. A {@code streamRoute}\n")
                .addJavadoc("registered here therefore never matches on a real boot; the generated\n")
                .addJavadoc("stream routes are registered on {@link $T#$L} instead. Resolving a\n",
                        lifecycleType, EDGE_ROUTER_METHOD)
                .addJavadoc("hand-registered stream through a forwarding handler needs a kernel change\n")
                .addJavadoc("(ADR-070, Amendment 2).\n")
                .addComment("No generated body — override to register hand-written routes.")
                .build());

        type.addMethod(MethodSpec.methodBuilder(DECORATE_METHOD)
                .addModifiers(Modifier.PUBLIC)
                .returns(HTTP_HANDLER)
                .addParameter(HTTP_ROUTER, "router")
                .addJavadoc("Wraps the built router before it is put in the handler slot.\n")
                .addJavadoc("<p>Called by {@link $T} with the router {@code build()} returned;\n", lifecycleType)
                .addJavadoc("whatever this returns is what every respond-once request reaches, through\n")
                .addJavadoc("the edge router's {@code notFound}. The default returns the\n")
                .addJavadoc("router unchanged. Override to install a per-request concern the generated\n")
                .addJavadoc("code does not own — binding a tenant, a decoder registry, an allocator:\n")
                .addJavadoc("<pre>{@code\n")
                .addJavadoc("@Override public $T $L($T router) {\n",
                        HTTP_HANDLER, DECORATE_METHOD, HTTP_ROUTER)
                .addJavadoc("    return exchange -> ScopedValue.where(KernelProviders.STORAGE_CONTEXT, ctx)\n")
                .addJavadoc("            .run(() -> router.handle(exchange));\n")
                .addJavadoc("}\n")
                .addJavadoc("}</pre>\n")
                .addJavadoc("<p><b>Applies to respond-once routes.</b> Stream routes resolve on the edge\n")
                .addJavadoc("router {@link $T#$L} builds before boot, and run outside\n",
                        lifecycleType, EDGE_ROUTER_METHOD)
                .addJavadoc("this wrapper: a scope bound here is not bound for a stream. Any wrapper is\n")
                .addJavadoc("safe to return — the kernel never sees this object's type, so wrapping it\n")
                .addJavadoc("cannot erase a stream route.\n")
                .addStatement("return router")
                .build());

        return new GeneratedFile(basePackage, COMPONENTS_TYPE_NAME,
                KernelScaffold.render(basePackage, type.build()), ArtifactType.APPLICATION);
    }

    /** Records that {@code reader} — a Javadoc fragment naming it — reads {@code scope}. */
    private static void read(Map<Scope, List<CodeBlock>> readers, Scope scope, CodeBlock reader) {
        readers.computeIfAbsent(scope, s -> new ArrayList<>()).add(reader);
    }

    /** The Javadoc reference to a component's factory, e.g. {@code {@link #createOrderHandler()}}. */
    private static CodeBlock factoryReference(String componentName) {
        return CodeBlock.of("{@link #$L()}", factoryName(componentName));
    }

    private static String factoryName(String componentName) {
        return "create" + Character.toUpperCase(componentName.charAt(0)) + componentName.substring(1);
    }

    private static TypeName scopeListType() {
        return ParameterizedTypeName.get(LIST,
                ParameterizedTypeName.get(SCOPED_VALUE, WildcardTypeName.subtypeOf(Object.class)));
    }

    /** {@code List.of(K.A, K.B)} over the scopes of {@code phase} that something reads. */
    private static CodeBlock scopeList(Map<Scope, List<CodeBlock>> readers, Phase phase) {
        CodeBlock.Builder list = CodeBlock.builder().add("$T.of(", LIST);
        boolean first = true;
        for (Scope scope : readers.keySet()) {
            if (scope.phase == phase) {
                list.add(first ? "$T.$L" : ", $T.$L", scope.holder, scope.name());
                first = false;
            }
        }
        return list.add(")").build();
    }

    /** One Javadoc bullet per scope of {@code phase}: the scope, then every reader of it. */
    private static CodeBlock scopeBullets(Map<Scope, List<CodeBlock>> readers, Phase phase) {
        CodeBlock.Builder doc = CodeBlock.builder();
        for (Map.Entry<Scope, List<CodeBlock>> entry : readers.entrySet()) {
            Scope scope = entry.getKey();
            if (scope.phase != phase) {
                continue;
            }
            doc.add("  <li>{@link $T#$L} — read by ", scope.holder, scope.name());
            doc.add(CodeBlock.join(entry.getValue(), ", "));
            doc.add("</li>\n");
        }
        return doc.build();
    }

    /**
     * Emits {@code RuntimeComponents.COMPOSITION_SCOPES}: the scopes the generated
     * factories on this class read, so a harness that composes outside a kernel boot learns the
     * whole set at once instead of one failed boot at a time.
     */
    private static FieldSpec compositionScopesField(Map<Scope, List<CodeBlock>> readers,
                                                    ClassName applicationType) {
        FieldSpec.Builder field = FieldSpec.builder(scopeListType(), COMPOSITION_SCOPES,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The kernel scopes the generated factories on this class read while composing.\n")
                .addJavadoc("<p>{@code KernelBootstrap.boot(...)} binds every one of them around the boot\n")
                .addJavadoc("callback, which is where {@link $T#$L($T)} runs. A harness\n",
                        applicationType, COMPONENTS_METHOD, TRANSACTIONAL_EXECUTOR)
                .addJavadoc("composing outside a boot binds each — or overrides every factory that reads\n")
                .addJavadoc("it:\n")
                .addJavadoc("<ul>\n")
                .addJavadoc(scopeBullets(readers, Phase.COMPOSITION))
                .addJavadoc("</ul>\n");
        List<CodeBlock> optional = readers.get(Scope.EVENT_PAYLOAD_CODEC_REGISTRY);
        if (optional != null) {
            field.addJavadoc("<p>Optional, and so not listed: {@link $T#$L}, read by\n",
                            Scope.EVENT_PAYLOAD_CODEC_REGISTRY.holder, Scope.EVENT_PAYLOAD_CODEC_REGISTRY.name())
                    .addJavadoc(CodeBlock.join(optional, ", "))
                    .addJavadoc(". Unbound, those publishers encode nothing and every payload\n")
                    .addJavadoc("publishes empty.\n");
        }
        return field
                .addJavadoc("<p>Not read here: {@code KernelProviders.PERSISTENCE_ENGINE}, read only by\n")
                .addJavadoc("{@link $T#$L()}'s default.\n", applicationType, TX_EXECUTOR_NAME)
                .initializer(scopeList(readers, Phase.COMPOSITION))
                .build();
    }

    /**
     * Emits {@code RuntimeComponents.REQUEST_SCOPES}: the scopes the generated code reads
     * while serving a request — the ones a harness otherwise discovers one failed request at a
     * time.
     */
    private static FieldSpec requestScopesField(Map<Scope, List<CodeBlock>> readers) {
        FieldSpec.Builder field = FieldSpec.builder(scopeListType(), REQUEST_SCOPES,
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .addJavadoc("The kernel scopes the generated code reads while serving a respond-once\n")
                .addJavadoc("request:\n")
                .addJavadoc("<ul>\n")
                .addJavadoc(scopeBullets(readers, Phase.REQUEST))
                .addJavadoc("</ul>\n");
        if (readers.containsKey(Scope.HTTP_REQUEST_BODY_DECODER_REGISTRY)) {
            field.addJavadoc("<p>The kernel's HTTP dispatcher binds {@code HTTP_REQUEST_BODY_DECODER_REGISTRY}\n")
                    .addJavadoc("around every dispatch.\n");
        }
        if (readers.containsKey(Scope.STORAGE_CONTEXT)) {
            field.addJavadoc("<p>{@code STORAGE_CONTEXT} is bound by the kernel's {@code SecurityInterceptor}\n")
                    .addJavadoc("only for a route whose {@code HttpRoutePolicy} is not {@code permitAll()}, and\n")
                    .addJavadoc("this application binds no policy (ADR-079) — so the deployment binds it,\n")
                    .addJavadoc("typically in {@link #$L}, or declares a policy.\n", DECORATE_METHOD);
        }
        return field
                .addJavadoc("<p>A stream route reads none of them: its handler took what it needs when it\n")
                .addJavadoc("was composed, and the kernel binds neither a policy's context nor a session\n")
                .addJavadoc("on a stream thread.\n")
                .initializer(scopeList(readers, Phase.REQUEST))
                .build();
    }

    /**
     * Emits one component into {@code RuntimeComponents}: a private field, a public
     * memoising accessor, and the {@code protected create*} factory holding
     * {@code construction}.
     */
    private void addComponent(TypeSpec.Builder type, ClassName componentType,
                              String name, CodeBlock construction) {
        String factory = factoryName(name);

        type.addField(FieldSpec.builder(componentType, name, Modifier.PRIVATE).build());

        type.addMethod(MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC)
                .returns(componentType)
                .addJavadoc("The application's {@link $T}, built once by\n", componentType)
                .addJavadoc("{@link #$L()} on first call and memoised.\n", factory)
                .beginControlFlow("if ($L == null)", name)
                .addStatement("$L = $L()", name, factory)
                .endControlFlow()
                .addStatement("return $L", name)
                .build());

        type.addMethod(MethodSpec.methodBuilder(factory)
                .addModifiers(Modifier.PROTECTED)
                .returns(componentType)
                .addJavadoc("Constructs the {@link $T}. Override to install a\n", componentType)
                .addJavadoc("subclass or an alternative implementation; call {@code super.$L()}\n", factory)
                .addJavadoc("to decorate the default rather than replace it.\n")
                .addStatement("return $L", construction)
                .build());
    }

    /**
     * One generated stream route: registered on the edge router, and served by a
     * {@code RuntimeComponents} accessor that {@code run()} forces before publishing the
     * components.
     *
     * @param method   {@code "GET"} (entity live view) or {@code "POST"} (streaming action)
     * @param path     the route template
     * @param accessor the {@code RuntimeComponents} accessor returning the stream handler
     */
    private record StreamRoute(String method, String path, String accessor) {}

    /**
     * Every stream route the application emits, in emission order: per entity, its
     * {@code @Action(streaming)} routes in declaration order, then its
     * {@code realTimeApi} live view.
     */
    private List<StreamRoute> streamRoutes(List<DomainMetadata> domains) {
        List<StreamRoute> routes = new ArrayList<>();
        for (DomainMetadata domain : domains) {
            String entityLower = lowerFirst(domain.entityName());
            String basePath = domain.effectivePath();
            // ADR-044 Slice 2 (axis 3c): a @Action(streaming) action is served at the SAME path
            // a respond-once action would use, as a stream route only.
            for (ActionMetadata action : domain.actions()) {
                if (action.streaming()) {
                    routes.add(new StreamRoute("POST",
                            basePath + "/{id}/actions/" + NameCasing.kebab(action.name()),
                            entityLower + NameCasing.pascal(action.name()) + "StreamHandler"));
                }
            }
            // ADR-043 Slice 1: collection-level SSE live view, GET {base}/stream, no custom
            // headers (TS EventSource).
            if (domain.realTimeApi()) {
                routes.add(new StreamRoute("GET", basePath + "/stream", entityLower + "StreamHandler"));
            }
        }
        return routes;
    }

    private GeneratedFile buildRuntimeLifecycle(List<DomainMetadata> domains, String basePackage) {
        ClassName selfType = ClassName.get(basePackage, "RuntimeLifecycle");
        ClassName componentsType = ClassName.get(basePackage, COMPONENTS_TYPE_NAME);
        TypeName atomicHttpHandler = ParameterizedTypeName.get(ATOMIC_REFERENCE, HTTP_HANDLER);
        TypeName atomicComponents = ParameterizedTypeName.get(ATOMIC_REFERENCE, componentsType);
        List<StreamRoute> streamRoutes = streamRoutes(domains);

        TypeSpec.Builder type = KernelScaffold.publicClass("RuntimeLifecycle")
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated runtime-lifecycle wiring.\n")
                .addJavadoc("<p>Owns both route tables. {@link #$L} builds, before boot, the\n",
                        EDGE_ROUTER_METHOD)
                .addJavadoc("router the kernel holds: the generated stream routes plus a fallthrough\n")
                .addJavadoc("to the handler slot. {@link #run()} takes each entity's Handler from\n")
                .addJavadoc("{@link $T}, builds an {@link $T} with the canonical\n", componentsType, HTTP_ROUTER)
                .addJavadoc("CRUD routes per entity, offers the same builder to\n")
                .addJavadoc("{@link $T#$L($T.Builder)}, publishes the components and\n",
                        componentsType, CONFIGURE_ROUTES_METHOD, HTTP_ROUTER)
                .addJavadoc("then the decorated router, and parks the JVM on a shutdown latch.\n")
                .addJavadoc("<p>Construction of the Repository → Service → Handler chain lives in\n")
                .addJavadoc("{@link $T}, which is where it can be overridden.\n", componentsType)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(KernelScaffold.loggerField(selfType))
                .addField(FieldSpec.builder(atomicHttpHandler, HANDLER_SLOT,
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(atomicComponents, COMPONENTS_SLOT,
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(componentsType, COMPONENTS_FIELD,
                        Modifier.PRIVATE, Modifier.FINAL).build());

        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(atomicHttpHandler, HANDLER_SLOT)
                .addParameter(atomicComponents, COMPONENTS_SLOT)
                .addParameter(componentsType, COMPONENTS_FIELD)
                .addJavadoc("@param $L receives the decorated respond-once router; the edge router\n",
                        HANDLER_SLOT)
                .addJavadoc("       built by {@link #$L} forwards to it\n", EDGE_ROUTER_METHOD)
                .addJavadoc("@param $L receives {@code $L} once every stream target is built;\n",
                        COMPONENTS_SLOT, COMPONENTS_FIELD)
                .addJavadoc("       the edge router's stream routes read it\n")
                .addJavadoc("@param $L the composed application\n", COMPONENTS_FIELD)
                .addStatement("this.$L = $L", HANDLER_SLOT, HANDLER_SLOT)
                .addStatement("this.$L = $L", COMPONENTS_SLOT, COMPONENTS_SLOT)
                .addStatement("this.$L = $L", COMPONENTS_FIELD, COMPONENTS_FIELD)
                .build());

        // The two-argument constructor, for a hand-rolled launcher that binds its own forwarding
        // handler. Such a launcher serves respond-once routes only: the components slot below is
        // one no edge router reads.
        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(atomicHttpHandler, HANDLER_SLOT)
                .addParameter(componentsType, COMPONENTS_FIELD)
                .addJavadoc("For a hand-rolled launcher that binds its own server handler.\n")
                .addJavadoc("<p>Publishes the components to a slot nothing reads, so an application\n")
                .addJavadoc("composed this way serves no generated stream route. Bind\n")
                .addJavadoc("{@link #$L($T, $T)} as the server handler and use\n",
                        EDGE_ROUTER_METHOD, ATOMIC_REFERENCE, ATOMIC_REFERENCE)
                .addJavadoc("{@link #RuntimeLifecycle($T, $T, $T)} to serve them.\n",
                        ATOMIC_REFERENCE, ATOMIC_REFERENCE, componentsType)
                .addJavadoc("@param $L receives the decorated respond-once router\n", HANDLER_SLOT)
                .addJavadoc("@param $L the composed application\n", COMPONENTS_FIELD)
                .addStatement("this($L, new $T<>(), $L)", HANDLER_SLOT, ATOMIC_REFERENCE, COMPONENTS_FIELD)
                .build());

        type.addMethod(buildEdgeRouterMethod(streamRoutes, atomicHttpHandler, atomicComponents, componentsType));
        type.addMethod(buildRunMethod(domains, streamRoutes));
        if (!streamRoutes.isEmpty()) {
            type.addMethod(buildLazyStreamMethod(atomicComponents, componentsType));
        }

        return new GeneratedFile(basePackage, "RuntimeLifecycle",
                KernelScaffold.render(basePackage, type.build()), ArtifactType.APPLICATION);
    }

    /**
     * Emits {@code RuntimeLifecycle.edgeRouter(handlerSlot, componentsSlot)} — the router
     * {@code Application} binds as {@code HTTP_SERVER_HANDLER} (ADR-070, Amendment 2).
     *
     * <p>It has to exist before boot: the http subsystem reads its handler once, at
     * {@code start()}, and a running engine refuses a new one. It has to <em>be</em> an
     * {@code HttpRouter}: the kernel's stream dispatcher resolves a stream only through
     * {@code handler instanceof HttpRouter}. Both hold for a router built from the stream
     * routes, which are known at generation time, with targets that read the components
     * composed later inside the boot callback. Composition itself stays inside the callback,
     * because the kernel scopes it reads are bound only there.
     *
     * <p>Emitted for every application, with or without stream routes: one uniform shape,
     * and with none it behaves exactly as a forwarding handler.
     */
    private MethodSpec buildEdgeRouterMethod(List<StreamRoute> streamRoutes, TypeName atomicHttpHandler,
                                             TypeName atomicComponents, ClassName componentsType) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(EDGE_ROUTER_METHOD)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(HTTP_ROUTER)
                .addParameter(atomicHttpHandler, HANDLER_SLOT)
                .addParameter(atomicComponents, COMPONENTS_SLOT)
                .addJavadoc("Builds the router the application binds as the kernel's server handler.\n")
                .addJavadoc("<p>Built before boot, because the kernel reads its server handler once, when\n")
                .addJavadoc("the http subsystem starts — ahead of the boot callback in which\n")
                .addJavadoc("{@link $T} is composed. It carries every generated stream\n", componentsType)
                .addJavadoc("route, because the kernel resolves a stream only on a handler that is an\n")
                .addJavadoc("{@link $T}; each route reads its handler from {@code $L}\n", HTTP_ROUTER, COMPONENTS_SLOT)
                .addJavadoc("when a stream opens. Every other request falls through {@code notFound} to\n")
                .addJavadoc("whatever {@code $L} holds — the router {@link #run()} builds, as\n", HANDLER_SLOT)
                .addJavadoc("{@link $T#$L} returned it — and is answered\n", componentsType, DECORATE_METHOD)
                .addJavadoc("{@link $T#SERVICE_UNAVAILABLE} while that slot is empty.\n", HTTP_STATUS)
                .addJavadoc("<p>A stream opened before {@code $L} is set is closed at once: its\n", COMPONENTS_SLOT)
                .addJavadoc("response head is written before any handler runs, so it cannot be refused\n")
                .addJavadoc("with a status.\n")
                .addJavadoc("<p>A hand-rolled launcher binds this too, and shares both slots with the\n")
                .addJavadoc("lifecycle it composes:\n")
                .addJavadoc("<pre>{@code\n")
                .addJavadoc("var handlerSlot = new AtomicReference<HttpHandler>();\n")
                .addJavadoc("var componentsSlot = new AtomicReference<$L>();\n", COMPONENTS_TYPE_NAME)
                .addJavadoc("ScopedValue.where(HttpKernelProviders.HTTP_SERVER_HANDLER,\n")
                .addJavadoc("        RuntimeLifecycle.$L(handlerSlot, componentsSlot)).call(() -> {\n",
                        EDGE_ROUTER_METHOD)
                .addJavadoc("    KernelBootstrap.builder().selector(selector).build().boot(() ->\n")
                .addJavadoc("            new RuntimeLifecycle(handlerSlot, componentsSlot, components).run());\n")
                .addJavadoc("    return null;\n")
                .addJavadoc("});\n")
                .addJavadoc("}</pre>\n")
                .addJavadoc("@param $L the slot {@link #run()} fills with the decorated respond-once router\n",
                        HANDLER_SLOT)
                .addJavadoc("@param $L the slot {@link #run()} fills with the composed components\n",
                        COMPONENTS_SLOT)
                .addJavadoc("@return the edge router; bind it as {@code HTTP_SERVER_HANDLER}\n")
                .addStatement("$T.Builder edge = $T.builder()", HTTP_ROUTER, HTTP_ROUTER);
        for (StreamRoute route : streamRoutes) {
            method.addStatement("edge.streamRoute($T.$L, $S, $L($L, $T::$L))",
                    HTTP_METHOD, route.method(), route.path(), LAZY_STREAM_METHOD, COMPONENTS_SLOT,
                    componentsType, route.accessor());
        }
        return method
                .addStatement("return edge.notFound(exchange -> {\n"
                                + "    $T handler = $L.get();\n"
                                + "    if (handler != null) {\n"
                                + "        handler.handle(exchange);\n"
                                + "    } else {\n"
                                + "        exchange.respond($T.SERVICE_UNAVAILABLE);\n"
                                + "    }\n"
                                + "}).build()",
                        HTTP_HANDLER, HANDLER_SLOT, HTTP_STATUS)
                .build();
    }

    /**
     * Emits the {@code lazyStream} helper the edge router's stream routes share — only when
     * there is a stream route to use it.
     */
    private MethodSpec buildLazyStreamMethod(TypeName atomicComponents, ClassName componentsType) {
        TypeName target = ParameterizedTypeName.get(FUNCTION, componentsType, HTTP_STREAM_HANDLER);
        return MethodSpec.methodBuilder(LAZY_STREAM_METHOD)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(HTTP_STREAM_HANDLER)
                .addParameter(atomicComponents, COMPONENTS_SLOT)
                .addParameter(target, "target")
                .addJavadoc("A stream route target that resolves its handler from the composed\n")
                .addJavadoc("components when a stream opens.\n")
                .addJavadoc("<p>{@link #run()} forces every target accessor before it sets\n")
                .addJavadoc("{@code $L}, so {@code target} only ever reads a memoised\n", COMPONENTS_SLOT)
                .addJavadoc("field here — construction never runs on a stream thread, where the\n")
                .addJavadoc("boot-scope providers a factory reads are not bound.\n")
                .addStatement("return exchange -> {\n"
                                + "    $T components = $L.get();\n"
                                + "    if (components == null) {\n"
                                + "        exchange.close();\n"
                                + "        return;\n"
                                + "    }\n"
                                + "    target.apply(components).handle(exchange);\n"
                                + "}",
                        componentsType, COMPONENTS_SLOT)
                .build();
    }

    private MethodSpec buildRunMethod(List<DomainMetadata> domains, List<StreamRoute> streamRoutes) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addJavadoc("Composes the application, starts its saga plans and subscribers,\n")
                .addJavadoc("publishes it to the components slot and the decorated router to the\n")
                .addJavadoc("handler slot, and parks on a shutdown latch until the JVM exits; then\n")
                .addJavadoc("releases the subscribers.\n");

        // T49: the per-entity Repository → Service → Handler chain is built by
        // RuntimeComponents, not here. Only the handlers need a local, because only they
        // are named by a route; the repository and the service stay reachable through the
        // components object, which is what a consumer's override reads.
        for (DomainMetadata domain : domains) {
            String entity = domain.entityName();
            String entityLower = lowerFirst(entity);
            ClassName handlerType = ClassName.get(infraPackages(domain).handler(), entity + "Handler");
            method.addStatement("$T $LHandler = $L.$LHandler()",
                    handlerType, entityLower, COMPONENTS_FIELD, entityLower);
        }

        // Event types. A publisher registers its entity's event types with the engine's registry
        // when it is constructed, and the kernel bus refuses a subscription to a type nobody has
        // registered (InMemoryEventBus.subscribe → EventBusException.subscriptionRejected). A
        // handler that publishes builds its publisher above; an entity whose events are all
        // published elsewhere (MANUAL, STATE_TRANSITION, SCHEDULED, …) has no handler to build
        // it, and without this its live-view stream would be refused on every open. Built here,
        // before anything can subscribe.
        List<String> publishers = domains.stream().filter(DomainMetadata::hasEvents)
                .map(d -> lowerFirst(d.entityName()) + "EventPublisher").toList();
        if (!publishers.isEmpty()) {
            method.addComment("Event types are registered by constructing each publisher; the bus refuses")
                    .addComment("a subscription to an unregistered type, so they are built before anything")
                    .addComment("subscribes.");
            for (String publisher : publishers) {
                method.addStatement("$L.$L()", COMPONENTS_FIELD, publisher);
            }
        }

        // Activation, before either slot opens the application. Sagas first: each
        // initialize() compiles and registers its plan, which the kernel needs before it can
        // resume a parked instance (ADR-064) — lazy compilation on the first schedule() would
        // not happen for an instance parked across a restart. Subscribers second, after every
        // publisher has registered its event types (above), since the bus refuses a
        // subscription to an unregistered type.
        List<String> sagas = sagaAccessors(domains);
        List<String> subscribers = subscriberAccessors(domains);
        if (!sagas.isEmpty()) {
            method.addComment("Saga plans are compiled and registered at boot, not on first schedule():")
                    .addComment("a parked saga resumes only on a registered plan version.");
            for (String saga : sagas) {
                method.addStatement("$L.$L().initialize()", COMPONENTS_FIELD, saga);
            }
        }
        if (!subscribers.isEmpty()) {
            method.addComment("Subscribers start receiving before the application serves anything.");
            for (String subscriber : subscribers) {
                method.addStatement("$L.$L().subscribe()", COMPONENTS_FIELD, subscriber);
            }
        }

        // The stream handlers are served by the edge router, which reaches them
        // through their accessors. Forcing each accessor here builds it on the boot thread,
        // inside the kernel scope its factory reads, before componentsSlot publishes the
        // object — so the unsynchronised memo field is written once, here, and a stream thread
        // only ever reads it.
        if (!streamRoutes.isEmpty()) {
            method.addComment("Stream route targets, served by edgeRouter(...): built now, on the boot")
                    .addComment("thread, so no factory ever runs on a stream thread.");
            for (StreamRoute route : streamRoutes) {
                method.addStatement("$L.$L()", COMPONENTS_FIELD, route.accessor());
            }
        }

        // Respond-once router. Stream routes are not registered here: the kernel never asks this
        // router to resolve a stream, because it only ever holds the edge router.
        method.addStatement("$T.Builder routerBuilder = $T.builder()", HTTP_ROUTER, HTTP_ROUTER);
        for (DomainMetadata domain : domains) {
            String entityLower = lowerFirst(domain.entityName());
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
            // "handle" + pascal(name). A @Action(streaming) action has no respond-once
            // route at all; its stream route is on the edge router.
            for (ActionMetadata action : domain.actions()) {
                if (!action.streaming()) {
                    method.addStatement("routerBuilder.route($T.POST, $S, $LHandler::$L)",
                            HTTP_METHOD, basePath + "/{id}/actions/" + NameCasing.kebab(action.name()),
                            entityLower, "handle" + NameCasing.pascal(action.name()));
                }
            }
        }
        // T49: the consumer's routes are registered after every generated one and before
        // build(), so a hand-written route can add to the table but never silently displace
        // a generated one.
        method.addStatement("$L.$L(routerBuilder)", COMPONENTS_FIELD, CONFIGURE_ROUTES_METHOD);
        method.addStatement("$T router = routerBuilder.build()", HTTP_ROUTER);
        // T49 residual: the consumer's one chance to wrap the router before it is served.
        // Whatever decorate(...) returns is what every respond-once request reaches. No type
        // guard: the kernel holds the edge router, never this object, so a wrapper here cannot
        // erase a stream route.
        method.addStatement("$T handler = $L.$L(router)", HTTP_HANDLER, COMPONENTS_FIELD, DECORATE_METHOD);
        // Components before handler: once the handler slot is set the application is serving,
        // and its streams are part of what it serves. The other order would leave a window in
        // which respond-once routes answer and every stream closes on open.
        method.addStatement("$L.set($L)", COMPONENTS_SLOT, COMPONENTS_FIELD);
        method.addStatement("$L.set(handler)", HANDLER_SLOT);
        // The entity count is known at generation time, so it is baked into the literal rather
        // than passed as a parameter — one fewer MessageFormat call at runtime, same output.
        method.addStatement("LOG.log($T.INFO, $S)", KernelScaffold.LOGGER_LEVEL,
                "Application bootstrap complete: " + domains.size() + " entities wired");

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

        // Subscribers stop receiving on the way out, in reverse order, before the
        // boot callback returns and the kernel stops the event engine under them.
        if (!subscribers.isEmpty()) {
            method.addComment("Subscribers are released in reverse order before the kernel stops.");
            for (int i = subscribers.size() - 1; i >= 0; i--) {
                method.addStatement("$L.$L().unsubscribe()", COMPONENTS_FIELD, subscribers.get(i));
            }
        }

        return method.build();
    }

    /** The saga-flow accessors, in domain order — the order {@code run()} initializes them in. */
    private List<String> sagaAccessors(List<DomainMetadata> domains) {
        List<String> accessors = new ArrayList<>();
        for (DomainMetadata domain : domains) {
            ClassName sagaFlowType = KernelSagaGenerator.sagaFlowType(domain);
            if (sagaFlowType != null) {
                accessors.add(sagaAccessor(sagaFlowType));
            }
        }
        return accessors;
    }

    /** The subscriber accessors, in domain order — subscribed in it, released in reverse. */
    private List<String> subscriberAccessors(List<DomainMetadata> domains) {
        return domains.stream().filter(DomainMetadata::hasEvents)
                .map(d -> lowerFirst(d.entityName()) + "EventSubscriber").toList();
    }

    /** The {@code RuntimeComponents} accessor for a saga flow: its simple name, lower-camel. */
    private String sagaAccessor(ClassName sagaFlowType) {
        return lowerFirst(sagaFlowType.simpleName());
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
