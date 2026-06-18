/**
 * FE build gate (T20) — generate a sample app and type-check its data layer.
 *
 * The codegen e2e otherwise asserts only emitted *text*, which is exactly how the
 * T20 break (an empty enum stub shadowing the real module) stayed latent. This gate
 * generates a fixture app via the real orchestrator and runs `tsc --noEmit` over the
 * data layer (`src/app/types/**` + `src/app/schemas/**`) — the files that import only
 * `zod` and each other, so they type-check without Angular installed. It catches the
 * enum/schema cross-reference breakage (TS2304/2305) that "doesn't compile" means.
 *
 * The Angular component/service layer needs `@angular/*`; the full `ng build` over a
 * generated app is the CI job (build.yml). This script is the fast, dependency-light
 * half that also runs locally.
 *
 * Exit code 0 = data layer type-checks; non-zero = regression.
 */

import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const pkgRoot = join(here, '..');
const dist = join(pkgRoot, 'dist');

const { buildGeneratedFiles } = await import(join(dist, 'orchestrator.js'));
const { DomainMetadataSchema } = await import(join(dist, 'models/domain-model.js'));
const { DEFAULT_CONFIG } = await import(join(dist, 'config.js'));

// Fixture: an entity with an ENUM-typed field, so the generated entity type and Zod
// schema import the enum module — if that module were the empty stub, this fails.
const domain = DomainMetadataSchema.parse({
  packageName: 'com.shop',
  entityName: 'Battle',
  fields: [
    { name: 'id', type: 'java.util.UUID' },
    { name: 'status', type: 'com.shop.BattleStatus', enumType: 'com.shop.BattleStatus' },
  ],
});
const enums = [{
  name: 'BattleStatus',
  qualifiedName: 'com.shop.BattleStatus',
  packageName: 'com.shop',
  values: [
    { name: 'ACTIVE', displayName: 'Active', ordinal: 0 },
    { name: 'RESOLVED', displayName: 'Resolved', ordinal: 1 },
  ],
}];

const files = buildGeneratedFiles([domain], enums, DEFAULT_CONFIG);

// The dependency-light data layer: types + schemas + the enum module.
const dataLayer = files.filter(
  (f) => f.path.startsWith('src/app/types/') || f.path.startsWith('src/app/schemas/'),
);
if (dataLayer.length === 0) {
  console.error('verify:generated — no data-layer files emitted; orchestrator changed?');
  process.exit(1);
}

const tmp = join(pkgRoot, '.verify-tmp');
rmSync(tmp, { recursive: true, force: true });
for (const f of dataLayer) {
  const full = join(tmp, f.path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, f.content);
}
writeFileSync(join(tmp, 'tsconfig.json'), JSON.stringify({
  compilerOptions: {
    target: 'ES2022',
    module: 'ESNext',
    moduleResolution: 'bundler',
    strict: true,
    noEmit: true,
    skipLibCheck: true,
    types: [],
  },
  include: ['src/app/types/**/*.ts', 'src/app/schemas/**/*.ts'],
}, null, 2));

console.log(`verify:generated — type-checking ${dataLayer.length} data-layer file(s)…`);
try {
  execSync(`node ${join(pkgRoot, 'node_modules', 'typescript', 'bin', 'tsc')} -p ${join(tmp, 'tsconfig.json')}`, {
    cwd: pkgRoot,
    stdio: 'inherit',
  });
} catch {
  console.error('\n✗ Generated frontend data layer does NOT type-check (T20 regression).');
  rmSync(tmp, { recursive: true, force: true });
  process.exit(1);
}
rmSync(tmp, { recursive: true, force: true });
console.log('✓ Generated frontend data layer type-checks (enums + types + schemas resolve).');
