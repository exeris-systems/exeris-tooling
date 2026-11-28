/**
 * Generate the Angular application of processor metadata that holds an `Order` entity: the SDK's
 * example corpus or the view-site fixture, for the CI job that builds it.
 *
 * The input is the processor metadata SdkExamplesE2ETest (exeris-e2e-tests) writes from the
 * exeris-sdk-examples sources at the pinned SDK tag. The app is generated through the CLI, exactly
 * as a consumer runs it, with the emitted specs on, so the job can `ng build` and `ng test` it.
 *
 * The CLI runs twice, the second time into an empty temporary directory, and the two outputs must
 * be byte-identical: same metadata, same emitted app.
 *
 * Usage: node scripts/gen-sdk-examples-app.mjs <metadata-dir> <output-dir> [--ssg]
 *
 * `--ssg` writes an exeris-codegen.json with `render: 'ssg'`, `sitemap` and a `siteUrl` into the
 * directory the CLI runs in, which the CLI reads: the static-site shape, which has no CLI flag for
 * the site URL.
 *
 * Everything in <output-dir> except node_modules is removed first, so a local re-run starts from
 * an empty project without reinstalling.
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const cli = join(here, '..', 'dist', 'index.js');

const [metadataArg, outArg, mode] = process.argv.slice(2);
if (!metadataArg || !outArg || (mode !== undefined && mode !== '--ssg')) {
  console.error('usage: node scripts/gen-sdk-examples-app.mjs <metadata-dir> <output-dir> [--ssg]');
  process.exit(2);
}
const metadata = resolve(metadataArg);
const out = resolve(outArg);
if (!existsSync(join(metadata, 'Order.json'))) {
  console.error(`::error::${metadata} holds no processor metadata with an Order entity; run SdkExamplesE2ETest or ViewSiteFixtureE2ETest first`);
  process.exit(1);
}
if (!existsSync(cli)) {
  console.error(`::error::${cli} is missing; run npm run build first`);
  process.exit(1);
}

const generate = (target) => {
  if (mode === '--ssg') {
    writeFileSync(
      join(target, 'exeris-codegen.json'),
      JSON.stringify({ render: 'ssg', sitemap: true, siteUrl: 'https://example.com/site' }, null, 2) + '\n',
    );
  }
  return execFileSync(process.execPath, [cli, 'generate', '--input', metadata, '--output', '.', '--tests'], {
    cwd: target,
    stdio: ['ignore', 'pipe', 'inherit'],
  });
};

/** Every file under root except node_modules, relative path → contents. */
const snapshot = (root) => {
  const files = new Map();
  const walk = (dir) => {
    for (const name of readdirSync(dir).sort()) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) {
        if (name !== 'node_modules') walk(full);
      } else {
        files.set(relative(root, full).split('\\').join('/'), readFileSync(full));
      }
    }
  };
  walk(root);
  return files;
};

mkdirSync(out, { recursive: true });
for (const name of readdirSync(out)) {
  if (name !== 'node_modules') rmSync(join(out, name), { recursive: true, force: true });
}
generate(out);

const rerun = mkdtempSync(join(tmpdir(), 'sdk-examples-app-'));
try {
  generate(rerun);
  const first = snapshot(out);
  const second = snapshot(rerun);
  const differing = [...new Set([...first.keys(), ...second.keys()])]
    .filter((path) => !first.has(path) || !second.has(path) || !first.get(path).equals(second.get(path)))
    .sort();
  if (differing.length > 0) {
    console.error(`::error::two generations from the same metadata differ in: ${differing.join(', ')}`);
    process.exit(1);
  }
  console.log(`gen-sdk-examples-app — ${first.size} files in ${out}, identical across two runs`);
} finally {
  rmSync(rerun, { recursive: true, force: true });
}
