package eu.exeris.tooling.codegen.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * Multi-backend code generation abstraction.
 * <p>
 * Enables full "Code Detachment via Regeneration" - the ability to regenerate
 * the entire infrastructure layer for any backend without modifying domain code.
 * <p>
 * Supports 5 backend targets:
 * <ul>
 *   <li>{@link #KERNEL} - Exeris Kernel (native runtime, QUIC/HTTP3, virtual threads)</li>
 *   <li>{@link #SPRING} - Spring Boot 3.x (MVC, Data JPA, Security)</li>
 *   <li>{@link #QUARKUS} - Quarkus 3.x (JAX-RS, Panache, CDI, GraalVM native)</li>
 *   <li>{@link #MICRONAUT} - Micronaut 4.x (AOT DI, reflection-free, GraalVM native)</li>
 *   <li>{@link #VANILLA} - Pure Java SE 21+ (zero frameworks, manual everything)</li>
 * </ul>
 *
 * <h2>Detachment Philosophy</h2>
 * <ul>
 *   <li><b>Sovereignty First</b> - Domain model is the single source of truth</li>
 *   <li><b>Glass Box</b> - All generated code is readable and debuggable</li>
 *   <li><b>No cross-dependencies</b> - SPRING doesn't depend on KERNEL, etc.</li>
 *   <li><b>Polyfills</b> - Missing features are polyfilled per backend</li>
 * </ul>
 *
 * @author Exeris Team
 * @since 0.2.0
 */
public enum PluggableBackend {

    // ═══════════════════════════════════════════════════════════════════════════
    // BACKEND DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Exeris Kernel - Native high-performance runtime.
     * <p>Features: QUIC/HTTP3, Virtual Threads, ScopedValue, Panama FFM, Native Saga Engine.
     */
    KERNEL(
        BackendConfig.builder()
            // Annotations
            .serviceAnnotation("")
            .repositoryAnnotation("")
            .transactionalAnnotation("")
            .controllerAnnotation("")
            .injectAnnotation("")
            // Types
            .pageType("eu.exeris.kernel.persistence.Page")
            .pageableType("eu.exeris.kernel.persistence.Pageable")
            .repositoryBase("eu.exeris.kernel.persistence.Repository")
            .responseType("eu.exeris.kernel.transport.Response")
            // Strategies
            .transportStrategy(TransportStrategy.KERNEL_QUIC)
            .persistenceStrategy(PersistenceStrategy.KERNEL_R2DBC)
            .diStrategy(DIStrategy.SCOPED_VALUE)
            .sagaStrategy(SagaStrategy.KERNEL_NATIVE)
            .securityStrategy(SecurityStrategy.KERNEL_NATIVE)
            .eventSourcingStrategy(EventSourcingStrategy.KERNEL_NATIVE)
            // Capabilities
            .capabilities(EnumSet.of(
                Capability.SAGA,
                Capability.EVENT_SOURCING,
                Capability.VIRTUAL_THREADS,
                Capability.QUIC_HTTP3,
                Capability.ZERO_COPY,
                Capability.AOT_FRIENDLY,
                Capability.GRAALVM_NATIVE
            ))
            .build()
    ),

    /**
     * Spring Boot 3.x - Enterprise Java framework.
     * <p>Features: Spring MVC, Spring Data JPA, Spring Security.
     */
    SPRING(
        BackendConfig.builder()
            // Annotations
            .serviceAnnotation("@org.springframework.stereotype.Service")
            .repositoryAnnotation("@org.springframework.stereotype.Repository")
            .transactionalAnnotation("@org.springframework.transaction.annotation.Transactional")
            .controllerAnnotation("@org.springframework.web.bind.annotation.RestController")
            .injectAnnotation("@org.springframework.beans.factory.annotation.Autowired")
            // Types
            .pageType("org.springframework.data.domain.Page")
            .pageableType("org.springframework.data.domain.Pageable")
            .repositoryBase("org.springframework.data.jpa.repository.JpaRepository")
            .responseType("org.springframework.http.ResponseEntity")
            // Strategies
            .transportStrategy(TransportStrategy.SPRING_MVC)
            .persistenceStrategy(PersistenceStrategy.SPRING_DATA_JPA)
            .diStrategy(DIStrategy.SPRING_DI)
            .sagaStrategy(SagaStrategy.POLYFILL_STATE_MACHINE)
            .securityStrategy(SecurityStrategy.SPRING_SECURITY)
            .eventSourcingStrategy(EventSourcingStrategy.POLYFILL_SPRING_EVENTS)
            // Capabilities
            .capabilities(EnumSet.of(
                Capability.TRANSACTIONS,
                Capability.VIRTUAL_THREADS,
                Capability.REFLECTION_BASED
            ))
            // Polyfills required
            .requiredPolyfills(EnumSet.of(
                Polyfill.SAGA_STATE_MACHINE,
                Polyfill.EVENT_SOURCING_DB
            ))
            .build()
    ),

    /**
     * Quarkus 3.x - GraalVM-optimized, build-time DI.
     * <p>Features: JAX-RS, Panache, CDI, native image support.
     */
    QUARKUS(
        BackendConfig.builder()
            // Annotations
            .serviceAnnotation("@jakarta.enterprise.context.ApplicationScoped")
            .repositoryAnnotation("@jakarta.enterprise.context.ApplicationScoped")
            .transactionalAnnotation("@jakarta.transaction.Transactional")
            .controllerAnnotation("@jakarta.ws.rs.Path")
            .injectAnnotation("@jakarta.inject.Inject")
            // Types
            .pageType("io.quarkus.panache.common.Page")
            .pageableType("io.quarkus.panache.common.Page")
            .repositoryBase("io.quarkus.hibernate.orm.panache.PanacheRepository")
            .responseType("jakarta.ws.rs.core.Response")
            // Strategies
            .transportStrategy(TransportStrategy.JAX_RS)
            .persistenceStrategy(PersistenceStrategy.PANACHE)
            .diStrategy(DIStrategy.CDI)
            .sagaStrategy(SagaStrategy.POLYFILL_SCHEDULER_DB)
            .securityStrategy(SecurityStrategy.QUARKUS_SECURITY)
            .eventSourcingStrategy(EventSourcingStrategy.POLYFILL_KAFKA)
            // Capabilities
            .capabilities(EnumSet.of(
                Capability.TRANSACTIONS,
                Capability.VIRTUAL_THREADS,
                Capability.AOT_FRIENDLY,
                Capability.GRAALVM_NATIVE
            ))
            // Polyfills required
            .requiredPolyfills(EnumSet.of(
                Polyfill.SAGA_SCHEDULER_DB,
                Polyfill.EVENT_SOURCING_KAFKA
            ))
            .build()
    ),

    /**
     * Micronaut 4.x - AOT DI, reflection-free.
     * <p>Features: Micronaut HTTP, Micronaut Data, AOT compilation.
     */
    MICRONAUT(
        BackendConfig.builder()
            // Annotations
            .serviceAnnotation("@jakarta.inject.Singleton")
            .repositoryAnnotation("@jakarta.inject.Singleton")
            .transactionalAnnotation("@io.micronaut.transaction.annotation.Transactional")
            .controllerAnnotation("@io.micronaut.http.annotation.Controller")
            .injectAnnotation("@jakarta.inject.Inject")
            // Types
            .pageType("io.micronaut.data.model.Page")
            .pageableType("io.micronaut.data.model.Pageable")
            .repositoryBase("io.micronaut.data.repository.CrudRepository")
            .responseType("io.micronaut.http.HttpResponse")
            // Strategies
            .transportStrategy(TransportStrategy.MICRONAUT_HTTP)
            .persistenceStrategy(PersistenceStrategy.MICRONAUT_DATA)
            .diStrategy(DIStrategy.MICRONAUT_DI)
            .sagaStrategy(SagaStrategy.POLYFILL_STATE_MACHINE)
            .securityStrategy(SecurityStrategy.MICRONAUT_SECURITY)
            .eventSourcingStrategy(EventSourcingStrategy.POLYFILL_KAFKA)
            // Capabilities
            .capabilities(EnumSet.of(
                Capability.TRANSACTIONS,
                Capability.VIRTUAL_THREADS,
                Capability.AOT_FRIENDLY,
                Capability.GRAALVM_NATIVE
            ))
            // Polyfills required
            .requiredPolyfills(EnumSet.of(
                Polyfill.SAGA_STATE_MACHINE,
                Polyfill.EVENT_SOURCING_KAFKA
            ))
            .build()
    ),

    /**
     * Vanilla Java SE 21+ - Zero frameworks, pure Java.
     * <p>Features: HttpServer, JDBC, manual DI, manual routing.
     */
    VANILLA(
        BackendConfig.builder()
            // Annotations - none, pure Java
            .serviceAnnotation("")
            .repositoryAnnotation("")
            .transactionalAnnotation("")
            .controllerAnnotation("")
            .injectAnnotation("")
            // Types
            .pageType("eu.exeris.vanilla.Page")
            .pageableType("eu.exeris.vanilla.Pageable")
            .repositoryBase("")
            .responseType("com.sun.net.httpserver.HttpExchange")
            // Strategies
            .transportStrategy(TransportStrategy.JAVA_HTTP_SERVER)
            .persistenceStrategy(PersistenceStrategy.MANUAL_JDBC)
            .diStrategy(DIStrategy.MANUAL)
            .sagaStrategy(SagaStrategy.POLYFILL_MANUAL)
            .securityStrategy(SecurityStrategy.MANUAL_FILTER)
            .eventSourcingStrategy(EventSourcingStrategy.POLYFILL_MANUAL)
            // Capabilities
            .capabilities(EnumSet.of(
                Capability.VIRTUAL_THREADS,
                Capability.AOT_FRIENDLY,
                Capability.GRAALVM_NATIVE,
                Capability.ZERO_DEPENDENCIES
            ))
            // Polyfills required
            .requiredPolyfills(EnumSet.of(
                Polyfill.SAGA_MANUAL,
                Polyfill.EVENT_SOURCING_MANUAL,
                Polyfill.SECURITY_MANUAL,
                Polyfill.ROUTING_MANUAL,
                Polyfill.TRANSACTION_MANUAL
            ))
            .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    private final BackendConfig config;

    PluggableBackend(BackendConfig config) {
        this.config = config;
    }

    public BackendConfig config() {
        return config;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVENIENCE ACCESSORS
    // ═══════════════════════════════════════════════════════════════════════════

    public String serviceAnnotation() { return config.serviceAnnotation(); }
    public String repositoryAnnotation() { return config.repositoryAnnotation(); }
    public String transactionalAnnotation() { return config.transactionalAnnotation(); }
    public String controllerAnnotation() { return config.controllerAnnotation(); }
    public String injectAnnotation() { return config.injectAnnotation(); }
    public String pageType() { return config.pageType(); }
    public String pageableType() { return config.pageableType(); }
    public String repositoryBase() { return config.repositoryBase(); }
    public String responseType() { return config.responseType(); }

    public String eventPublisher() {
        return switch (this) {
            case KERNEL -> "eu.exeris.kernel.events.EventPublisher";
            case SPRING -> "org.springframework.context.ApplicationEventPublisher";
            case QUARKUS -> "jakarta.enterprise.event.Event";
            case MICRONAUT -> "io.micronaut.context.event.ApplicationEventPublisher";
            case VANILLA -> "eu.exeris.vanilla.events.EventPublisher";
        };
    }

    // Strategy accessors
    public TransportStrategy transportStrategy() { return config.transportStrategy(); }
    public PersistenceStrategy persistenceStrategy() { return config.persistenceStrategy(); }
    public DIStrategy diStrategy() { return config.diStrategy(); }
    public SagaStrategy sagaStrategy() { return config.sagaStrategy(); }
    public SecurityStrategy securityStrategy() { return config.securityStrategy(); }
    public EventSourcingStrategy eventSourcingStrategy() { return config.eventSourcingStrategy(); }

    // Capability checks
    public boolean supports(Capability capability) {
        return config.capabilities().contains(capability);
    }

    public boolean requiresPolyfill(Polyfill polyfill) {
        return config.requiredPolyfills().contains(polyfill);
    }

    public Set<Capability> capabilities() { return config.capabilities(); }
    public Set<Polyfill> requiredPolyfills() { return config.requiredPolyfills(); }

    // ═══════════════════════════════════════════════════════════════════════════
    // TYPE CHECKS
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean isKernel() { return this == KERNEL; }
    public boolean isSpring() { return this == SPRING; }
    public boolean isQuarkus() { return this == QUARKUS; }
    public boolean isMicronaut() { return this == MICRONAUT; }
    public boolean isVanilla() { return this == VANILLA; }
    public boolean isFrameworkBased() { return this == SPRING || this == QUARKUS || this == MICRONAUT; }
    public boolean isAotFriendly() { return supports(Capability.AOT_FRIENDLY); }
    public boolean isGraalVmNative() { return supports(Capability.GRAALVM_NATIVE); }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANNOTATION GENERATORS
    // ═══════════════════════════════════════════════════════════════════════════

    public String requestMappingAnnotation(String path) {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.RequestMapping(\"" + path + "\")";
            case QUARKUS -> "@jakarta.ws.rs.Path(\"" + path + "\")";
            case MICRONAUT -> "@io.micronaut.http.annotation.Controller(\"" + path + "\")";
            case VANILLA -> "// Route: " + path;
        };
    }

    public String getMappingAnnotation(String path) {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.GetMapping(\"" + path + "\")";
            case QUARKUS -> "@jakarta.ws.rs.GET" + (path.isEmpty() ? "" : " @jakarta.ws.rs.Path(\"" + path + "\")");
            case MICRONAUT -> "@io.micronaut.http.annotation.Get(\"" + path + "\")";
            case VANILLA -> "// GET " + path;
        };
    }

    public String postMappingAnnotation(String path) {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.PostMapping(\"" + path + "\")";
            case QUARKUS -> "@jakarta.ws.rs.POST" + (path.isEmpty() ? "" : " @jakarta.ws.rs.Path(\"" + path + "\")");
            case MICRONAUT -> "@io.micronaut.http.annotation.Post(\"" + path + "\")";
            case VANILLA -> "// POST " + path;
        };
    }

    public String putMappingAnnotation(String path) {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.PutMapping(\"" + path + "\")";
            case QUARKUS -> "@jakarta.ws.rs.PUT" + (path.isEmpty() ? "" : " @jakarta.ws.rs.Path(\"" + path + "\")");
            case MICRONAUT -> "@io.micronaut.http.annotation.Put(\"" + path + "\")";
            case VANILLA -> "// PUT " + path;
        };
    }

    public String deleteMappingAnnotation(String path) {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.DeleteMapping(\"" + path + "\")";
            case QUARKUS -> "@jakarta.ws.rs.DELETE" + (path.isEmpty() ? "" : " @jakarta.ws.rs.Path(\"" + path + "\")");
            case MICRONAUT -> "@io.micronaut.http.annotation.Delete(\"" + path + "\")";
            case VANILLA -> "// DELETE " + path;
        };
    }

    public String pathVariableAnnotation(String name) {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.PathVariable(\"" + name + "\")";
            case QUARKUS -> "@jakarta.ws.rs.PathParam(\"" + name + "\")";
            case MICRONAUT -> "@io.micronaut.http.annotation.PathVariable(\"" + name + "\")";
            case VANILLA -> "/* path: " + name + " */";
        };
    }

    public String pathVariableAnnotation() {
        return pathVariableAnnotation("id");
    }

    public String specificationExecutor() {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "org.springframework.data.jpa.repository.JpaSpecificationExecutor";
            case QUARKUS -> "";
            case MICRONAUT -> "";
            case VANILLA -> "";
        };
    }

    public String requestBodyAnnotation() {
        return switch (this) {
            case KERNEL -> "";
            case SPRING -> "@org.springframework.web.bind.annotation.RequestBody";
            case QUARKUS -> ""; // JAX-RS auto-binds body
            case MICRONAUT -> "@io.micronaut.http.annotation.Body";
            case VANILLA -> "";
        };
    }

    public String validAnnotation() {
        return switch (this) {
            case KERNEL, VANILLA -> "";
            case SPRING, QUARKUS, MICRONAUT -> "@jakarta.validation.Valid";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENUMS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Backend capabilities.
     */
    public enum Capability {
        SAGA,
        EVENT_SOURCING,
        TRANSACTIONS,
        VIRTUAL_THREADS,
        QUIC_HTTP3,
        ZERO_COPY,
        AOT_FRIENDLY,
        GRAALVM_NATIVE,
        REFLECTION_BASED,
        ZERO_DEPENDENCIES
    }

    /**
     * Polyfills for missing features.
     */
    public enum Polyfill {
        SAGA_STATE_MACHINE,
        SAGA_SCHEDULER_DB,
        SAGA_MANUAL,
        EVENT_SOURCING_DB,
        EVENT_SOURCING_KAFKA,
        EVENT_SOURCING_MANUAL,
        SECURITY_MANUAL,
        ROUTING_MANUAL,
        TRANSACTION_MANUAL
    }

    /**
     * Transport layer strategies.
     */
    public enum TransportStrategy {
        KERNEL_QUIC,
        SPRING_MVC,
        JAX_RS,
        MICRONAUT_HTTP,
        JAVA_HTTP_SERVER
    }

    /**
     * Persistence layer strategies.
     */
    public enum PersistenceStrategy {
        KERNEL_R2DBC,
        SPRING_DATA_JPA,
        PANACHE,
        MICRONAUT_DATA,
        MANUAL_JDBC
    }

    /**
     * Dependency injection strategies.
     */
    public enum DIStrategy {
        SCOPED_VALUE,
        SPRING_DI,
        CDI,
        MICRONAUT_DI,
        MANUAL
    }

    /**
     * Saga orchestration strategies.
     */
    public enum SagaStrategy {
        KERNEL_NATIVE,
        POLYFILL_STATE_MACHINE,
        POLYFILL_SCHEDULER_DB,
        POLYFILL_MANUAL
    }

    /**
     * Security strategies.
     */
    public enum SecurityStrategy {
        KERNEL_NATIVE,
        SPRING_SECURITY,
        QUARKUS_SECURITY,
        MICRONAUT_SECURITY,
        MANUAL_FILTER
    }

    /**
     * Event sourcing strategies.
     */
    public enum EventSourcingStrategy {
        KERNEL_NATIVE,
        POLYFILL_SPRING_EVENTS,
        POLYFILL_KAFKA,
        POLYFILL_MANUAL
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BACKEND CONFIG
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Immutable backend configuration.
     */
    public record BackendConfig(
        // Annotations
        String serviceAnnotation,
        String repositoryAnnotation,
        String transactionalAnnotation,
        String controllerAnnotation,
        String injectAnnotation,
        // Types
        String pageType,
        String pageableType,
        String repositoryBase,
        String responseType,
        // Strategies
        TransportStrategy transportStrategy,
        PersistenceStrategy persistenceStrategy,
        DIStrategy diStrategy,
        SagaStrategy sagaStrategy,
        SecurityStrategy securityStrategy,
        EventSourcingStrategy eventSourcingStrategy,
        // Capabilities
        Set<Capability> capabilities,
        Set<Polyfill> requiredPolyfills
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String serviceAnnotation = "";
            private String repositoryAnnotation = "";
            private String transactionalAnnotation = "";
            private String controllerAnnotation = "";
            private String injectAnnotation = "";
            private String pageType = "";
            private String pageableType = "";
            private String repositoryBase = "";
            private String responseType = "";
            private TransportStrategy transportStrategy;
            private PersistenceStrategy persistenceStrategy;
            private DIStrategy diStrategy;
            private SagaStrategy sagaStrategy;
            private SecurityStrategy securityStrategy;
            private EventSourcingStrategy eventSourcingStrategy;
            private Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
            private Set<Polyfill> requiredPolyfills = EnumSet.noneOf(Polyfill.class);

            public Builder serviceAnnotation(String v) { this.serviceAnnotation = v; return this; }
            public Builder repositoryAnnotation(String v) { this.repositoryAnnotation = v; return this; }
            public Builder transactionalAnnotation(String v) { this.transactionalAnnotation = v; return this; }
            public Builder controllerAnnotation(String v) { this.controllerAnnotation = v; return this; }
            public Builder injectAnnotation(String v) { this.injectAnnotation = v; return this; }
            public Builder pageType(String v) { this.pageType = v; return this; }
            public Builder pageableType(String v) { this.pageableType = v; return this; }
            public Builder repositoryBase(String v) { this.repositoryBase = v; return this; }
            public Builder responseType(String v) { this.responseType = v; return this; }
            public Builder transportStrategy(TransportStrategy v) { this.transportStrategy = v; return this; }
            public Builder persistenceStrategy(PersistenceStrategy v) { this.persistenceStrategy = v; return this; }
            public Builder diStrategy(DIStrategy v) { this.diStrategy = v; return this; }
            public Builder sagaStrategy(SagaStrategy v) { this.sagaStrategy = v; return this; }
            public Builder securityStrategy(SecurityStrategy v) { this.securityStrategy = v; return this; }
            public Builder eventSourcingStrategy(EventSourcingStrategy v) { this.eventSourcingStrategy = v; return this; }
            public Builder capabilities(Set<Capability> v) { this.capabilities = v; return this; }
            public Builder requiredPolyfills(Set<Polyfill> v) { this.requiredPolyfills = v; return this; }

            public BackendConfig build() {
                return new BackendConfig(
                    serviceAnnotation, repositoryAnnotation, transactionalAnnotation,
                    controllerAnnotation, injectAnnotation,
                    pageType, pageableType, repositoryBase, responseType,
                    transportStrategy, persistenceStrategy, diStrategy,
                    sagaStrategy, securityStrategy, eventSourcingStrategy,
                    capabilities, requiredPolyfills
                );
            }
        }
    }
}
