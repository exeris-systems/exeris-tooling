package eu.exeris.kernel.flow;

import eu.exeris.kernel.flow.model.SagaDefinition;
import eu.exeris.kernel.flow.model.SagaSnapshot;
import eu.exeris.kernel.flow.model.StepResult;

import java.time.Duration;
import java.util.function.Function;

/**
 * Test stub for the saga DSL. Generated saga registrars chain
 * {@link #create(String)} → {@code timeout/maxRetries/step(...).action(...)
 * .compensation(...).timeout(...).retries(...).and()...} → {@link #build()}.
 */
public final class SagaBuilder {

    private SagaBuilder() {
    }

    public static SagaBuilder create(String name) {
        return new SagaBuilder();
    }

    public SagaBuilder timeout(Duration v) {
        return this;
    }

    public SagaBuilder maxRetries(int v) {
        return this;
    }

    public StepBuilder step(String name) {
        return new StepBuilder(this);
    }

    public SagaDefinition build() {
        return new SagaDefinition();
    }

    public static final class StepBuilder {
        private final SagaBuilder parent;

        StepBuilder(SagaBuilder parent) {
            this.parent = parent;
        }

        public StepBuilder action(Function<SagaSnapshot, StepResult> v) {
            return this;
        }

        public StepBuilder compensation(Function<SagaSnapshot, StepResult> v) {
            return this;
        }

        public StepBuilder timeout(Duration v) {
            return this;
        }

        public StepBuilder retries(int v) {
            return this;
        }

        public SagaBuilder and() {
            return parent;
        }
    }
}
