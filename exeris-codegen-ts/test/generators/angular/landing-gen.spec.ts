/**
 * Coverage for src/generators/angular/landing-gen.ts —
 * LandingPageGenerator emits a single hardcoded-entity pair (component
 * .ts + template .html) for the marketing entity `ExerisPitchDeck`.
 * It is registered under the DETAIL artifact type alongside
 * DetailGenerator (see core/generator-registry spec).
 *
 * Contracts pinned:
 *   - CodeGenerator metadata: name / artifactType=DETAIL /
 *     supportedBackends=[] / priority=20.
 *   - generate(): hardcoded entity-name filter — returns null for any
 *     entityName !== 'ExerisPitchDeck', returns a typed GeneratedFile
 *     for the match. Emit path is fixed and overwritable=true.
 *   - generate() emits one `name = "value";` line per field, with
 *     defaultValue read from the FLAT FieldMetadata.defaultValue
 *     (not the old `f.properties['defaultValue']` shape that
 *     pre-stage-4e shipped against a non-existent model).
 *   - Escaping in the emitted TS literal: `"` → `\"`, `\n` → `\n`
 *     (the literal 2-char escape). Pinned with both characters at
 *     once + a control-fixture confirming the substitution doesn't
 *     touch other characters.
 *   - generateAggregate(): empty array when no deck entity present;
 *     otherwise one template GeneratedFile.
 *   - Template renders one `evidence-item` block PER field whose
 *     `f.type === 'IMAGE_URL'`, and ZERO blocks for any other
 *     field type. The text-field bucket is intentionally unused
 *     in the emitted HTML today — pinned by asserting that a
 *     text-field's name does NOT appear in the rendered template.
 *   - Evidence label sourcing: prefers FieldMetadata.displayName,
 *     falls back to FieldMetadata.name (the earlier `f.label` access
 *     emitted literal "undefined" because no `label` key exists on
 *     FieldMetadata).
 */

import { describe, expect, it } from 'vitest';
import { LandingPageGenerator } from '../../../src/generators/angular/landing-gen.js';
import {
  DomainMetadataSchema,
  FieldMetadataSchema,
  type DomainMetadata,
  type FieldMetadata,
} from '../../../src/models/domain-model.js';
import {
  createGeneratorContext,
  type GeneratorContext,
} from '../../../src/core/generator-registry.js';

const CTX: GeneratorContext = createGeneratorContext({});

function domain(overrides: Partial<DomainMetadata> & { entityName: string }): DomainMetadata {
  return DomainMetadataSchema.parse({ packageName: 'com.shop', ...overrides });
}

function field(overrides: Partial<FieldMetadata> & { name: string; type: string }): FieldMetadata {
  return FieldMetadataSchema.parse(overrides);
}

// ---------- CodeGenerator metadata ----------

describe('LandingPageGenerator — CodeGenerator metadata', () => {
  const gen = new LandingPageGenerator();

  it('declares name / artifactType=DETAIL / supportedBackends=[] / priority=20', () => {
    expect(gen.name).toBe('LandingPageGenerator');
    expect(gen.artifactType).toBe('DETAIL');
    expect(gen.supportedBackends).toEqual([]);
    expect(gen.priority).toBe(20);
  });
});

// ---------- generate (entity-name filter) ----------

describe('LandingPageGenerator.generate — entity-name filter', () => {
  const gen = new LandingPageGenerator();

  it('returns null for any entity other than ExerisPitchDeck', () => {
    expect(gen.generate(domain({ entityName: 'Order' }), CTX)).toBeNull();
    expect(gen.generate(domain({ entityName: 'Product' }), CTX)).toBeNull();
    // Case-sensitive: lowercased / suffixed names do NOT match.
    expect(gen.generate(domain({ entityName: 'exerisPitchDeck' }), CTX)).toBeNull();
    expect(gen.generate(domain({ entityName: 'ExerisPitchDecks' }), CTX)).toBeNull();
  });

  it('returns a typed GeneratedFile for ExerisPitchDeck', () => {
    const file = gen.generate(domain({ entityName: 'ExerisPitchDeck' }), CTX);
    expect(file).not.toBeNull();
    expect(file!.path).toBe('src/app/features/pitch-deck/pitch-deck.component.ts');
    expect(file!.artifactType).toBe('DETAIL');
    expect(file!.overwritable).toBe(true);
  });
});

// ---------- generate (emitted component contents) ----------

describe('LandingPageGenerator.generate — component emission', () => {
  const gen = new LandingPageGenerator();

  it('emits @Component with the app-pitch-deck selector and a standalone import set', () => {
    const file = gen.generate(domain({ entityName: 'ExerisPitchDeck' }), CTX)!;
    expect(file.content).toContain("selector: 'app-pitch-deck'");
    expect(file.content).toContain('standalone: true');
    expect(file.content).toContain('imports: [CommonModule]');
    expect(file.content).toContain("templateUrl: './pitch-deck.component.html'");
    expect(file.content).toContain('export class PitchDeckComponent {');
  });

  it('emits one `name = "value";` line per field, sourcing value from the FLAT FieldMetadata.defaultValue', () => {
    const file = gen.generate(domain({
      entityName: 'ExerisPitchDeck',
      fields: [
        field({ name: 'title', type: 'java.lang.String', defaultValue: 'Exeris' }),
        field({ name: 'subTitle', type: 'java.lang.String', defaultValue: 'Native Foundation' }),
      ],
    }), CTX)!;
    expect(file.content).toContain('title = "Exeris";');
    expect(file.content).toContain('subTitle = "Native Foundation";');
  });

  it('emits an empty-string value when FieldMetadata.defaultValue is absent (Zod-default undefined)', () => {
    const file = gen.generate(domain({
      entityName: 'ExerisPitchDeck',
      fields: [field({ name: 'contactEmail', type: 'java.lang.String' })],
    }), CTX)!;
    expect(file.content).toContain('contactEmail = "";');
  });

  it('escapes embedded double quotes and newlines in defaultValue', () => {
    // Pin BOTH escapes at once + a control character that should
    // pass through unchanged. The earlier `'\"'` replacement was a
    // no-op (replaced `"` with itself); the spec locks the corrected
    // `'\\"'` substitution.
    const file = gen.generate(domain({
      entityName: 'ExerisPitchDeck',
      fields: [
        field({ name: 'quote', type: 'java.lang.String', defaultValue: 'a "b" c\nd\te' }),
      ],
    }), CTX)!;
    // The emitted TS source contains the 2-char escape sequence \"
    // (a backslash followed by a quote) and \n (backslash + n) —
    // assert the literal output substring.
    expect(file.content).toContain('quote = "a \\"b\\" c\\nd\te";');
    // Sanity: the un-escaped `"b"` would terminate the string
    // literal and break the emit. Make sure no occurrence shows up
    // un-escaped in the property block.
    expect(file.content).not.toContain('a "b" c');
  });

  it('emits no property block when the entity has no fields', () => {
    const file = gen.generate(domain({ entityName: 'ExerisPitchDeck' }), CTX)!;
    expect(file.content).toContain('export class PitchDeckComponent {\n\n}');
  });
});

// ---------- generateAggregate (deck presence) ----------

describe('LandingPageGenerator.generateAggregate — deck-presence filter', () => {
  const gen = new LandingPageGenerator();

  it('returns an empty array when no domain in the list is ExerisPitchDeck', () => {
    const files = gen.generateAggregate(
      [domain({ entityName: 'Order' }), domain({ entityName: 'Product' })],
      CTX,
    );
    expect(files).toEqual([]);
  });

  it('returns ONE template GeneratedFile when ExerisPitchDeck is present (regardless of position in the list)', () => {
    const files = gen.generateAggregate(
      [
        domain({ entityName: 'Order' }),
        domain({ entityName: 'ExerisPitchDeck' }),
        domain({ entityName: 'Product' }),
      ],
      CTX,
    );
    expect(files).toHaveLength(1);
    expect(files[0].path).toBe('src/app/features/pitch-deck/pitch-deck.component.html');
    expect(files[0].artifactType).toBe('DETAIL');
    expect(files[0].overwritable).toBe(true);
  });
});

// ---------- generateAggregate (template emission) ----------

describe('LandingPageGenerator.generateAggregate — template emission', () => {
  const gen = new LandingPageGenerator();

  it('emits one evidence-item block PER IMAGE_URL field and zero for other field types (text-field bucket is intentionally unused)', () => {
    // Pick a text-field name that does NOT collide with the static
    // template's hardcoded bindings (`title`, `subTitle`,
    // `contactEmail` are all referenced by the static scaffold for
    // the pitch-deck use case). Anything else is fair game for the
    // "text fields aren't rendered" pin.
    const [tpl] = gen.generateAggregate(
      [domain({
        entityName: 'ExerisPitchDeck',
        fields: [
          field({ name: 'tagline', type: 'java.lang.String', defaultValue: 'Native Foundation' }),
          field({ name: 'crashShot', type: 'IMAGE_URL' }),
          field({ name: 'stableShot', type: 'IMAGE_URL' }),
        ],
      })],
      CTX,
    );
    // Two image fields → two cards.
    const cardMatches = tpl.content.match(/<div class="evidence-item">/g) ?? [];
    expect(cardMatches).toHaveLength(2);
    // Image-field bindings present (both as binding + alt label).
    expect(tpl.content).toContain('{{ crashShot }}');
    expect(tpl.content).toContain('alt="crashShot"');
    expect(tpl.content).toContain('{{ stableShot }}');
    expect(tpl.content).toContain('alt="stableShot"');
    // The text-only `tagline` field MUST NOT show up anywhere in the
    // template — the text-field bucket is computed but never
    // rendered today, and the spec pins that exclusion explicitly.
    expect(tpl.content).not.toContain('{{ tagline }}');
    expect(tpl.content).not.toContain('alt="tagline"');
    expect(tpl.content).not.toContain('<h3>tagline</h3>');
  });

  it('uses FieldMetadata.displayName for the evidence label when set', () => {
    const [tpl] = gen.generateAggregate(
      [domain({
        entityName: 'ExerisPitchDeck',
        fields: [field({ name: 'crashShot', type: 'IMAGE_URL', displayName: 'Crash Evidence' })],
      })],
      CTX,
    );
    expect(tpl.content).toContain('<h3>Crash Evidence</h3>');
    expect(tpl.content).toContain('alt="Crash Evidence"');
  });

  it('falls back to FieldMetadata.name when displayName is unset (NOT literal "undefined")', () => {
    // Pre-stage-4e shape used `f.label`, which does not exist on
    // FieldMetadata; the rendered HTML emitted literal "undefined".
    // Stage 4e replaces with `f.displayName ?? f.name` — pin both
    // the success substitution AND the no-undefined invariant.
    const [tpl] = gen.generateAggregate(
      [domain({
        entityName: 'ExerisPitchDeck',
        fields: [field({ name: 'crashShot', type: 'IMAGE_URL' })],
      })],
      CTX,
    );
    expect(tpl.content).toContain('<h3>crashShot</h3>');
    expect(tpl.content).not.toContain('undefined');
  });

  it('emits the static section scaffold (header / EMPIRICAL EVIDENCE / NEXT STEPS) even with zero IMAGE_URL fields', () => {
    const [tpl] = gen.generateAggregate(
      [domain({ entityName: 'ExerisPitchDeck' })],
      CTX,
    );
    expect(tpl.content).toContain('{{ title }}');
    expect(tpl.content).toContain('{{ subTitle }}');
    expect(tpl.content).toContain('EMPIRICAL EVIDENCE');
    expect(tpl.content).toContain('NEXT STEPS');
    expect(tpl.content).toContain('REQUEST SOURCE CODE');
    const cardMatches = tpl.content.match(/<div class="evidence-item">/g) ?? [];
    expect(cardMatches).toHaveLength(0);
  });
});
