#!/usr/bin/env bash
#
# Release-readiness gate for a Maven Central release.
#
# Asserts that a `mvn -P release verify` (or `deploy`) produced, for every coordinate Central will
# receive, the files Central requires (pom, and for a jar-packaged module the jar, a sources jar and
# a javadoc jar), and that each of those carries a detached signature that verifies. Central
# validates per artifact, so one module short of one file fails the whole deployment, and a
# published version can never be reused.
#
# The set of coordinates comes from the reactor's <modules>, walked recursively, not from what is
# on disk: a module whose release profile never ran produces nothing, and a scan of target/ cannot
# see an absence.
#
# Modules held back from Central are named twice in the build, for two different plugins:
# maven.deploy.skip in the module's own pom (maven-deploy-plugin) and <excludeArtifacts> in the root
# pom's `release` profile (central-publishing-maven-plugin, which replaces maven-deploy-plugin and
# does not read that property). This gate fails when the two lists disagree.
#
# @exeris/codegen-ts (npm) is released by the same tag: this gate also fails when its package.json
# or package-lock.json version differs from the reactor's.
#
# Usage:
#   tools/release-readiness/release-readiness.sh             # release.yml: full gate
#   tools/release-readiness/release-readiness.sh --unsigned  # local: after -Dgpg.skip, no signatures
#
# --unsigned checks the jar, sources and javadoc files only. It cannot stand in for the full gate:
# the signed pom files are produced by the signer, so it does not check them at all.
set -euo pipefail

UNSIGNED=0
case "${1:-}" in
  -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
  --unsigned) UNSIGNED=1 ;;
  "") ;;
  *) echo "unknown argument: $1" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

if [ "$UNSIGNED" = "0" ]; then
  command -v gpg >/dev/null || { echo "release-readiness: FAILED — gpg not on PATH"; exit 1; }
fi

UNSIGNED="$UNSIGNED" python3 - <<'PY'
import json, os, pathlib, subprocess, sys, zipfile
import xml.etree.ElementTree as ET

NS = '{http://maven.apache.org/POM/4.0.0}'
UNSIGNED = os.environ['UNSIGNED'] == '1'


def text(node, tag, default=None):
    v = node.findtext(NS + tag) if node is not None else None
    return v.strip() if v is not None else default


def reactor(directory=pathlib.Path('.')):
    """Every module directory in the reactor, the root included, depth-first."""
    pom = directory / 'pom.xml'
    yield directory, pom
    root = ET.parse(pom).getroot()
    modules = root.find(NS + 'modules')
    for m in (modules if modules is not None else []):
        child = directory / m.text.strip()
        if (child / 'pom.xml').is_file():
            yield from reactor(child)


def coordinates(pom):
    root = ET.parse(pom).getroot()
    version = text(root, 'version') or text(root.find(NS + 'parent'), 'version')
    props = root.find(NS + 'properties')
    deploy_skip = text(props, 'maven.deploy.skip') == 'true'
    return text(root, 'artifactId'), version, text(root, 'packaging', 'jar'), deploy_skip


def excluded_from_central():
    root = ET.parse('pom.xml').getroot()
    return {e.text.strip() for ex in root.iter(NS + 'excludeArtifacts') for e in ex if e.text}


failures = []
checked = signatures = 0
excluded = excluded_from_central()
deploy_skipped = set()
published = []

for directory, pom in reactor():
    artifact, version, packaging, deploy_skip = coordinates(pom)
    if deploy_skip:
        deploy_skipped.add(artifact)
    if artifact in excluded or deploy_skip:
        continue
    published.append(artifact)
    target = directory / 'target'
    stem = f'{artifact}-{version}'

    required = [] if UNSIGNED else [target / f'{stem}.pom']
    if packaging != 'pom':
        required += [target / f'{stem}.jar',
                     target / f'{stem}-sources.jar',
                     target / f'{stem}-javadoc.jar']

    for path in required:
        checked += 1
        if not path.is_file():
            failures.append(f'{artifact}: missing {path}')
            continue
        if UNSIGNED:
            continue
        sig = path.with_name(path.name + '.asc')
        if not sig.is_file():
            failures.append(f'{artifact}: {path.name} has no detached signature ({sig.name})')
            continue
        signatures += 1
        proc = subprocess.run(['gpg', '--verify', str(sig), str(path)],
                              capture_output=True, text=True)
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout).strip().splitlines()
            failures.append(f'{artifact}: signature on {path.name} does not verify: '
                            f'{detail[-1] if detail else "gpg gave no reason"}')

    # The javadoc plugin runs with failOnError=false, so a javadoc run that failed still leaves a
    # jar behind when it got as far as packaging. An index page is what a generated one always has.
    javadoc = target / f'{stem}-javadoc.jar'
    if packaging != 'pom' and javadoc.is_file():
        with zipfile.ZipFile(javadoc) as z:
            if 'index.html' not in z.namelist():
                failures.append(f'{artifact}: {javadoc.name} has no index.html — javadoc did not run')

    # A Maven plugin without its descriptor installs and then fails at the consumer's first goal.
    main_jar = target / f'{stem}.jar'
    if packaging == 'maven-plugin' and main_jar.is_file():
        with zipfile.ZipFile(main_jar) as z:
            if 'META-INF/maven/plugin.xml' not in z.namelist():
                failures.append(f'{artifact}: {main_jar.name} carries no META-INF/maven/plugin.xml')

# @exeris/codegen-ts is released by the same tag as the reactor, so it carries the same version.
reactor_version = coordinates(pathlib.Path('pom.xml'))[1]
npm_dir = pathlib.Path('exeris-codegen-ts')
npm_version = json.loads((npm_dir / 'package.json').read_text())['version']
lock = json.loads((npm_dir / 'package-lock.json').read_text())
lock_versions = {lock.get('version'), lock.get('packages', {}).get('', {}).get('version')}
if npm_version != reactor_version:
    failures.append(f'exeris-codegen-ts/package.json is at {npm_version}, the reactor at '
                    f'{reactor_version}: the two release in lockstep')
if lock_versions != {npm_version}:
    failures.append(f'exeris-codegen-ts/package-lock.json carries {sorted(map(str, lock_versions))}, '
                    f'package.json {npm_version}')

if deploy_skipped != excluded:
    failures.append('maven.deploy.skip and <excludeArtifacts> disagree about which modules ship: '
                    f'deploy.skip={sorted(deploy_skipped)} excludeArtifacts={sorted(excluded)}')

if checked == 0:
    print('release-readiness: FAILED — checked 0 files; run `mvn -P release verify` first')
    sys.exit(1)

mode = ' (--unsigned: signatures and pom files NOT checked)' if UNSIGNED else ''
print(f'release-readiness{mode}: {len(published)} published coordinate(s), '
      f'{checked} required file(s), {signatures} signature(s) verified')
for artifact in published:
    print(f'    published: {artifact}')
for artifact in sorted(excluded | deploy_skipped):
    print(f'    NOT PUBLISHED to Central: {artifact}')

if failures:
    print(f'\nFAILED — {len(failures)} problem(s):')
    for f in failures:
        print(f'    {f}')
    print('\nA Maven Central release cannot be changed once published. If a deployment was '
          'uploaded, DROP it in the portal.')
    sys.exit(1)

print('release-readiness: PASSED')
PY
