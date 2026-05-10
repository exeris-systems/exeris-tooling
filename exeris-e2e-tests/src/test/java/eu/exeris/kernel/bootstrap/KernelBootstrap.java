package eu.exeris.kernel.bootstrap;

import eu.exeris.kernel.events.outbox.OutboxSignal;
import eu.exeris.kernel.events.store.EventStore;
import eu.exeris.kernel.flow.SagaEngine;
import eu.exeris.kernel.graph.GraphService;
import eu.exeris.kernel.security.context.KernelContext;
import eu.exeris.kernel.transport.carrier.CarrierConfig;
import eu.exeris.kernel.transport.http3.server.Http3Router;

import javax.sql.DataSource;

/**
 * Test stub for the kernel bootstrap surface. Mirrors the methods generated
 * {@code Application} and {@code CompositionRoot} call against. All accessors
 * return {@code null}; the compile-test gate only checks signatures resolve.
 */
public final class KernelBootstrap {

    private KernelBootstrap() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public void initialize() {
    }

    public void start() {
    }

    public void shutdown() {
    }

    public TransportBootstrap getTransportBootstrap() {
        return null;
    }

    public PersistenceBootstrap getPersistenceBootstrap() {
        return null;
    }

    public EventsBootstrap getEventsBootstrap() {
        return null;
    }

    public FlowBootstrap getFlowBootstrap() {
        return null;
    }

    public GraphBootstrap getGraphBootstrap() {
        return null;
    }

    public static final class Builder {
        public Builder context(KernelContext context) {
            return this;
        }

        public Builder transportConfig(CarrierConfig config) {
            return this;
        }

        public KernelBootstrap build() {
            return new KernelBootstrap();
        }
    }

    public interface TransportBootstrap {
        void setHttp3Router(Http3Router router);
    }

    public interface PersistenceBootstrap {
        DataSource getDataSource();
    }

    public interface EventsBootstrap {
        EventStore getEventStore();

        OutboxSignal getOutboxSignal();
    }

    public interface FlowBootstrap {
        SagaEngine getSagaEngine();
    }

    public interface GraphBootstrap {
        GraphService getGraphService();
    }
}
