package eu.exeris.tooling.codegen.java.kernel;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import eu.exeris.sdk.sourcemodel.ast.DomainMetadata;
import eu.exeris.tooling.codegen.core.generator.GeneratedFile;
import eu.exeris.tooling.codegen.core.generator.KernelArtifactGenerator.ArtifactType;
import eu.exeris.tooling.codegen.java.support.KernelScaffold;

import javax.lang.model.element.Modifier;

/**
 * Emits {@code <Saga>FlowTest} — the generated test for the generated saga skeleton
 * (T2, ADR-058).
 *
 * <h2>What is actually at risk here</h2>
 * <p>Most of the saga skeleton is compile-checked: {@code builder.step(name, this::action,
 * this::compensation)} cannot name a method that does not exist. Three things are not, and they are
 * what this covers.
 *
 * <ul>
 *   <li><b>The transition chain.</b> {@code initialize()} walks the step list twice — once to
 *       register steps, once to lay {@code transition(i, i + 1)} over them — with each walk
 *       deriving its own indices. Two paths that must agree, and neither the compiler nor any
 *       existing test compares them. So the emitted assertion is structural and reads the
 *       <em>recorded</em> steps, not the metadata: n registered steps must produce exactly n−1
 *       transitions, each connecting i to i+1. A chain that skipped, duplicated or overran a step
 *       compiles and schedules; it just runs the wrong flow.</li>
 *   <li><b>Lazy-init idempotence.</b> {@code initialize()} is documented as returning the cached
 *       plan on repeat calls. Nothing enforced it — a second compile is invisible except as
 *       wasted work and a second plan identity.</li>
 *   <li><b>{@code schedule()} using that plan.</b> It is specified to call {@code initialize()} and
 *       hand the scheduler the result. A version that compiled its own plan would behave
 *       identically in every respect except the one that matters under load.</li>
 * </ul>
 *
 * <p>The step <em>names</em> are deliberately not asserted against literals: they come from the
 * same metadata as the emitter, so that check could never fail — the same reasoning that keeps SQL
 * text out of the generated repository test.
 *
 * <h2>The double</h2>
 * <p>{@code RecordingFlow} (emitted once per project by {@link KernelTestSupportGenerator}) plays
 * the engine, plan factory, definition builder, scheduler, plan and context at once. No scheduler
 * thread, no engine lifecycle, no persistence — and the dependency contract stays JUnit 5 + AssertJ.
 *
 * <p>Emitted only for entities that declare a saga, mirroring {@link KernelSagaGenerator}.
 *
 * @implNote Emission is JavaPoet-based (ADR-015).
 * @since 0.7.0
 */
public final class KernelSagaTestGenerator {

    private static final ClassName TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName ASSERTIONS = ClassName.get("org.assertj.core.api", "Assertions");
    private static final ClassName FLOW_OUTCOME =
            ClassName.get("eu.exeris.kernel.spi.flow.model", "FlowOutcome");
    private static final ClassName FLOW_STEP_ACTION =
            ClassName.get("eu.exeris.kernel.spi.flow.model", "FlowStepAction");

    /**
     * @param metadata    the entity whose saga is under test
     * @param basePackage the project base package (the {@code testsupport} package is resolved
     *                    from it, since the flow double is project-wide)
     * @return the emitted test, or {@code null} when the entity declares no saga
     */
    public GeneratedFile generate(DomainMetadata metadata, String basePackage) {
        ClassName flowType = KernelSagaGenerator.sagaFlowType(metadata);
        if (flowType == null) {
            return null;
        }
        String className = flowType.simpleName() + "Test";
        ClassName recordingFlow = ClassName.get(
                KernelTestSupportGenerator.supportPackage(basePackage),
                KernelTestSupportGenerator.RECORDING_FLOW);

        TypeSpec.Builder type = KernelScaffold.publicClass(className)
                .addJavadoc("Generated tests for {@link $T}.\n", flowType)
                .addJavadoc("<p>Covers what the compiler cannot: that the transition chain spans\n")
                .addJavadoc("exactly the steps that were registered, that lazy initialisation is\n")
                .addJavadoc("idempotent, and that {@code schedule} hands the scheduler the plan\n")
                .addJavadoc("{@code initialize} built rather than one of its own.\n")
                .addJavadoc("<p>Requires JUnit 5 and AssertJ on the test classpath, and nothing else.\n")
                .addJavadoc("<p><b>DO NOT EDIT</b> - Regenerate from domain models.\n");

        type.addMethod(chainTest(flowType, recordingFlow));
        type.addMethod(idempotenceTest(flowType, recordingFlow));
        type.addMethod(scheduleTest(flowType, recordingFlow));
        type.addMethod(defaultOutcomeTest(flowType, recordingFlow));

        return new GeneratedFile(flowType.packageName(), className,
                KernelScaffold.render(flowType.packageName(), type.build()), ArtifactType.TEST);
    }

    private MethodSpec chainTest(ClassName flowType, ClassName recordingFlow) {
        return test("initializeChainsExactlyTheRegisteredSteps")
                .addJavadoc("The step registration and the transition layout are two separate walks\n")
                .addJavadoc("over the same list, each deriving its own indices. This is the only\n")
                .addJavadoc("place they are compared — and it compares the <em>recorded</em> steps,\n")
                .addJavadoc("so a chain built from a different list than the one registered fails.\n")
                .addStatement("$T flow = new $T()", recordingFlow, recordingFlow)
                .addStatement("new $T(flow).initialize()", flowType)
                .addCode("\n")
                .addStatement("$T.assertThat(flow.steps).isNotEmpty()", ASSERTIONS)
                .addStatement("$T.assertThat(flow.transitions).hasSize(flow.steps.size() - 1)",
                        ASSERTIONS)
                .beginControlFlow("for (int i = 0; i < flow.steps.size() - 1; i++)")
                .addStatement("$T.assertThat(flow.transitions.get(i)).isEqualTo(i + $S + (i + 1))",
                        ASSERTIONS, "->")
                .endControlFlow()
                .addStatement("$T.assertThat(flow.actions).doesNotContainNull()", ASSERTIONS)
                .build();
    }

    private MethodSpec idempotenceTest(ClassName flowType, ClassName recordingFlow) {
        return test("initializeIsIdempotent")
                .addStatement("$T flow = new $T()", recordingFlow, recordingFlow)
                .addStatement("$T saga = new $T(flow)", flowType, flowType)
                .addStatement("$T.assertThat(saga.initialize()).isSameAs(saga.initialize())",
                        ASSERTIONS)
                .addStatement("$T.assertThat(flow.compiled).isEqualTo(1)", ASSERTIONS)
                .build();
    }

    private MethodSpec scheduleTest(ClassName flowType, ClassName recordingFlow) {
        return test("scheduleHandsTheSchedulerTheInitializedPlan")
                .addJavadoc("A schedule that compiled its own plan would look identical from the\n")
                .addJavadoc("outside — except that every scheduled instance would recompile.\n")
                .addStatement("$T flow = new $T()", recordingFlow, recordingFlow)
                .addStatement("$T saga = new $T(flow)", flowType, flowType)
                .addStatement("saga.schedule(flow)")
                .addStatement("$T.assertThat(flow.scheduled).isSameAs(saga.initialize())", ASSERTIONS)
                .addStatement("$T.assertThat(flow.compiled).isEqualTo(1)", ASSERTIONS)
                .build();
    }

    private MethodSpec defaultOutcomeTest(ClassName flowType, ClassName recordingFlow) {
        return test("everyStepDefaultsToContinueSoTheSkeletonIsSchedulable")
                .addJavadoc("The emitted skeleton is meant to be runnable before a single step is\n")
                .addJavadoc("overridden; a stub that returned anything else would abort the flow.\n")
                .addStatement("$T flow = new $T()", recordingFlow, recordingFlow)
                .addStatement("new $T(flow).initialize()", flowType)
                .beginControlFlow("for ($T action : flow.actions)", FLOW_STEP_ACTION)
                .addStatement("$T.assertThat(action.execute(flow)).isEqualTo($T.CONTINUE)",
                        ASSERTIONS, FLOW_OUTCOME)
                .endControlFlow()
                .beginControlFlow("for ($T compensation : flow.compensations)", FLOW_STEP_ACTION)
                .beginControlFlow("if (compensation != null)")
                .addStatement("$T.assertThat(compensation.execute(flow)).isEqualTo($T.CONTINUE)",
                        ASSERTIONS, FLOW_OUTCOME)
                .endControlFlow()
                .endControlFlow()
                .build();
    }

    private static MethodSpec.Builder test(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(TEST)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID);
    }
}
