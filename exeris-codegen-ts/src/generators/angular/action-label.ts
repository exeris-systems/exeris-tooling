/**
 * The label of an action control. `ActionMetadata.displayName`, written from `@Action(label)`, names
 * the action to the user; an action that declares none is labelled by its method name in title case.
 */

import type { ActionMetadata } from '../../models/domain-model.js';
import { toTitleCase } from './field-render.js';

export function actionLabel(action: ActionMetadata, methodName: string): string {
  const declared = action.displayName?.trim();
  return declared ? declared : toTitleCase(methodName);
}
