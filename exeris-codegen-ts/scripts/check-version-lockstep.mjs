#!/usr/bin/env node
/**
 * Version lockstep guard.
 *
 * @exeris/codegen-ts versions in lockstep with the Maven reactor: one tag vX.Y.Z releases both, so
 * package.json's `version` must equal the root pom.xml's project version at every commit — the
 * -SNAPSHOT development line included — and package-lock.json must carry the same version as
 * package.json. Exits non-zero, naming the values, when any of them differ.
 *
 * The project version is the `<version>` that is a direct child of `<project>`; the `<version>` of
 * a `<parent>`, a dependency, a plugin or a property is not it. The pom is read with a depth-tracking
 * tag scan rather than a regex for the first `<version>`, so element order and nesting cannot
 * select the wrong one. No dependencies: this runs before `npm ci`.
 *
 * Usage: node scripts/check-version-lockstep.mjs [path/to/pom.xml]
 *   default pom: the repository root, two directories above this script.
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const packageJsonPath = resolve(here, '..', 'package.json');
const pomPath = resolve(process.argv[2] ?? resolve(here, '..', '..', 'pom.xml'));

function projectVersion(xml) {
  // Comments, CDATA, processing instructions and the doctype carry no elements.
  const body = xml
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '')
    .replace(/<\?[\s\S]*?\?>/g, '')
    .replace(/<!DOCTYPE[^>]*>/gi, '');

  const tag = /<(\/?)([A-Za-z_][\w.:-]*)[^>]*?(\/?)>/g;
  let depth = 0;
  let m;
  while ((m = tag.exec(body)) !== null) {
    const [, closing, name, selfClosing] = m;
    if (closing) {
      depth--;
      continue;
    }
    if (selfClosing) continue;
    depth++;
    // depth 1 is <project>; its direct children open at depth 2.
    if (depth === 2 && name === 'version') {
      const end = body.indexOf('</version>', tag.lastIndex);
      if (end < 0) return null;
      return body.slice(tag.lastIndex, end).trim();
    }
  }
  return null;
}

let pomVersion;
try {
  pomVersion = projectVersion(readFileSync(pomPath, 'utf8'));
} catch (e) {
  console.error(`check-version-lockstep: cannot read ${pomPath}: ${e}`);
  process.exit(2);
}
if (!pomVersion) {
  console.error(`check-version-lockstep: ${pomPath} declares no <project><version>`);
  process.exit(2);
}

const packageVersion = JSON.parse(readFileSync(packageJsonPath, 'utf8')).version;
const lock = JSON.parse(readFileSync(resolve(here, '..', 'package-lock.json'), 'utf8'));
const lockVersions = [lock.version, lock.packages?.['']?.version];
const fix =
  `set it with\n  npm version ${pomVersion} --no-git-tag-version --ignore-scripts\n` +
  `in exeris-codegen-ts/ (updates package.json and package-lock.json together).`;

if (packageVersion !== pomVersion) {
  console.error(
    `check-version-lockstep: FAILED — package.json version is ${packageVersion}, ` +
      `the Maven reactor (${pomPath}) is ${pomVersion}.\n` +
      `@exeris/codegen-ts releases in lockstep with the reactor; ${fix}`,
  );
  process.exit(1);
}

if (lockVersions.some((v) => v !== packageVersion)) {
  console.error(
    `check-version-lockstep: FAILED — package-lock.json carries ${lockVersions.join(' / ')}, ` +
      `package.json carries ${packageVersion}; ${fix}`,
  );
  process.exit(1);
}

console.log(`check-version-lockstep: ok — package.json and pom.xml both at ${pomVersion}`);
