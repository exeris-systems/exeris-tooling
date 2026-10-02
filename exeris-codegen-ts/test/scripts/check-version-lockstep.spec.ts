/**
 * The version lockstep guard (scripts/check-version-lockstep.mjs) is what keeps a package.json that
 * disagrees with the reactor from merging or releasing. Each case runs the real script inside a
 * throwaway repository layout — root pom.xml, exeris-codegen-ts/package.json and package-lock.json —
 * so the paths the script resolves relative to itself point at the fixture.
 */

import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

const SCRIPT = resolve(__dirname, '..', '..', 'scripts', 'check-version-lockstep.mjs');

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true });
});

function run(pom: string, pkgVersion: string, lockVersion = pkgVersion, lockRootVersion = lockVersion) {
  const root = mkdtempSync(join(tmpdir(), 'lockstep-'));
  roots.push(root);
  const pkgDir = join(root, 'exeris-codegen-ts');
  mkdirSync(join(pkgDir, 'scripts'), { recursive: true });
  copyFileSync(SCRIPT, join(pkgDir, 'scripts', 'check-version-lockstep.mjs'));
  writeFileSync(join(root, 'pom.xml'), pom);
  writeFileSync(join(pkgDir, 'package.json'), JSON.stringify({ name: '@exeris/codegen-ts', version: pkgVersion }));
  writeFileSync(
    join(pkgDir, 'package-lock.json'),
    JSON.stringify({ name: '@exeris/codegen-ts', version: lockVersion, packages: { '': { version: lockRootVersion } } }),
  );
  const res = spawnSync(process.execPath, [join(pkgDir, 'scripts', 'check-version-lockstep.mjs')], { encoding: 'utf8' });
  return { status: res.status, out: `${res.stdout}${res.stderr}` };
}

function pom(body: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<project>\n  <modelVersion>4.0.0</modelVersion>\n${body}\n</project>\n`;
}

describe('check-version-lockstep', () => {
  it('passes when package.json and the lockfile carry the project version', () => {
    const r = run(pom('  <artifactId>root</artifactId>\n  <version>0.9.0-SNAPSHOT</version>'), '0.9.0-SNAPSHOT');
    expect(r.status).toBe(0);
  });

  it('reads the project version, not the parent, a property or a dependency version', () => {
    const r = run(
      pom([
        '  <parent><groupId>g</groupId><artifactId>p</artifactId><version>1.2.3</version></parent>',
        '  <artifactId>root</artifactId>',
        '  <version>0.9.0</version>',
        '  <properties><foo.version>5.5.5</foo.version></properties>',
        '  <dependencies><dependency><version>7.7.7</version></dependency></dependencies>',
      ].join('\n')),
      '0.9.0',
    );
    expect(r.status).toBe(0);
  });

  it('ignores a version inside a comment, a CDATA section or a processing instruction', () => {
    const r = run(
      pom([
        '  <!-- <version>9.9.9</version> -->',
        '  <![CDATA[ <version>8.8.8</version> ]]>',
        '  <?pi <version>6.6.6</version> ?>',
        '  <artifactId>root</artifactId>',
        '  <version>0.9.0</version>',
      ].join('\n')),
      '0.9.0',
    );
    expect(r.status).toBe(0);
  });

  it('fails with exit 1 when package.json differs from the POM', () => {
    const r = run(pom('  <artifactId>root</artifactId>\n  <version>0.9.0</version>'), '0.2.0');
    expect(r.status).toBe(1);
    expect(r.out).toContain('package.json version is 0.2.0');
  });

  it('fails with exit 1 when the lockfile differs from package.json', () => {
    const r = run(pom('  <artifactId>root</artifactId>\n  <version>0.9.0</version>'), '0.9.0', '0.9.0', '0.2.0');
    expect(r.status).toBe(1);
    expect(r.out).toContain('package-lock.json');
  });

  it('fails with exit 2 when the POM declares no project version', () => {
    const r = run(pom('  <artifactId>root</artifactId>\n  <!-- <version>0.9.0</version> -->'), '0.9.0');
    expect(r.status).toBe(2);
  });

  it('fails with exit 2 when an unterminated comment swallows the version', () => {
    const r = run(pom('  <artifactId>root</artifactId>\n  <!-- unterminated\n  <version>0.9.0</version>'), '0.9.0');
    expect(r.status).toBe(2);
  });
});
