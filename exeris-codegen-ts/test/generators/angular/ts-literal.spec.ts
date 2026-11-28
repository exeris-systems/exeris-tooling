import { describe, expect, it } from 'vitest';

import { attributeText, testIdSegment, tsTemplateText } from '../../../src/generators/angular/ts-literal.js';

describe('ts-literal', () => {
  it('template text drops the characters that close the literal, open an interpolation or a tag', () => {
    expect(tsTemplateText('a`b${c}<d>\\e')).toBe('abcde');
  });

  it('attribute text encodes a double quote, so the value stays inside its attribute', () => {
    expect(attributeText('Say "hi" <b>')).toBe('Say &quot;hi&quot; b');
  });

  it('a test-id segment keeps letters, digits, underscore and hyphen', () => {
    expect(testIdSegment('Open "orders"/2')).toBe('Open--orders--2');
  });
});
