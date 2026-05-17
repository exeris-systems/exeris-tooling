#!/usr/bin/env node
/**
 * Coverage gate wrapper.
 *
 * vitest 4.1.6 reports threshold violations to stdout (lines starting
 * "ERROR: Coverage ...") but exits 0 regardless — meaning a violation
 * does not fail the build by itself. CI needs a non-zero exit to block
 * a regression, so this wrapper runs `vitest run --coverage`, streams
 * its output, then exits non-zero if any "ERROR: Coverage" line was
 * emitted OR if vitest itself failed for another reason.
 *
 * Once the upstream vitest behaviour is fixed, this wrapper can be
 * deleted and `test:coverage` in package.json can point straight at
 * `vitest run --coverage` again.
 */

import { spawnSync } from 'node:child_process';

const result = spawnSync(
  'npx',
  ['vitest', 'run', '--coverage', ...process.argv.slice(2)],
  { encoding: 'utf8' },
);

// Stream the captured output back to the user so npm-script consumers
// see the same report they would from a direct vitest call.
process.stdout.write(result.stdout ?? '');
process.stderr.write(result.stderr ?? '');

const combined = (result.stdout ?? '') + (result.stderr ?? '');
const thresholdViolated = /^ERROR: Coverage/m.test(combined);

if (thresholdViolated) {
  console.error('\nGate FAILED — one or more coverage thresholds were not met.');
  process.exit(1);
}

process.exit(result.status ?? 0);
