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

    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
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

    @Override
    public GeneratedFile generate(DomainMetadata metadata) {
        if (!metadata.isSaga() || metadata.sagaMetadata() == null) {
            return null;
        }
        SagaMetadata saga = metadata.sagaMetadata();
        List<SagaStepMetadata> steps = stepsOrPlaceholder(saga);

        assertDistinctMethodNames(metadata.entityName(), steps);

        String packageName = metadata.packageName().replace(".domain", ".saga");
        String entity = metadata.entityName();
        String sagaName = saga.name() != null && !saga.name().isBlank()
                ? saga.name() : entity + "Saga";
        String className = sagaName.endsWith("Flow") ? sagaName : sagaName + "Flow";
        ClassName selfType = ClassName.get(packageName, className);

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
                .addField(FieldSpec.builder(SLF4J_LOGGER, "LOG",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, selfType)
                        .build())
                .addField(FieldSpec.builder(String.class, "DEFINITION_NAME",
                                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                        .initializer("$S", sagaName)
                        .build())
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

        builder.addMethod(buildInitialize(steps));
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

    private MethodSpec buildInitialize(List<SagaStepMetadata> steps) {
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
                .addStatement("LOG.debug($S, DEFINITION_NAME, $S, context.currentStep())",
                        "[{}] step '{}' at index {} — override to implement", step.name())
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
                .addStatement("LOG.debug($S, DEFINITION_NAME, $S, context.currentStep())",
                        "[{}] compensating step '{}' at index {} — override to implement", step.name())
                .addStatement("return $T.CONTINUE", FLOW_OUTCOME)
                .build();
    }

    private List<SagaStepMetadata> stepsOrPlaceholder(SagaMetadata saga) {
        if (saga.steps() != null && !saga.steps().isEmpty()) {
            return saga.steps();
        }
        // FlowDefinitionBuilder requires at least one step; if the metadata
        // declares none, emit a single placeholder so the generated class is
        // at least compilable and schedulable.
        return List.of(SagaStepMetadata.simple("process", 0, null));
    }

    private boolean hasCompensation(SagaStepMetadata step) {
        return step.compensation() != null && !step.compensation().isBlank();
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
