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
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const pkgRoot = join(here, '..');
const dist = join(pkgRoot, 'dist');

// Dynamic import() needs a file:// URL, not a bare OS path — a bare `d:\…` path
// throws ERR_UNSUPPORTED_ESM_URL_SCHEME on Windows. pathToFileURL is a no-op-equivalent on POSIX.
const { buildGeneratedFiles } = await import(pathToFileURL(join(dist, 'orchestrator.js')).href);
const { DomainMetadataSchema } = await import(pathToFileURL(join(dist, 'models/domain-model.js')).href);
const { DEFAULT_CONFIG } = await import(pathToFileURL(join(dist, 'config.js')).href);

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

const tscBin = join(pkgRoot, 'node_modules', 'typescript', 'bin', 'tsc');

/** Generate a fixture app, write its data layer to a temp dir, and `tsc --noEmit`.
 *  The data layer (types + schemas + the enum module) imports only `zod` + itself,
 *  so it type-checks without an Angular install. */
function check(label, domains, fixtureEnums) {
  const files = buildGeneratedFiles(domains, fixtureEnums, DEFAULT_CONFIG);
  const dataLayer = files.filter(
    (f) => f.path.startsWith('src/app/types/') || f.path.startsWith('src/app/schemas/'),
  );
  if (dataLayer.length === 0) {
    console.error(`verify:generated [${label}] — no data-layer files emitted; orchestrator changed?`);
    process.exit(1);
  }

  const tmp = join(pkgRoot, '.verify-tmp', label);
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

  console.log(`verify:generated [${label}] — type-checking ${dataLayer.length} data-layer file(s)…`);
  try {
    execSync(`node "${tscBin}" -p "${join(tmp, 'tsconfig.json')}"`, {
      cwd: pkgRoot,
      stdio: 'inherit',
      timeout: 60_000,
    });
  } catch {
    console.error(`\n✗ [${label}] generated frontend data layer does NOT type-check (T20 regression).`);
    rmSync(join(pkgRoot, '.verify-tmp'), { recursive: true, force: true });
    process.exit(1);
  }
  rmSync(tmp, { recursive: true, force: true });
}

// (1) entity with an enum-typed field; (2) zero-enum project — the empty enum module
// + barrels re-exporting ./enums must still resolve (dangling re-export = TS2307).
check('with-enums', [domain], enums);
check('zero-enums', [DomainMetadataSchema.parse({ packageName: 'com.shop', entityName: 'Plain', fields: [{ name: 'id', type: 'java.util.UUID' }, { name: 'name', type: 'String' }] })], []);

rmSync(join(pkgRoot, '.verify-tmp'), { recursive: true, force: true });
console.log('✓ Generated frontend data layer type-checks (with-enums + zero-enums).');
