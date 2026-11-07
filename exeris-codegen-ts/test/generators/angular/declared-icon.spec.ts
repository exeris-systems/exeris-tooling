import { describe, expect, it } from 'vitest';
import { declaredIconSpan, isMaterialIconName } from '../../../src/generators/angular/declared-icon.js';

describe('declared icons', () => {
  it.each(['badge', 'shopping_cart', 'counter_1', '3d_rotation'])('%s is a Material icon name', (name) => {
    expect(isMaterialIconName(name)).toBe(true);
  });

  it.each(['📋', 'Badge', 'shopping-cart', 'two words', '', '{{x}}'])('%j is not a Material icon name', (text) => {
    expect(isMaterialIconName(text)).toBe(false);
  });

  it('renders a name through the font class, trimmed and hidden from assistive technology', () => {
    expect(declaredIconSpan(' badge ', 'mr-2')).toBe(
      '<span class="material-symbols-outlined mr-2" aria-hidden="true">badge</span>',
    );
  });

  it('renders other text as itself, template metacharacters dropped', () => {
    expect(declaredIconSpan('📋', 'mr-2')).toBe('<span class="mr-2">📋</span>');
    expect(declaredIconSpan('<b>{{x}}</b>', 'mr-2')).toBe('<span class="mr-2">bx/b</span>');
  });
});
