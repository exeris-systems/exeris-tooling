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
import eu.exeris.sdk.sourcemodel.ast.RelationshipMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
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
 *   <li>{@code RuntimeLifecycle.java} — Receives the
 *       {@code RuntimeComponents}, takes each declared entity's
 *       {@code *Handler} from it, builds an
 *       {@link eu.exeris.kernel.core.http.routing.HttpRouter} with the
 *       five canonical CRUD routes per entity (GET-all / GET-by-id /
 *       POST-create / PUT-update / DELETE), sets the forwarding handler
 *       slot, and parks on a {@link java.util.concurrent.CountDownLatch}
 *       until the JVM shuts down.</li>
 * </ul>
 * <p>When the build also carries a capability composition (G2, 0.7.0), the
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
    private static final ClassName KERNEL_PROVIDERS =
            ClassName.get("eu.exeris.kernel.spi.context", "KernelProviders");
    private static final ClassName TRANSACTIONAL_EXECUTOR =
            ClassName.get("eu.exeris.kernel.spi.persistence", "TransactionalExecutor");
    private static final ClassName TRANSACTION_ORCHESTRATOR =
            ClassName.get("eu.exeris.kernel.core.persistence", "TransactionOrchestrator");

    // G2 (ADR-024, 2026-07-21 "Boot Conductor Call Site" amendment): the SKU-side
    // boot conductor. Emitted ONLY into a build that actually has a composition —
    // see buildApplication(String, boolean).
    private static final ClassName COMPOSITION_CONDUCTOR =
            ClassName.get("eu.exeris.sdk.composition.runtime", "CompositionConductor");
    private static final ClassName PATH = ClassName.get("java.nio.file", "Path");
    private static final String CAP_MANIFEST_METHOD = "capManifest";
    private static final String CAP_MANIFEST_FILE = "cap-manifest.json";
    private static final String CAP_MANIFEST_PROPERTY = "exeris.capManifest";

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
     *                 {@link #buildApplication(String, boolean)}
     * @return the three emitted files; always
     *         {@code [Application, RuntimeComponents, RuntimeLifecycle]}
     * @since 0.7.0
     */
    public List<GeneratedFile> generateAll(List<DomainMetadata> domains, String basePackage,
                                           boolean composed) {
        List<GeneratedFile> files = new ArrayList<>(3);
        files.add(buildApplication(basePackage, composed));
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
     * the forwarding handler answers {@code SERVICE_UNAVAILABLE}). On the way out the
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
    private GeneratedFile buildApplication(String basePackage, boolean composed) {
        ClassName selfType = ClassName.get(basePackage, "Application");
        ClassName lifecycleType = ClassName.get(basePackage, "RuntimeLifecycle");
        TypeName atomicHttpHandler = ParameterizedTypeName.get(ATOMIC_REFERENCE, HTTP_HANDLER);

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
                .addJavadoc("Application entry point. Boots the Kernel under a forwarding\n")
                .addJavadoc("HTTP handler bound via {@link $T} and hands control to\n", SCOPED_VALUE)
                .addJavadoc("{@link $T}.\n", lifecycleType)
                .addJavadoc("<p>While {@link $T#run()} is still composing the per-entity\n", lifecycleType)
                .addJavadoc("wiring (between bootstrap completion and the moment the\n")
                .addJavadoc("handler slot is set), inbound requests respond\n")
                .addJavadoc("{@link $T#SERVICE_UNAVAILABLE} rather than being silently dropped.\n", HTTP_STATUS);
        if (composed) {
            run.addJavadoc("<p>This build has a capability composition, so the boot callback\n")
                    .addJavadoc("also drives the {@link $T}: every cap is\n", COMPOSITION_CONDUCTOR)
                    .addJavadoc("initialized and made ready before the handler slot is set, and\n")
                    .addJavadoc("drained + terminated in reverse {@code initOrder} once the\n")
                    .addJavadoc("shutdown latch releases — before the kernel itself stops.\n");
        }
        MethodSpec runMethod = run
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
        applicationType
                .addJavadoc("<p>Runtime classpath requirements (in addition to\n")
                .addJavadoc("{@code exeris-kernel-spi} / {@code -core}): a kernel persistence\n")
                .addJavadoc("provider (Community driver with a configured PostgreSQL DataSource —\n")
                .addJavadoc("bound by the kernel bootstrap, not by this generated code).\n")
                .addJavadoc("<p>Generated code logs through {@link System.Logger}, so it adds no\n")
                .addJavadoc("logging dependency of its own. To route it to a backend, put a\n")
                .addJavadoc("{@link System.LoggerFinder} provider on the classpath.\n");
        if (composed) {
            applicationType
                    .addJavadoc("Composition adds {@code eu.exeris:exeris-sdk-composition-runtime}\n")
                    .addJavadoc("(the boot conductor).\n");
        }
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
     * lifecycle in a try-with-resources over the conductor; the plain variant is byte-for-byte
     * what every release before 0.7.0 emitted.
     */
    private CodeBlock bootBlock(ClassName lifecycleType, boolean composed) {
        CodeBlock.Builder block = CodeBlock.builder()
                .add("$T.where($T.HTTP_SERVER_HANDLER, forwardingHandler).call(() -> {\n",
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
                    .add("            new $T(handlerSlot, $L($L())).run();\n",
                            lifecycleType, COMPONENTS_METHOD, TX_EXECUTOR_NAME)
                    .add("        }\n")
                    .add("    });\n");
        } else {
            block.add("    .boot(() -> new $T(handlerSlot, $L($L())).run());\n",
                    lifecycleType, COMPONENTS_METHOD, TX_EXECUTOR_NAME);
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
     * <p>Before this existed, {@code RuntimeLifecycle} constructed every repository,
     * service and handler with an inline {@code new}, and {@code Application} exposed only
     * infrastructure hooks ({@code subsystems()}, {@code transactionalExecutor()},
     * {@code capManifest()}). A consumer could write {@code MyOrderService extends
     * OrderService} — the emitted service is deliberately {@code public} and non-final so
     * that they can (ADR-058) — and then had nowhere to install it.
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
                .addJavadoc("service and handler it wires, instead of constructing them itself.\n")
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
                .addJavadoc("{@code KernelProviders.eventEngine()}, …). Accessors memoise and are\n")
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
                addComponent(type, publisherType, publisherName,
                        CodeBlock.of("new $T($T.eventEngine())", publisherType, KERNEL_PROVIDERS));
            }
            if (KernelHandlerGenerator.publishesFromHandler(domain)) {
                addComponent(type, handlerType, entityLower + "Handler",
                        CodeBlock.of("new $T($L(), $L())", handlerType, serviceName, publisherName));
            } else {
                addComponent(type, handlerType, entityLower + "Handler",
                        CodeBlock.of("new $T($L())", handlerType, serviceName));
            }

            // ADR-043 Slice 1 / ADR-044 Slice 2: the SSE stream handlers are no-arg today,
            // but they go through the same seam so that a consumer overriding one does not
            // have to know which handlers happen to take constructor arguments.
            if (domain.realTimeApi()) {
                ClassName streamHandlerType = ClassName.get(pkgs.handler(), entity + "StreamHandler");
                addComponent(type, streamHandlerType, entityLower + "StreamHandler",
                        CodeBlock.of("new $T()", streamHandlerType));
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
                .addComment("No generated body — override to register hand-written routes.")
                .build());

        return new GeneratedFile(basePackage, COMPONENTS_TYPE_NAME,
                KernelScaffold.render(basePackage, type.build()), ArtifactType.APPLICATION);
    }

    /**
     * Emits one component into {@code RuntimeComponents}: a private field, a public
     * memoising accessor, and the {@code protected create*} factory holding
     * {@code construction}.
     */
    private void addComponent(TypeSpec.Builder type, ClassName componentType,
                              String name, CodeBlock construction) {
        String factory = "create" + Character.toUpperCase(name.charAt(0)) + name.substring(1);

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

    private GeneratedFile buildRuntimeLifecycle(List<DomainMetadata> domains, String basePackage) {
        ClassName selfType = ClassName.get(basePackage, "RuntimeLifecycle");
        ClassName componentsType = ClassName.get(basePackage, COMPONENTS_TYPE_NAME);
        TypeName atomicHttpHandler = ParameterizedTypeName.get(ATOMIC_REFERENCE, HTTP_HANDLER);

        TypeSpec.Builder type = KernelScaffold.publicClass("RuntimeLifecycle")
                .addModifiers(Modifier.FINAL)
                .addJavadoc("Generated runtime-lifecycle wiring.\n")
                .addJavadoc("<p>Takes each entity's Handler from {@link $T}, builds\n", componentsType)
                .addJavadoc("an {@link $T} with the canonical CRUD routes per entity,\n", HTTP_ROUTER)
                .addJavadoc("offers the same builder to {@link $T#$L($T.Builder)},\n",
                        componentsType, CONFIGURE_ROUTES_METHOD, HTTP_ROUTER)
                .addJavadoc("sets the forwarding handler slot, and parks the JVM on a\n")
                .addJavadoc("shutdown latch.\n")
                .addJavadoc("<p>Construction of the Repository → Service → Handler chain lives in\n")
                .addJavadoc("{@link $T}, which is where it can be overridden.\n", componentsType)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n")
                .addField(KernelScaffold.loggerField(selfType))
                .addField(FieldSpec.builder(atomicHttpHandler, "handlerSlot",
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(componentsType, COMPONENTS_FIELD,
                        Modifier.PRIVATE, Modifier.FINAL).build());

        type.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(atomicHttpHandler, "handlerSlot")
                .addParameter(componentsType, COMPONENTS_FIELD)
                .addStatement("this.handlerSlot = handlerSlot")
                .addStatement("this.$L = $L", COMPONENTS_FIELD, COMPONENTS_FIELD)
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

        // T49: the per-entity Repository → Service → Handler chain is built by
        // RuntimeComponents, not here. Only the handlers need a local, because only they
        // are named by a route; the repository and the service stay reachable through the
        // components object, which is what a consumer's override reads.
        for (DomainMetadata domain : domains) {
            String entity = domain.entityName();
            String entityLower = lowerFirst(entity);
            String handlerPkg = infraPackages(domain).handler();

            ClassName handlerType = ClassName.get(handlerPkg, entity + "Handler");

            method.addStatement("$T $LHandler = $L.$LHandler()",
                    handlerType, entityLower, COMPONENTS_FIELD, entityLower);
            // ADR-043 Slice 1: instantiate the per-entity SSE live-view stream
            // handler when @ExerisDomain(realTimeApi). Emitted by
            // KernelStreamHandlerGenerator into the same .handler package.
            if (domain.realTimeApi()) {
                ClassName streamHandlerType = ClassName.get(handlerPkg, entity + "StreamHandler");
                method.addStatement("$T $LStreamHandler = $L.$LStreamHandler()",
                        streamHandlerType, entityLower, COMPONENTS_FIELD, entityLower);
            }
            // ADR-044 Slice 2: instantiate one per-action SSE stream handler per
            // @Action(streaming) action (no-arg, like the Slice-1 handler). Emitted
            // by KernelActionStreamHandlerGenerator into the same .handler package,
            // named <Entity><ActionPascal>StreamHandler.
            for (ActionMetadata action : domain.actions()) {
                if (action.streaming()) {
                    String actionPascal = NameCasing.pascal(action.name());
                    ClassName actionStreamHandlerType =
                            ClassName.get(handlerPkg, entity + actionPascal + "StreamHandler");
                    method.addStatement("$T $L$LStreamHandler = $L.$L$LStreamHandler()",
                            actionStreamHandlerType, entityLower, actionPascal,
                            COMPONENTS_FIELD, entityLower, actionPascal);
                }
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
            //
            // ADR-044 Slice 2 (axis 3c): a @Action(streaming) action is registered at
            // the SAME path but via the router's typed streamRoute(POST, ...) — the
            // request opens the stream (resolves to an HttpStreamExchange, never an
            // HttpExchange). A streaming action gets the streamRoute ONLY, not the
            // respond-once route(...) too.
            for (ActionMetadata action : domain.actions()) {
                String actionPath = basePath + "/{id}/actions/" + NameCasing.kebab(action.name());
                String actionPascal = NameCasing.pascal(action.name());
                if (action.streaming()) {
                    method.addStatement("routerBuilder.streamRoute($T.POST, $S, $L$LStreamHandler::handle)",
                            HTTP_METHOD, actionPath, entityLower, actionPascal);
                } else {
                    method.addStatement("routerBuilder.route($T.POST, $S, $LHandler::$L)",
                            HTTP_METHOD, actionPath, entityLower, "handle" + actionPascal);
                }
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
        // T49: the consumer's routes are registered after every generated one and before
        // build(), so a hand-written route can add to the table but never silently displace
        // a generated one.
        method.addStatement("$L.$L(routerBuilder)", COMPONENTS_FIELD, CONFIGURE_ROUTES_METHOD);
        method.addStatement("$T router = routerBuilder.build()", HTTP_ROUTER);
        // Publish the HttpRouter INSTANCE, not a `router::handle` method-ref.
        // The kernel stream dispatcher resolves a streaming route only when the
        // bound handler IS an HttpRouter (`handler instanceof HttpRouter` →
        // router.resolveStream(...)); a method-ref lambda erases that type, so
        // every generated streamRoute(...) registration (Slice 1 entity-level +
        // Slice 2 per-action) would silently fall back to respond-once / 404 on
        // a real boot. HttpRouter implements HttpHandler, so the forwarding
        // slot's respond-once dispatch is byte-for-byte identical either way.
        method.addStatement("handlerSlot.set(router)");
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
