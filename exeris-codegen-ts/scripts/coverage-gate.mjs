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

// Note on buffering: spawnSync captures the full subprocess output into
// memory, governed by maxBuffer (default 1 MB). Today's 115-test report
// is ~10 KB so this is fine, but the full src/generators/angular/ pass
// will grow it. If output starts hitting the cap, switch to a streaming
// `spawn` + line-buffer that pipes stdout/stderr through while watching
// for the ERROR: Coverage marker — or raise maxBuffer to 16 MB.
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

// status is null when the child was killed by a signal (OOM, SIGTERM
// from the runner, etc). Treat that as a failure — otherwise CI would
// go green on a half-run vitest, masking genuine outages.
process.exit(result.status ?? (result.signal ? 1 : 0));
