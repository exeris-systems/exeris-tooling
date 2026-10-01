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
# maven.deploy.skip in the module's own pom (maven-deploy-plugin) and <excludeArtifacts>
# (central-publishing-maven-plugin, which replaces maven-deploy-plugin and does not read that
# property). central-publishing-maven-plugin is declared twice, in the root pom's `release` profile
# and in exeris-app-bom's `exeris-tooling-release` profile (ADR-091), and each module stages through
# the declaration its parent chain reaches. This gate fails when either declaration's
# <excludeArtifacts> disagrees with maven.deploy.skip of the modules it governs. The two profiles
# must publish alike, so it also fails when any of their four plugins (source, javadoc, gpg,
# central-publishing) differs between them in version, extensions, executions or configuration,
# <excludeArtifacts> aside, or is missing from either.
#
# exeris-app-bom has no parent and so states the tooling, kernel and SDK versions as literals
# (ADR-091). This gate fails when any of them is a -SNAPSHOT, when the tooling version or the BOM's
# own version is not the reactor version, and when its kernel, SDK or Jackson 3 version differs
# from exeris-tooling-bom's, the versions this release was built and tested against.
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
  -h|--help) sed -n '2,34p' "$0"; exit 0 ;;
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
import os, pathlib, subprocess, sys, zipfile
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


CENTRAL = ('org.sonatype.central', 'central-publishing-maven-plugin')
# The POMs that declare central-publishing-maven-plugin, and the profile each declares it in.
DECLARATIONS = {'exeris-tooling-root': 'release', 'exeris-app-bom': 'exeris-tooling-release'}


def central_plugin(pom, profile_id):
    """The central-publishing-maven-plugin element of `profile_id` in `pom`, or None."""
    root = ET.parse(pom).getroot()
    for profile in root.iter(NS + 'profile'):
        if text(profile, 'id') != profile_id:
            continue
        for plugin in profile.iter(NS + 'plugin'):
            if (text(plugin, 'groupId'), text(plugin, 'artifactId')) == CENTRAL:
                return plugin
    return None


def excluded(plugin):
    ex = plugin.find(f'{NS}configuration/{NS}excludeArtifacts')
    return {e.text.strip() for e in (ex if ex is not None else []) if e.text and e.text.strip()}


# The publishing plugins both profiles declare. A plugin's version may come from the declaring
# POM's pluginManagement (the root's source and javadoc do).
PUBLISHING = [('org.apache.maven.plugins', 'maven-source-plugin'),
              ('org.apache.maven.plugins', 'maven-javadoc-plugin'),
              ('org.apache.maven.plugins', 'maven-gpg-plugin'),
              CENTRAL]


def canonical(element):
    """An element as comparable text: tags and trimmed text, whitespace and comments dropped."""
    if element is None:
        return None
    tag = element.tag.replace(NS, '') if isinstance(element.tag, str) else None
    if tag is None:
        return ''
    if tag == 'excludeArtifacts':
        return ''
    inner = ''.join(canonical(c) for c in element)
    return f'<{tag}>{(element.text or "").strip()}{inner}</{tag}>'


def publishing_plugins(pom, profile_id):
    """{(groupId, artifactId): comparable description} for PUBLISHING in `profile_id` of `pom`."""
    root = ET.parse(pom).getroot()
    managed = {}
    pm = root.find(f'{NS}build/{NS}pluginManagement/{NS}plugins')
    for plugin in (pm if pm is not None else []):
        managed[(text(plugin, 'groupId', 'org.apache.maven.plugins'), text(plugin, 'artifactId'))] = \
            text(plugin, 'version')
    found = {}
    for profile in root.iter(NS + 'profile'):
        if text(profile, 'id') != profile_id:
            continue
        for plugin in profile.iter(NS + 'plugin'):
            key = (text(plugin, 'groupId', 'org.apache.maven.plugins'), text(plugin, 'artifactId'))
            if key not in PUBLISHING:
                continue
            found[key] = {
                'version': text(plugin, 'version') or managed.get(key),
                'extensions': text(plugin, 'extensions'),
                'executions': canonical(plugin.find(NS + 'executions')),
                'configuration': canonical(plugin.find(NS + 'configuration')),
            }
    return found


def properties(pom):
    props = ET.parse(pom).getroot().find(NS + 'properties')
    return {c.tag.replace(NS, ''): (c.text or '').strip() for c in (props if props is not None else [])}


failures = []
checked = signatures = 0
deploy_skipped = set()
published = []
modules = list(reactor())
reactor_version = text(ET.parse('pom.xml').getroot(), 'version')

# Which declaration each module stages through: the first DECLARATIONS key on its parent chain.
parent_of, poms = {}, {}
for directory, pom in modules:
    root = ET.parse(pom).getroot()
    artifact = text(root, 'artifactId')
    parent_of[artifact] = text(root.find(NS + 'parent'), 'artifactId')
    poms[artifact] = pom


def governing(artifact):
    seen = set()
    while artifact is not None and artifact not in seen:
        if artifact in DECLARATIONS:
            return artifact
        seen.add(artifact)
        artifact = parent_of.get(artifact)
    return None


declarations = {}
for owner, profile_id in DECLARATIONS.items():
    plugin = central_plugin(poms[owner], profile_id) if owner in poms else None
    if plugin is None:
        failures.append(f'{owner}: no central-publishing-maven-plugin in profile `{profile_id}`')
    else:
        declarations[owner] = plugin
excluded_by = {owner: excluded(plugin) for owner, plugin in declarations.items()}
all_excluded = set().union(*excluded_by.values()) if excluded_by else set()

if all(owner in poms for owner in DECLARATIONS):
    (root_owner, root_profile), (app_owner, app_profile) = DECLARATIONS.items()
    root_plugins = publishing_plugins(poms[root_owner], root_profile)
    app_plugins = publishing_plugins(poms[app_owner], app_profile)
    for key in PUBLISHING:
        name = key[1]
        if key not in root_plugins or key not in app_plugins:
            missing = [p for p, found in ((root_profile, root_plugins), (app_profile, app_plugins))
                       if key not in found]
            failures.append(f'{name} is missing from profile(s) {missing}; both publishing profiles '
                            'declare all four publishing plugins')
            continue
        for field in ('version', 'extensions', 'executions', 'configuration'):
            a, b = root_plugins[key][field], app_plugins[key][field]
            if a != b:
                failures.append(f'{name} {field} differs between `{root_profile}` ({a}) and '
                                f'`{app_profile}` ({b})')

for directory, pom in modules:
    artifact, version, packaging, deploy_skip = coordinates(pom)
    if deploy_skip:
        deploy_skipped.add(artifact)
    if artifact in all_excluded or deploy_skip:
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

for owner, listed in excluded_by.items():
    governed_skips = {a for a in deploy_skipped if governing(a) == owner}
    if governed_skips != listed:
        failures.append(f'maven.deploy.skip and <excludeArtifacts> of the `{DECLARATIONS[owner]}` '
                        f'profile ({owner}) disagree about which of its modules ship: '
                        f'deploy.skip={sorted(governed_skips)} excludeArtifacts={sorted(listed)}')

# ADR-091: exeris-app-bom names a tested, released triple, in literals.
if 'exeris-app-bom' in poms:
    app = properties(poms['exeris-app-bom'])
    internal = properties(poms['exeris-tooling-bom']) if 'exeris-tooling-bom' in poms else {}
    app_bom_version = text(ET.parse(poms['exeris-app-bom']).getroot(), 'version')
    for key in ('exeris.tooling.version', 'exeris.kernel.version', 'exeris.sdk.version'):
        value = app.get(key)
        if not value:
            failures.append(f'exeris-app-bom: no <{key}>')
        elif value.endswith('-SNAPSHOT'):
            failures.append(f'exeris-app-bom: <{key}> is {value}; a release names final versions only')
    if app.get('exeris.tooling.version') != reactor_version:
        failures.append(f'exeris-app-bom: <exeris.tooling.version> is {app.get("exeris.tooling.version")}, '
                        f'the reactor version is {reactor_version}')
    if app_bom_version != reactor_version:
        failures.append(f'exeris-app-bom: its own <version> is {app_bom_version}, '
                        f'the reactor version is {reactor_version}')
    for key in ('exeris.kernel.version', 'exeris.sdk.version', 'jackson3.version'):
        if app.get(key) != internal.get(key):
            failures.append(f'<{key}> is {app.get(key)} in exeris-app-bom and {internal.get(key)} in '
                            'exeris-tooling-bom; the app BOM must name what this release was tested against')

if checked == 0:
    print('release-readiness: FAILED — checked 0 files; run `mvn -P release verify` first')
    sys.exit(1)

mode = ' (--unsigned: signatures and pom files NOT checked)' if UNSIGNED else ''
print(f'release-readiness{mode}: {len(published)} published coordinate(s), '
      f'{checked} required file(s), {signatures} signature(s) verified')
for artifact in published:
    print(f'    published: {artifact}')
for artifact in sorted(all_excluded | deploy_skipped):
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
