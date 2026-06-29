/**
 * Angular Generators - Public Exports
 *
 * @author Exeris Team
 * @since 0.4.0
 */

// Core generators
export { generateService } from './service-gen.js';
export { generateStreamClient, StreamClientGenerator } from './stream-client-gen.js';
export { generateActionStreamClient, ActionStreamClientGenerator } from './action-stream-client-gen.js';
export { generateForm } from './form-gen.js';
export { generateList } from './list-gen.js';
export { generateStore, StoreGenerator } from './store-gen.js';
export { generateSaga, SagaGenerator } from './saga-gen.js';
export { generateEventHandler, EventHandlerGenerator } from './event-gen.js';
export { generateAppStructure } from './app-structure-gen.js';

// New generators (v0.4.0)
export { generateDetail, DetailGenerator } from './detail-gen.js';
export { generateGuard, GuardGenerator } from './guard-gen.js';

// Presentation IR — @View page emitter (v0.8.0, RFC-2026-06-28)
export { generateView, generateViewRoute } from './view-gen.js';

export type { GeneratedFile } from './service-gen.js';
