package eu.exeris.tooling.initializr.model;

/**
 * Supported Exeris project topologies.
 */
public enum ProjectTopology {
    /** Full-stack monorepo: /backend (Java 25, Exeris Kernel) + /frontend (Angular 22, @exeris/codegen-ts). */
    E2E,
    /** Backend microservice/application only with Exeris Kernel SDK, domain models, and Flyway migrations. */
    BACKEND_ONLY,
    /** Frontend application only targeting Exeris API endpoints with @exeris/codegen-ts tooling. */
    FRONTEND_ONLY
}
