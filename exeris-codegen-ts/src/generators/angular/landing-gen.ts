import { BackendGenerator, GeneratedFile } from '../../core/backend-strategy';
import { CodegenContext } from '../../core/index';
import { DomainEntity } from '../../models/domain-model';
import type { CodeGenerator, ArtifactType, GeneratorContext } from '../../core/generator-registry';

export class LandingPageGenerator implements CodeGenerator {
    readonly name = 'LandingPageGenerator';
    readonly artifactType: ArtifactType = 'DETAIL';
    readonly supportedBackends = [];
    readonly priority = 20;

    generate(domain: any, context: GeneratorContext): GeneratedFile | null {
        // Szukamy tylko encji ExerisPitchDeck
        if (domain.entityName !== 'ExerisPitchDeck') return null;
        return this.generateComponent(domain);
    }

    generateAggregate(domains: any[], context: GeneratorContext): GeneratedFile[] {
        const deckEntity = domains.find(e => e.entityName === 'ExerisPitchDeck');
        if (!deckEntity) return [];
        return [this.generateTemplate(deckEntity)];
    }

    private generateComponent(entity: DomainEntity): GeneratedFile {
        // Generujemy komponent Angulara, który ma te dane "zaszyte" lub pobiera je z serwisu
        // Dla uproszczenia demo - zaszywamy wartości z 'defaultValue'
        const properties = entity.fields.map(f => {
            const val = f.properties['defaultValue'] || '';
            // Escape quotes and newlines
            const safeVal = String.raw`${val}`.replaceAll('"', '\"').replaceAll('\n', '\\n');
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

    private generateTemplate(entity: DomainEntity): GeneratedFile {
        // Rozdzielamy pola tekstowe od obrazkowych
        const textFields = entity.fields.filter(f => f.type !== 'IMAGE_URL');
        const imageFields = entity.fields.filter(f => f.type === 'IMAGE_URL');

        // Generujemy sekcję "Evidence Gallery" dynamicznie
        const evidenceHtml = imageFields.map(f => `
            <div class="evidence-item">
                <h3>${f.label}</h3>
                <div class="terminal-window">
                    <div class="terminal-header">
                        <span class="dot red"></span><span class="dot yellow"></span><span class="dot green"></span>
                    </div>
                    <img src="{{ ${f.name} }}" alt="${f.label}" class="evidence-img" />
                </div>
            </div>
        `).join('\n');

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
