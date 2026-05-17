import type { GeneratedFile, CodeGenerator, ArtifactType, GeneratorContext } from '../../core/generator-registry.js';
import type { BackendType } from '../../core/backend-strategy.js';
import type { DomainMetadata, FieldMetadata } from '../../models/domain-model.js';

/** Single source of truth for the hardcoded entity name this
 *  generator binds to — used by both generate() and
 *  generateAggregate() so a future rename of the marketing entity
 *  is a one-line change. */
const PITCH_DECK_ENTITY = 'ExerisPitchDeck';

/** Minimal HTML escape for values interpolated into the emitted
 *  template (h3 text + alt=""). Domain metadata is developer-
 *  authored, so the practical blast radius of unescaped output is
 *  contained, but a `displayName` containing `"` would otherwise
 *  break the alt attribute and `<` / `>` would inject markup into
 *  the rendered template. Escapes the standard five HTML special
 *  characters; leaves everything else untouched. */
function escapeHtml(value: string): string {
    return value
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

/**
 * Landing-page generator — emits a single Angular component + template
 * pair for the marketing "ExerisPitchDeck" entity. Short-circuits to
 * `null` for every other entity (the generator is keyed off the exact
 * `entityName === PITCH_DECK_ENTITY` literal).
 *
 * Registered alongside DetailGenerator under the DETAIL artifact type
 * — both are intentionally active at the same time; see the registry
 * spec ("DETAIL artifact type has TWO generators registered").
 *
 * The earlier shape of this file imported `BackendGenerator`,
 * `CodegenContext` and `DomainEntity` — none of which exist in the
 * current SDK / core surface. They were pre-existing tsc errors that
 * stage 4d kept out of the per-file coverage gate; stage 4e (this PR)
 * realigns the imports against the actual exports and the current
 * `FieldMetadata` Zod schema (flat `defaultValue` / `displayName`,
 * not a `properties: Record<string, unknown>` bag and no `label`).
 */
export class LandingPageGenerator implements CodeGenerator {
    readonly name = 'LandingPageGenerator';
    readonly artifactType: ArtifactType = 'DETAIL';
    readonly supportedBackends: BackendType[] = [];
    readonly priority = 20;

    generate(domain: DomainMetadata, _context: GeneratorContext): GeneratedFile | null {
        if (domain.entityName !== PITCH_DECK_ENTITY) return null;
        return this.generateComponent(domain);
    }

    generateAggregate(domains: DomainMetadata[], _context: GeneratorContext): GeneratedFile[] {
        const deckEntity = domains.find((e) => e.entityName === PITCH_DECK_ENTITY);
        if (!deckEntity) return [];
        return [this.generateTemplate(deckEntity)];
    }

    private generateComponent(entity: DomainMetadata): GeneratedFile {
        const properties = entity.fields.map((f: FieldMetadata) => {
            const val = f.defaultValue ?? '';
            // Escape characters that would break out of the surrounding
            // double-quoted TS string literal we emit on the next line.
            // The earlier `'\"'` replacement was a no-op (CodeQL
            // js/identity-replacement, GHSA-tracked alert #1): inside a
            // single-quoted JS string `'\"'` is just `'"'`, so
            // `.replaceAll('"', '\"')` replaced `"` with itself. The fix
            // uses `'\\"'` (= 2-char sequence `\"`), which is the actual
            // escape that survives into the emitted TS source.
            const safeVal = String(val).replaceAll('"', '\\"').replaceAll('\n', '\\n');
            return `  ${f.name} = "${safeVal}";`;
        }).join('\n');
        const content = `
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-pitch-deck',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pitch-deck.component.html',
  styles: [
    ` + "`" + `
    :host { display: block; background-color: #050505; color: #e0e0e0; font-family: monospace; min-height: 100vh; }
    .container { max-width: 900px; margin: 0 auto; padding: 4rem 2rem; }
    h1 { font-size: 3rem; color: #fff; margin-bottom: 0.5rem; letter-spacing: -1px; }
    .accent { color: #00ff41; }
    .section { margin: 4rem 0; border-top: 1px solid #333; padding-top: 2rem; }
    .stat-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 2rem; margin-top: 2rem; }
    .stat-box { border: 1px solid #333; padding: 1.5rem; }
    .stat-val { font-size: 1.5rem; font-weight: bold; color: #fff; }
    .stat-label { font-size: 0.8rem; color: #888; text-transform: uppercase; }
    .win { color: #00ff41; }
    .loss { color: #ff3333; }
    .btn { display: inline-block; border: 1px solid #00ff41; color: #00ff41; padding: 1rem 2rem; text-decoration: none; margin-top: 2rem; font-weight: bold; }
    .btn:hover { background: #00ff41; color: #000; }
    .evidence-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 2rem; margin-top: 2rem; }
    .evidence-item h3 { font-size: 0.9rem; color: #888; margin-bottom: 0.5rem; font-family: sans-serif; letter-spacing: 1px; }
    .terminal-window { background: #1e1e1e; border-radius: 6px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); border: 1px solid #333; overflow: hidden; transition: transform 0.2s; }
    .terminal-window:hover { transform: scale(1.02); border-color: #00ff41; }
    .terminal-header { background: #2d2d2d; padding: 8px 12px; display: flex; gap: 6px; border-bottom: 1px solid #333; }
    .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
    .red { background: #ff5f56; }
    .yellow { background: #ffbd2e; }
    .green { background: #27c93f; }
    .evidence-img { width: 100%; height: auto; display: block; opacity: 0.9; }
  ` + "`" + `
  ]
})
export class PitchDeckComponent {
${properties}
}
`;
        return {
            path: 'src/app/features/pitch-deck/pitch-deck.component.ts',
            content: content,
            artifactType: 'DETAIL',
            overwritable: true
        };
    }

    private generateTemplate(entity: DomainMetadata): GeneratedFile {
        // Split text fields from image-URL fields. The text bucket is
        // computed but not currently rendered in the emitted HTML —
        // it's a hook for future text-section work and intentionally
        // kept around so adding text rendering doesn't require
        // re-introducing the filter.
        const _textFields = entity.fields.filter((f: FieldMetadata) => f.type !== 'IMAGE_URL');
        const imageFields = entity.fields.filter((f: FieldMetadata) => f.type === 'IMAGE_URL');

        // Evidence Gallery — one terminal-window card per IMAGE_URL
        // field. We prefer `displayName` (the human-readable label
        // surfaced by FieldMetadata) and fall back to the bare field
        // name when displayName is unset. The earlier shape called
        // `f.label`, which doesn't exist on FieldMetadata and emitted
        // literal "undefined" into the rendered HTML. The label is
        // interpolated into BOTH an h3 text body and an alt=""
        // attribute, so it goes through escapeHtml — a displayName
        // containing `"` would otherwise break the alt syntax, and
        // `<` / `>` would inject markup into the rendered template.
        const evidenceHtml = imageFields.map((f: FieldMetadata) => {
            const label = escapeHtml(f.displayName ?? f.name);
            return `
            <div class="evidence-item">
                <h3>${label}</h3>
                <div class="terminal-window">
                    <div class="terminal-header">
                        <span class="dot red"></span><span class="dot yellow"></span><span class="dot green"></span>
                    </div>
                    <img src="{{ ${f.name} }}" alt="${label}" class="evidence-img" />
                </div>
            </div>
        `;
        }).join('\n');

        const content = `
<div class="container">
    <header>
        <h1>{{ title }}<span class="accent">_</span></h1>
        <p class="lead">{{ subTitle }}</p>
    </header>

    <div class="section">
        <h2 class="accent">> EMPIRICAL EVIDENCE (Screenshots)</h2>
        <p class="sub-text">Raw terminal output capturing the crash of legacy frameworks vs Exeris stability.</p>
        <div class="evidence-grid">
            ${evidenceHtml}
        </div>
    </div>

    <div class="section">
        <h2 class="accent">> NEXT STEPS</h2>
        <p>Built with Exeris Generator v1.3</p>
        <a [attr.href]="'mailto:' + contactEmail" class="btn">REQUEST SOURCE CODE</a>
    </div>
</div>
`;
        return {
            path: 'src/app/features/pitch-deck/pitch-deck.component.html',
            content: content,
            artifactType: 'DETAIL',
            overwritable: true
        };
    }
}
