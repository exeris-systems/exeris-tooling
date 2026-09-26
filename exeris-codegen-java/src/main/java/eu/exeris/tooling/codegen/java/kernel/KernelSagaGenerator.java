package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaMetadata;
import eu.exeris.sdk.sourcemodel.ast.SagaStepMetadata;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Kernel Saga Generator.
 * <p>
 * Emits a per-entity {@code *SagaFlow} class that wraps the Open-Core SPI
 * flow framework ({@code eu.exeris.kernel.spi.flow.{FlowEngine,
 * FlowDefinitionBuilder, FlowExecutionPlan, FlowContext, FlowOutcome}}).
 * The emitted class is a <b>skeleton</b> — step actions and their
 * compensations default to logging stubs that return
 * {@link eu.exeris.kernel.spi.flow.model.FlowOutcome#CONTINUE};
 * downstream consumers extend the generated class and override the
 * {@code protected} step methods to supply real business logic.
 * Canonical wiring shape mirrors the working community benchmark
 * app's {@code OrderSagaOrchestrator} (under
 * {@code exeris-benchmarks/targets/exeris-community-app}).
 * <p>
 * Emitted skeleton contract:
 * <ul>
 *   <li>{@code public synchronized FlowExecutionPlan initialize()} —
 *       lazy-builds and compiles the {@link
 *       eu.exeris.kernel.spi.flow.model.FlowDefinition} via
 *       {@code flowEngine.plans().newDefinition(NAME).step(...).step(...)
 *       .transition(...).build()}; idempotent.</li>
 *   <li>{@code public void schedule(FlowContext ctx)} — invokes
 *       {@code initialize()} then hands the plan + context to
 *       {@code flowEngine.scheduler().schedule(...)}.</li>
 *   <li>One {@code protected FlowOutcome <stepName>(FlowContext)} method
 *       per declared {@link SagaStepMetadata}, plus one matching
 *       {@code compensate<StepName>} when the step declares a
 *       compensation.</li>
 * </ul>
 * <p>
 * Transitions are emitted as a strict linear chain in the order steps
 * appear in {@code SagaMetadata.steps()} ({@code transition(0,
 * 1).transition(1, 2)...}). The {@link SagaStepMetadata#order()} field
 * is <b>not</b> consulted — callers wanting non-list ordering must
 * sort their step list before passing it to the AST. The flow
 * {@code timeoutDuration} is parsed once from the saga's ISO-8601
 * timeout string at class-init time via
 * {@link java.time.Duration#parse(CharSequence)} and pinned to a
 * {@code static final long TIMEOUT_NANOS}; {@code maxRetries} comes
 * directly from the metadata.
 * <p>
 * <b>Plan version (kernel ADR-064, Stellar K5).</b> A saga that declares
 * {@code @Saga(version = n)} with {@code n > 1} gets a
 * {@code private static final int DEFINITION_VERSION = n} and a
 * {@code builder.version(DEFINITION_VERSION)} call straight after
 * {@code newDefinition(DEFINITION_NAME)}, since {@code (name, version)} is the
 * plan's identity in the kernel's catalog. This uses
 * {@code FlowDefinitionBuilder.version(int)}, which exists from kernel 0.12.
 * A saga at version 1 (the annotation default, and
 * {@code FlowDefinition.INITIAL_VERSION}) emits neither, and there are three
 * reasons for that:
 * <ul>
 *   <li>it is the version an unversioned builder already produces, so the
 *       omission loses nothing: the plan identity is the same;</li>
 *   <li>every saga that declares no version keeps byte-identical output, so
 *       a consumer's committed tree does not churn for a no-op;</li>
 *   <li>{@code version(int)} is a {@code default} method that <em>throws</em>
 *       on a builder that does not override it. Emitting it unconditionally
 *       would break every saga on an out-of-tree engine, including the ones
 *       that never asked for a version. Emitting it only when declared confines
 *       that failure to where it is wanted: such an engine cannot host a
 *       version-3 plan and should say so rather than build version 1.</li>
 * </ul>
 * A declared version below 1 is refused at generation time, because the kernel
 * would refuse it at the saga's first {@code initialize()}.
 * <p>
 * <b>Composition (T48 slice C1).</b> {@code RuntimeComponents} builds the flow
 * through {@code create<Flow>()} — {@code sagaFlowType(...)} names the accessor — so a
 * consumer's subclass is installed by overriding that factory, and
 * {@code RuntimeLifecycle} calls {@code initialize()} at boot. That is load-bearing, not
 * an optimisation: the kernel resumes a parked instance only on a registered plan
 * version (ADR-064), and a plan compiled lazily by the first {@code schedule()} is
 * never registered for an instance parked across a restart.
 * <p>
 * The legacy generator's saga DSL (SagaBuilder / SagaEngine / step-name
 * pattern dispatch / nested {@code State} record) is dropped. The new
 * skeleton is much smaller; downstream consumers compose real saga
 * behaviour by subclassing rather than by populating a state record.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public class KernelSagaGenerator implements KernelArtifactGenerator {

    private static final ClassName DURATION = ClassName.get("java.time", "Duration");

    private static final ClassName FLOW_ENGINE =
            ClassName.get("eu.exeris.kernel.spi.flow", "FlowEngine");
    private static final ClassName FLOW_EXECUTION_PLAN =
            ClassName.get("eu.exeris.kernel.spi.flow.model", "FlowExecutionPlan");
    private static final ClassName FLOW_DEFINITION_BUILDER =
            ClassName.get("eu.exeris.kernel.spi.flow", "FlowDefinitionBuilder");
    private static final ClassName FLOW_CONTEXT =
            ClassName.get("eu.exeris.kernel.spi.flow.model", "FlowContext");
    private static final ClassName FLOW_OUTCOME =
            ClassName.get("eu.exeris.kernel.spi.flow.model", "FlowOutcome");

    /**
     * The version a definition carries when its builder is never told one — the kernel's
     * {@code FlowDefinition.INITIAL_VERSION}, restated because this module names kernel types
     * only as emitted text and has no compile dependency on the SPI. Also the SDK default of
     * {@code @Saga.version} and of {@code SagaMetadata.version}.
     */
    private static final int INITIAL_VERSION = 1;

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!metadata.isSaga() || metadata.sagaMetadata() == null) {
            return null;
        }
        SagaMetadata saga = metadata.sagaMetadata();
        List<SagaStepMetadata> steps = effectiveSteps(metadata);

        assertDistinctMethodNames(metadata.entityName(), steps);

        ClassName selfType = sagaFlowType(metadata);
        String packageName = selfType.packageName();
        String entity = metadata.entityName();
        String sagaName = definitionName(metadata);
        String className = selfType.simpleName();

        assertSupportedVersion(entity, sagaName, saga.version());
        boolean versioned = saga.version() != INITIAL_VERSION;

        String timeoutIso = saga.timeout() != null && !saga.timeout().isBlank()
                ? saga.timeout() : "PT30M";
        int maxRetries = saga.maxRetries() > 0 ? saga.maxRetries() : 3;

        TypeSpec.Builder builder = KernelScaffold.publicClass(className)
                .addJavadoc("Generated saga skeleton for $L.\n", entity)
                .addJavadoc("<p>Subclass and override the {@code protected} step methods to\n")
                .addJavadoc("provide real business logic. The default body for every step\n")
                .addJavadoc("(action and compensation) logs and returns\n")
                .addJavadoc("{@link $T#CONTINUE}.\n", FLOW_OUTCOME)
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain model.\n")
                .addField(KernelScaffold.loggerField(selfType))
                .addField(FieldSpec.builder(String.class, "DEFINITION_NAME",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", sagaName)
                        .build());

        // Only a declared version reaches the emitted source — see the class Javadoc for why
        // version 1 emits nothing. Sits beside DEFINITION_NAME because the two are one identity.
        if (versioned) {
            builder.addField(FieldSpec.builder(TypeName.INT, "DEFINITION_VERSION",
                            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .addJavadoc("From {@code @Saga(version = $L)}. With {@link #DEFINITION_NAME} it is\n",
                            saga.version())
                    .addJavadoc("this plan's identity in the kernel plan catalog (ADR-064): a parked\n")
                    .addJavadoc("instance resumes only on the version it parked under, and moving it\n")
                    .addJavadoc("across versions takes a registered migration.\n")
                    .initializer("$L", saga.version())
                    .build());
        }

        builder
                .addField(FieldSpec.builder(TypeName.LONG, "TIMEOUT_NANOS",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.parse($S).toNanos()", DURATION, timeoutIso)
                        .build())
                .addField(FieldSpec.builder(TypeName.INT, "MAX_RETRIES",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$L", maxRetries)
                        .build())
                .addField(FieldSpec.builder(FLOW_ENGINE, "flowEngine",
                        Modifier.PRIVATE, Modifier.FINAL).build())
                .addField(FieldSpec.builder(FLOW_EXECUTION_PLAN, "plan",
                        Modifier.PRIVATE, Modifier.VOLATILE).build());

        builder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(FLOW_ENGINE, "flowEngine")
                .addStatement("this.flowEngine = flowEngine")
                .build());

        builder.addMethod(buildInitialize(steps, versioned));
        builder.addMethod(buildSchedule());

        for (SagaStepMetadata step : steps) {
            builder.addMethod(buildStepAction(step));
            if (hasCompensation(step)) {
                builder.addMethod(buildStepCompensation(step));
            }
        }

        return new GeneratedFile(packageName, className,
                KernelScaffold.render(packageName, builder.build()), ArtifactType.SAGA);
    }

    /**
     * The emitted flow class for an entity, or {@code null} when the entity declares no saga.
     *
     * <p>Shared with the generated {@code *SagaFlowTest} (T2/ADR-058) so the test cannot name a
     * class the emitter does not produce — the name is derived, not spelled: a {@code @Saga(name)}
     * that already ends in {@code Flow} is used as-is, anything else gains the suffix.
     */
    static ClassName sagaFlowType(DomainMetadata metadata) {
        if (!metadata.isSaga() || metadata.sagaMetadata() == null) {
            return null;
        }
        String sagaName = definitionName(metadata);
        return ClassName.get(metadata.packageName().replace(".domain", ".saga"),
                sagaName.endsWith("Flow") ? sagaName : sagaName + "Flow");
    }

    /** The {@code DEFINITION_NAME} the emitted flow registers under. */
    static String definitionName(DomainMetadata metadata) {
        String declared = metadata.sagaMetadata().name();
        return declared != null && !declared.isBlank() ? declared : metadata.entityName() + "Saga";
    }

    /**
     * The step list the emitter walks — the declared steps, or the single compilable placeholder
     * ({@code FlowDefinitionBuilder} requires at least one step, so an entity that declares none
     * still has to yield a schedulable class).
     *
     * <p>Package-private rather than private because the derivation is a property of the saga
     * surface, not of one emitter: anything reasoning about how many steps an entity produces has
     * to agree with this. Today {@link #generate} is the only caller — the generated saga test
     * deliberately asserts over the steps the flow builder <em>recorded</em> rather than over a
     * count derived here, which is what keeps that assertion non-circular.
     */
    static List<SagaStepMetadata> effectiveSteps(DomainMetadata metadata) {
        SagaMetadata saga = metadata.sagaMetadata();
        return (saga.steps() != null && !saga.steps().isEmpty())
                ? saga.steps()
                : List.of(SagaStepMetadata.simple("process", 0, null));
    }

    private MethodSpec buildInitialize(List<SagaStepMetadata> steps, boolean versioned) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("initialize")
                .addModifiers(Modifier.PUBLIC, Modifier.SYNCHRONIZED)
                .returns(FLOW_EXECUTION_PLAN)
                .addJavadoc("Lazy-builds and compiles the flow definition. Idempotent — repeat\n")
                .addJavadoc("calls return the cached plan. Always invoked by\n")
                .addJavadoc("{@link #schedule(eu.exeris.kernel.spi.flow.model.FlowContext)};\n")
                .addJavadoc("consumers may also call it explicitly at bootstrap to amortise\n")
                .addJavadoc("compilation.\n")
                .addJavadoc("@return the compiled {@link $T} for this saga\n", FLOW_EXECUTION_PLAN)
                .beginControlFlow("if (plan != null)")
                .addStatement("return plan")
                .endControlFlow()
                .addStatement("$T builder = flowEngine.plans().newDefinition(DEFINITION_NAME)",
                        FLOW_DEFINITION_BUILDER);
        if (versioned) {
            method.addStatement("builder.version(DEFINITION_VERSION)");
        }

        for (SagaStepMetadata step : steps) {
            String stepName = step.name();
            String methodName = toMethodName(stepName);
            if (hasCompensation(step)) {
                String compMethodName = "compensate" + capitalize(methodName);
                method.addStatement("builder.step($S, this::$L, this::$L)",
                        stepName, methodName, compMethodName);
            } else {
                method.addStatement("builder.step($S, this::$L, null)",
                        stepName, methodName);
            }
        }

        for (int i = 0; i < steps.size() - 1; i++) {
            method.addStatement("builder.transition($L, $L)", i, i + 1);
        }

        method.addStatement("builder.timeoutDuration(TIMEOUT_NANOS).maxRetries(MAX_RETRIES)")
                .addStatement("this.plan = flowEngine.plans().compile(builder.build())")
                .addStatement("return this.plan");

        return method.build();
    }

    private MethodSpec buildSchedule() {
        return MethodSpec.methodBuilder("schedule")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addParameter(FLOW_CONTEXT, "context")
                .addJavadoc("Schedules this saga instance on the flow scheduler. Calls\n")
                .addJavadoc("{@link #initialize()} on first use so the plan is compiled lazily.\n")
                .addJavadoc("@param context the per-instance flow context (instance UUID,\n")
                .addJavadoc("       definition name, starting step, state, timeout)\n")
                .addStatement("flowEngine.scheduler().schedule(initialize(), context)")
                .build();
    }

    private MethodSpec buildStepAction(SagaStepMetadata step) {
        return MethodSpec.methodBuilder(toMethodName(step.name()))
                .addModifiers(Modifier.PROTECTED)
                .returns(FLOW_OUTCOME)
                .addParameter(FLOW_CONTEXT, "context")
                .addJavadoc("Action for saga step {@code $L}.\n", step.name())
                .addJavadoc("<p>Default implementation logs and returns {@link $T#CONTINUE};\n", FLOW_OUTCOME)
                .addJavadoc("override in a subclass to add real business logic.\n")
                .addStatement("LOG.log($T.DEBUG, $S, DEFINITION_NAME, context.currentStep())",
                        KernelScaffold.LOGGER_LEVEL, KernelScaffold.escapeQuotes(
                                "[{0}] step '" + step.name() + "' at index {1} — override to implement"))
                .addStatement("return $T.CONTINUE", FLOW_OUTCOME)
                .build();
    }

    private MethodSpec buildStepCompensation(SagaStepMetadata step) {
        String compensationName = "compensate" + capitalize(toMethodName(step.name()));
        return MethodSpec.methodBuilder(compensationName)
                .addModifiers(Modifier.PROTECTED)
                .returns(FLOW_OUTCOME)
                .addParameter(FLOW_CONTEXT, "context")
                .addJavadoc("Compensation for saga step {@code $L}.\n", step.name())
                .addJavadoc("<p>Default implementation logs and returns {@link $T#CONTINUE};\n", FLOW_OUTCOME)
                .addJavadoc("override in a subclass to add real rollback logic.\n")
                .addStatement("LOG.log($T.DEBUG, $S, DEFINITION_NAME, context.currentStep())",
                        KernelScaffold.LOGGER_LEVEL, KernelScaffold.escapeQuotes(
                                "[{0}] compensating step '" + step.name()
                                        + "' at index {1} — override to implement"))
                .addStatement("return $T.CONTINUE", FLOW_OUTCOME)
                .build();
    }

    private boolean hasCompensation(SagaStepMetadata step) {
        return step.compensation() != null && !step.compensation().isBlank();
    }

    /**
     * Refuses a declared plan version below {@link #INITIAL_VERSION}. The kernel numbers versions
     * from 1 so that 0 can mean "this snapshot predates versioning" on its resume path, and it
     * refuses anything lower both in {@code FlowDefinitionBuilder.version(int)} and in the
     * {@code FlowDefinition} constructor. Emitting the value anyway would compile and then fail
     * at the saga's first {@code initialize()}, far from the declaration. The processor passes
     * {@code @Saga.version} through unchecked, and metadata can also arrive as JSON from outside
     * the processor, so this is the one place every path goes through.
     */
    private void assertSupportedVersion(String entity, String sagaName, int version) {
        if (version < INITIAL_VERSION) {
            throw new IllegalArgumentException(
                    "Saga '" + sagaName + "' on entity '" + entity + "' declares version " + version
                            + ", but kernel plan versions start at " + INITIAL_VERSION
                            + " (FlowDefinition.INITIAL_VERSION, ADR-064), so the emitted flow "
                            + "would be refused at its first initialize(). Declare "
                            + "@Saga(version = n) with n >= " + INITIAL_VERSION
                            + ", or omit it for version " + INITIAL_VERSION + ".");
        }
    }

    /**
     * Asserts that every emitted method name — the per-step action AND
     * (where declared) the compensation — is unique across the generated
     * class. Bare step-name uniqueness is not enough:
     * <ul>
     *   <li>Two raw names that normalise to the same Java identifier
     *       (e.g.\ {@code "my-step"} and {@code "my_step"} both become
     *       {@code myStep}) would emit two methods with the same
     *       signature.</li>
     *   <li>A step literally named {@code "compensate-foo"} (no
     *       compensation) emits {@code compensateFoo()}; a separate step
     *       {@code "foo"} <i>with</i> a compensation also emits
     *       {@code compensateFoo()} — a cross-category clash the
     *       raw-name check would miss.</li>
     * </ul>
     * Both cases produce the same outcome at codegen time: JavaPoet
     * throws a generic duplicate-method error with no saga context.
     * Failing fast here gives the caller the actual offending names.
     */
    private void assertDistinctMethodNames(String entity, List<SagaStepMetadata> steps) {
        List<String> emitted = new ArrayList<>(steps.size() * 2);
        for (SagaStepMetadata step : steps) {
            String action = toMethodName(step.name());
            emitted.add(action);
            if (hasCompensation(step)) {
                emitted.add("compensate" + capitalize(action));
            }
        }
        List<String> duplicates = emitted.stream()
                .collect(Collectors.groupingBy(n -> n, Collectors.counting()))
                .entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Saga step method-name collision on entity '" + entity + "': " + duplicates
                            + ". Each step (and its compensation) must produce a unique "
                            + "Java method identifier after name normalisation.");
        }
    }

    private String toMethodName(String name) {
        if (name == null || name.isBlank()) return "step";
        String[] parts = name.split("[-_]+");
        StringBuilder sb = new StringBuilder(name.length());
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (first) {
                sb.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
                first = false;
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.length() == 0 ? "step" : sb.toString();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public ArtifactType artifactType() {
        return ArtifactType.SAGA;
    }
}
