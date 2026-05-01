package eu.exeris.tooling.codegen.java.service;

import eu.exeris.tooling.codegen.core.CodegenContext;
import eu.exeris.tooling.codegen.core.PluggableBackend;
import eu.exeris.sdk.sourcemodel.ast.*;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates service implementation classes with framework-specific annotations.
 * Supports:
 * - Traditional CRUD operations
 * - Event Sourcing with aggregate reconstruction
 * - Saga orchestration steps
 * - Async action execution
 * - Soft delete handling
 * - Multi-tenancy support
 *
 * @author Exeris Team
 * @since 0.1.0
 */
public final class ServiceImplementationGenerator {

    private final PluggableBackend backend;

    public ServiceImplementationGenerator() {
        this(PluggableBackend.SPRING);
    }

    public ServiceImplementationGenerator(PluggableBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend cannot be null");
    }

    public String generate(DomainMetadata metadata) {
        return generate(metadata, null);
    }

    public String generate(DomainMetadata metadata, CodegenContext ctx) {
        String entity = metadata.entityName();
        String pkg = buildServicePackage(metadata.packageName());
        String implName = entity + "ServiceImpl";
        String ifaceName = entity + "Service";
        String repoName = entity + "Repository";
        String now = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now());

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append(buildImports(metadata));
        sb.append(buildHeader(implName, metadata.fullyQualifiedName(), now));
        sb.append(buildClassAnnotations(metadata));
        sb.append("public class ").append(implName).append(" implements ").append(ifaceName).append(" {\n\n");
        sb.append(buildFields(metadata, repoName));
        sb.append(buildConstructor(metadata, implName, repoName));

        // Choose implementation strategy based on domain configuration
        if (metadata.isEventSourced()) {
            sb.append(buildEventSourcedImplementation(metadata, entity));
        } else {
            sb.append(buildCrudImpl(metadata, entity));
        }

        sb.append(buildActionImpls(metadata, entity));

        // Add saga step methods if this is a saga
        if (metadata.isSaga() && metadata.sagaMetadata().hasSteps()) {
            sb.append(buildSagaStepMethods(metadata));
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String buildServicePackage(String domainPackage) {
        int lastDot = domainPackage.lastIndexOf('.');
        return lastDot <= 0 ? "service" : domainPackage.substring(0, lastDot) + ".service";
    }

    private String buildImports(DomainMetadata metadata) {
        String entityFqn = metadata.fullyQualifiedName();
        String basePkg = buildBasePackage(metadata.packageName());

        StringBuilder sb = new StringBuilder();
        sb.append("import ").append(entityFqn).append(";\n");
        sb.append("import java.util.Optional;\n");
        sb.append("import java.util.UUID;\n");
        sb.append("import java.util.List;\n");
        sb.append("import ").append(basePkg).append(".repository.").append(metadata.entityName()).append("Repository;\n");
        sb.append("import ").append(basePkg).append(".service.").append(metadata.entityName()).append("Service;\n");

        // Pagination
        if (backend == PluggableBackend.SPRING) {
            sb.append("import org.springframework.data.domain.Page;\n");
            sb.append("import org.springframework.data.domain.Pageable;\n");
            sb.append("import org.springframework.transaction.annotation.Transactional;\n");
        }

        // Event sourcing imports
        if (metadata.isEventSourced()) {
            sb.append("import eu.exeris.kernel.eventsourcing.EventStore;\n");
            sb.append("import eu.exeris.kernel.eventsourcing.AggregateRoot;\n");
            sb.append("import eu.exeris.kernel.eventsourcing.DomainEvent;\n");
        }

        // Saga imports
        if (metadata.isSaga()) {
            sb.append("import eu.exeris.kernel.saga.SagaOrchestrator;\n");
            sb.append("import eu.exeris.kernel.saga.SagaState;\n");
            sb.append("import java.util.concurrent.CompletableFuture;\n");
        }

        // Async actions
        if (hasAsyncActions(metadata)) {
            sb.append("import java.util.concurrent.CompletableFuture;\n");
            sb.append("import org.springframework.scheduling.annotation.Async;\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    private boolean hasAsyncActions(DomainMetadata metadata) {
        return metadata.hasActions() && metadata.actions().stream().anyMatch(ActionMetadata::async);
    }

    private String buildBasePackage(String domainPackage) {
        int lastDot = domainPackage.lastIndexOf('.');
        return lastDot <= 0 ? domainPackage : domainPackage.substring(0, lastDot);
    }

    private String buildHeader(String implName, String sourceFqn, String timestamp) {
        return """
                /**
                 * %s - generated service implementation.
                 * Source: %s
                 * Generated: %s
                 */
                """.formatted(implName, sourceFqn, timestamp);
    }

    private String buildClassAnnotations(DomainMetadata metadata) {
        StringBuilder sb = new StringBuilder();
        String annotation = backend.serviceAnnotation();
        if (!annotation.isBlank()) {
            sb.append(annotation).append("\n");
        }

        // Add transactional by default for non-event-sourced
        if (!metadata.isEventSourced() && backend == PluggableBackend.SPRING) {
            sb.append("@Transactional\n");
        }

        return sb.toString();
    }

    private String buildFields(DomainMetadata metadata, String repoName) {
        StringBuilder sb = new StringBuilder();
        sb.append("    private final ").append(repoName).append(" repository;\n");

        if (metadata.isEventSourced()) {
            sb.append("    private final EventStore eventStore;\n");
        }

        if (metadata.isSaga()) {
            sb.append("    private final SagaOrchestrator sagaOrchestrator;\n");
        }

        // Event publisher for domain events
        if (metadata.hasEvents()) {
            sb.append("    private final ").append(backend.eventPublisher().substring(backend.eventPublisher().lastIndexOf('.') + 1))
                    .append(" eventPublisher;\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    private String buildConstructor(DomainMetadata metadata, String implName, String repoName) {
        StringBuilder sb = new StringBuilder();
        sb.append("    public ").append(implName).append("(\n");
        sb.append("            ").append(repoName).append(" repository");

        if (metadata.isEventSourced()) {
            sb.append(",\n            EventStore eventStore");
        }
        if (metadata.isSaga()) {
            sb.append(",\n            SagaOrchestrator sagaOrchestrator");
        }
        if (metadata.hasEvents()) {
            String publisher = backend.eventPublisher().substring(backend.eventPublisher().lastIndexOf('.') + 1);
            sb.append(",\n            ").append(publisher).append(" eventPublisher");
        }

        sb.append(") {\n");
        sb.append("        this.repository = repository;\n");
        if (metadata.isEventSourced()) {
            sb.append("        this.eventStore = eventStore;\n");
        }
        if (metadata.isSaga()) {
            sb.append("        this.sagaOrchestrator = sagaOrchestrator;\n");
        }
        if (metadata.hasEvents()) {
            sb.append("        this.eventPublisher = eventPublisher;\n");
        }
        sb.append("    }\n\n");

        return sb.toString();
    }

    private String buildCrudImpl(DomainMetadata metadata, String entity) {
        StringBuilder sb = new StringBuilder();
        boolean softDelete = metadata.softDelete();

        // findById
        sb.append("""
                    @Override
                    public Optional<%s> findById(UUID id) {
                        return repository.findById(id);
                    }
                
                """.formatted(entity));

        // getById
        sb.append("""
                    @Override
                    public %s getById(UUID id) {
                        return repository.findById(id)
                            .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("%s not found: " + id));
                    }
                
                """.formatted(entity, entity));

        // findAll with pagination
        sb.append("""
                    @Override
                    public Page<%s> findAll(Pageable pageable) {
                        return repository.findAll(pageable);
                    }
                
                """.formatted(entity));

        // search
        sb.append("""
                    @Override
                    public Page<%s> search(String query, Pageable pageable) {
                        if (query == null || query.isBlank()) {
                            return repository.findAll(pageable);
                        }
                        return repository.search(query, pageable);
                    }
                
                """.formatted(entity));

        // save
        sb.append("""
                    @Override
                    public %s save(%s entity) {
                        var saved = repository.save(entity);
                """.formatted(entity, entity));

        // Publish create event if configured
        if (metadata.hasEvents()) {
            sb.append("""
                            // Publish domain event
                            eventPublisher.publishEvent(new %sCreatedEvent(saved.getId()));
                    """.formatted(entity));
        }

        sb.append("""
                        return saved;
                    }
                
                """);

        // update
        sb.append("""
                    @Override
                    public %s update(UUID id, %s entity) {
                        var existing = getById(id);
                        // Apply updates to existing entity
                        var updated = repository.save(entity);
                """.formatted(entity, entity));

        if (metadata.hasEvents()) {
            sb.append("""
                            // Publish domain event
                            eventPublisher.publishEvent(new %sUpdatedEvent(updated.getId()));
                    """.formatted(entity));
        }

        sb.append("""
                        return updated;
                    }
                
                """);

        // delete (or soft delete)
        if (softDelete) {
            sb.append("""
                        @Override
                        public %s softDelete(UUID id) {
                            var entity = getById(id);
                            entity.setDeleted(true);
                            entity.setDeletedAt(java.time.Instant.now());
                            var deleted = repository.save(entity);
                    """.formatted(entity));

            if (metadata.hasEvents()) {
                sb.append("""
                                // Publish domain event
                                eventPublisher.publishEvent(new %sDeletedEvent(deleted.getId()));
                        """.formatted(entity));
            }

            sb.append("""
                            return deleted;
                        }
                    
                    """);
        }

        sb.append("""
                    @Override
                    public void delete(UUID id) {
                """);

        if (softDelete) {
            sb.append("        softDelete(id);\n");
        } else {
            sb.append("""
                            var entity = getById(id);
                            repository.deleteById(id);
                    """);
            if (metadata.hasEvents()) {
                sb.append("""
                                // Publish domain event
                                eventPublisher.publishEvent(new %sDeletedEvent(id));
                        """.formatted(entity));
            }
        }

        sb.append("    }\n\n");

        return sb.toString();
    }

    private String buildEventSourcedImplementation(DomainMetadata metadata, String entity) {
        EventSourcedMetadata esConfig = metadata.eventSourced();
        String aggregateType = esConfig.aggregateType() != null ? esConfig.aggregateType() : entity;

        return """
                    // ==================== Event Sourced Implementation ====================
                
                    @Override
                    public Optional<%s> findById(UUID id) {
                        return reconstructAggregate(id);
                    }
                
                    @Override
                    public %s getById(UUID id) {
                        return reconstructAggregate(id)
                            .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("%s not found: " + id));
                    }
                
                    @Override
                    public Page<%s> findAll(Pageable pageable) {
                        // For event-sourced aggregates, we use a read model/projection
                        return repository.findAll(pageable);
                    }
                
                    @Override
                    public Page<%s> search(String query, Pageable pageable) {
                        // Search uses read model
                        return repository.search(query, pageable);
                    }
                
                    @Override
                    public %s save(%s entity) {
                        // For event sourcing, we append events rather than saving state directly
                        var events = entity.getUncommittedChanges();
                        eventStore.append(entity.getId(), events, entity.getVersion());
                
                        // Clear uncommitted changes
                        entity.markChangesAsCommitted();
                
                        // Publish events for projections
                        events.forEach(eventPublisher::publishEvent);
                
                        return entity;
                    }
                
                    @Override
                    public %s update(UUID id, %s entity) {
                        return save(entity);
                    }
                
                    @Override
                    public void delete(UUID id) {
                        var aggregate = getById(id);
                        aggregate.markAsDeleted();
                        save(aggregate);
                    }
                
                    /**
                     * Reconstructs aggregate from event stream.
                     */
                    private Optional<%s> reconstructAggregate(UUID id) {
                        var events = eventStore.getEvents(id);
                        if (events.isEmpty()) {
                            return Optional.empty();
                        }
                
                        var aggregate = new %s();
                        aggregate.replayEvents(events);
                        return Optional.of(aggregate);
                    }
                
                    /**
                     * Loads aggregate with snapshot optimization.
                     */
                    private %s loadAggregateWithSnapshot(UUID id) {
                        var snapshot = eventStore.getLatestSnapshot(id, %s.class);
                        var fromVersion = snapshot.map(s -> s.getVersion() + 1).orElse(0L);
                        var events = eventStore.getEvents(id, fromVersion);
                
                        var aggregate = snapshot.orElseGet(%s::new);
                        aggregate.replayEvents(events);
                
                        // Create snapshot if needed
                        if (events.size() >= %d) {
                            eventStore.saveSnapshot(id, aggregate);
                        }
                
                        return aggregate;
                    }
                
                """.formatted(
                entity, entity, entity, entity, entity,
                entity, entity, entity, entity,
                entity, entity, entity, entity, entity,
                esConfig.snapshotEvery()
        );
    }

    private String buildActionImpls(DomainMetadata metadata, String entity) {
        if (!metadata.hasActions()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("    // ==================== Custom Actions ====================\n\n");

        for (ActionMetadata action : metadata.actions()) {
            sb.append(buildActionImpl(metadata, entity, action));
        }

        return sb.toString();
    }

    private String buildActionImpl(DomainMetadata metadata, String entity, ActionMetadata action) {
        StringBuilder sb = new StringBuilder();
        String method = GeneratorUtils.toCamel(action.name());
        boolean async = action.async();

        if (async) {
            // Async version
            sb.append("    @Override\n");
            if (backend == PluggableBackend.SPRING) {
                sb.append("    @Async\n");
            }
            sb.append("    public CompletableFuture<").append(entity).append("> ").append(method).append("Async(UUID id");
            if (action.hasParams()) {
                sb.append(", ").append(entity).append(GeneratorUtils.toPascal(action.name())).append("Request request");
            }
            sb.append(") {\n");
            sb.append("        return CompletableFuture.supplyAsync(() -> ").append(method).append("(id");
            if (action.hasParams()) {
                sb.append(", request");
            }
            sb.append("));\n");
            sb.append("    }\n\n");
        }

        // Sync version
        sb.append("    @Override\n");
        sb.append("    public ").append(entity).append(" ").append(method).append("(UUID id");
        if (action.hasParams()) {
            sb.append(", ").append(entity).append(GeneratorUtils.toPascal(action.name())).append("Request request");
        }
        sb.append(") {\n");

        sb.append("        var aggregate = getById(id);\n");
        sb.append("        aggregate = aggregate.").append(method).append("(");
        if (action.hasParams()) {
            sb.append("request");
        }
        sb.append(");\n");

        if (metadata.isEventSourced()) {
            sb.append("        return save(aggregate);\n");
        } else {
            sb.append("        var result = repository.save(aggregate);\n");

            // Publish action event if configured
            if (action.hasProducedEvents()) {
                for (String event : action.producesEvents()) {
                    sb.append("        eventPublisher.publishEvent(new ").append(event).append("(result.getId()));\n");
                }
            }

            sb.append("        return result;\n");
        }

        sb.append("    }\n\n");

        return sb.toString();
    }

    private String buildSagaStepMethods(DomainMetadata metadata) {
        SagaMetadata saga = metadata.sagaMetadata();
        StringBuilder sb = new StringBuilder();

        sb.append("    // ==================== Saga Steps ====================\n\n");

        for (SagaStepMetadata step : saga.steps()) {
            sb.append(buildSagaStepMethod(metadata, step));
            if (step.hasCompensation()) {
                sb.append(buildSagaCompensationMethod(metadata, step));
            }
        }

        return sb.toString();
    }

    private String buildSagaStepMethod(DomainMetadata metadata, SagaStepMetadata step) {
        String method = GeneratorUtils.toCamel(step.name());

        return """
                    /**
                     * Saga step: %s
                     * Order: %d
                     * %s
                     */
                    public CompletableFuture<SagaState> %s(SagaState state) {
                        return sagaOrchestrator.executeStep("%s", state, ctx -> {
                            // Execute step logic
                            // Command: %s
                            return ctx.complete();
                        });
                    }
                
                """.formatted(
                step.name(),
                step.order(),
                step.description() != null ? step.description() : "",
                method,
                step.name(),
                step.command() != null ? step.command() : "N/A"
        );
    }

    private String buildSagaCompensationMethod(DomainMetadata metadata, SagaStepMetadata step) {
        String method = "compensate" + GeneratorUtils.toPascal(step.name());

        return """
                    /**
                     * Compensation for: %s
                     */
                    public CompletableFuture<SagaState> %s(SagaState state) {
                        return sagaOrchestrator.compensateStep("%s", state, ctx -> {
                            // Execute compensation: %s
                            return ctx.complete();
                        });
                    }
                
                """.formatted(step.name(), method, step.name(), step.compensation());
    }
}
