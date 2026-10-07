#!/usr/bin/env python3
"""Checks and assembles the migration fragments of an open release train.

A release train's steps are written one file per change under docs/migration/<version>/ and inlined
into docs/MIGRATION-0.x-to-1.0.md when that version is cut. docs/migration/README.md is the
contributor-facing description of the format; this script is its enforcement.

Usage:
  assemble-migration.py --check
      Validate every fragment directory and the train markers in MIGRATION. Exit 1 on a finding.
  assemble-migration.py --release X.Y.Z [--dry-run]
      Inline the fragments of X.Y.Z into its train, in filename order, and delete them. With
      --dry-run, print the assembled MIGRATION to stdout and change nothing.
  assemble-migration.py --pending X.Y.Z
      Exit 1 when X.Y.Z still has fragments or a train marker, i.e. it has not been assembled.

Fragment format, all of it enforced by --check:
  * the path is docs/migration/<X.Y.Z>/<area>-<NN>-<slug>.md, with <area> `java` or `ts` and <NN>
    two digits; the train is assembled in byte order of the filenames, so `java` steps precede `ts`
    steps and NN orders the steps within an area;
  * the file opens with a front matter block carrying the keys the organisation docs-lint requires
    and `type: migration-guide`, followed by one blank line; the assembler drops that block;
  * the body is exactly one `### ` section: it starts with the `### ` heading, holds no other
    heading of level 1 to 3 outside fenced code, and ends with a single newline;
  * a `ts` step's heading starts with "`exeris-codegen-ts`:", the prefix MIGRATION gives every
    TypeScript generator step, and a `java` step's heading does not;
  * a relative link is written relative to the fragment and must resolve; the assembler rewrites it
    relative to MIGRATION, so the link checker passes on both the fragment and the assembled file.

Stdlib only, and deterministic: the same tree assembles to the same bytes on every machine.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

MIGRATION = Path("docs/MIGRATION-0.x-to-1.0.md")
FRAGMENTS = Path("docs/migration")

VERSION = re.compile(r"^\d+\.\d+\.\d+$")
FILENAME = re.compile(r"^(java|ts)-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
TS_PREFIX = "### `exeris-codegen-ts`:"
REQUIRED_KEYS = ("title", "type", "visibility", "owning-repo", "last-verified")
FENCE = re.compile(r"^ {0,3}(`{3,}|~{3,})")
HEADING = re.compile(r"^ {0,3}(#{1,6})(?:[ \t]|$)")
INLINE_LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
REFERENCE_DEF = re.compile(r"^( {0,3}\[[^\]]+\]:[ \t]+)(\S+)(.*)$")
SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def begin_marker(version: str) -> str:
    return f"<!-- BEGIN migration-fragments {version} -->"


def end_marker(version: str) -> str:
    return f"<!-- END migration-fragments {version} -->"


MARKER = re.compile(r"^<!-- (BEGIN|END) migration-fragments (\S+) -->$", re.M)


@dataclass
class Fragment:
    path: Path
    heading: str
    body: str


@dataclass
class Findings:
    errors: list[str] = field(default_factory=list)

    def add(self, path, msg: str) -> None:
        self.errors.append(f"{path}: {msg}")


def outside_fences(lines: list[str]):
    """Yield (index, line) for every line that is not inside a fenced code block."""
    fence = None
    for i, line in enumerate(lines):
        m = FENCE.match(line)
        if fence is None:
            if m:
                fence = m.group(1)
                continue
            yield i, line
        elif m and m.group(1)[0] == fence[0] and len(m.group(1)) >= len(fence) \
                and line.strip() == m.group(1):
            fence = None


def split_front_matter(text: str):
    """(front matter lines, body) — None for the lines when the file opens with no closed block."""
    if not text.startswith("---\n"):
        return None, text
    end = text.find("\n---\n", 3)
    if end < 0:
        return None, text
    return text[4:end].split("\n"), text[end + len("\n---\n"):]


def link_targets(lines: list[str]):
    """Yield (index, match, kind) for each relative link target outside fenced code."""
    for i, line in outside_fences(lines):
        for m in INLINE_LINK.finditer(line):
            yield i, m, "inline"
        m = REFERENCE_DEF.match(line)
        if m:
            yield i, m, "reference"


def is_relative(target: str) -> bool:
    return not (target.startswith("#") or target.startswith("/") or SCHEME.match(target))


def read_fragment(root: Path, path: Path, findings: Findings) -> Fragment | None:
    rel = path.relative_to(root)
    m = FILENAME.match(path.name)
    if not m:
        findings.add(rel, "filename must be <area>-<NN>-<slug>.md: area `java` or `ts`, NN two "
                          "digits, slug lowercase kebab-case (e.g. java-06-some-change.md)")
        return None
    area = m.group(1)
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        findings.add(rel, "is not UTF-8")
        return None
    if "\r" in text:
        findings.add(rel, "carries a carriage return; fragments use LF line endings")
        return None
    front, body = split_front_matter(text)
    if front is None:
        findings.add(rel, "must open with a front matter block (--- … ---): the organisation "
                          "docs-lint requires one on every page, and the assembler drops it")
        return None
    keys = {}
    for line in front:
        km = re.match(r"^([A-Za-z][A-Za-z0-9-]*):\s*(.*)$", line)
        if km:
            keys[km.group(1)] = km.group(2).strip().strip("\"'")
    for key in REQUIRED_KEYS:
        if not keys.get(key):
            findings.add(rel, f"front matter key '{key}' is required")
    if keys.get("type") and keys["type"] != "migration-guide":
        findings.add(rel, f"front matter type must be migration-guide (got '{keys['type']}')")
    if not body.startswith("\n"):
        findings.add(rel, "the front matter block must be followed by one blank line")
        return None
    body = body[1:]
    if not body.endswith("\n") or body.endswith("\n\n"):
        findings.add(rel, "must end with exactly one newline")
        return None
    lines = body[:-1].split("\n")
    if not lines[0].startswith("### ") or not lines[0][4:].strip():
        findings.add(rel, "the line after the front matter's blank line must be the step's `### ` heading")
        return None
    for i, line in outside_fences(lines):
        h = HEADING.match(line)
        if i and h and len(h.group(1)) <= 3:
            findings.add(rel, f"line {i + 1}: a fragment holds one `###` section; `{line.strip()}` "
                              "opens another (use `####` for a sub-heading)")
    heading = lines[0]
    if area == "ts" and not heading.startswith(TS_PREFIX):
        findings.add(rel, f"a `ts` step's heading starts with \"{TS_PREFIX[4:]}\"")
    if area == "java" and heading.startswith(TS_PREFIX):
        findings.add(rel, "a heading naming `exeris-codegen-ts` is a `ts` step; rename the file "
                          "ts-<NN>-<slug>.md")
    for i, m, _ in link_targets(lines):
        target = m.group(2)
        if not is_relative(target):
            continue
        local = target.split("#", 1)[0]
        if local and not (path.parent / local).exists():
            findings.add(rel, f"line {i + 1}: relative link '{target}' does not resolve from the "
                              "fragment's own directory")
    return Fragment(path, heading, body)


def rewrite_links(fragment: Fragment, migration: Path) -> str:
    """The fragment body with every relative link re-expressed relative to MIGRATION."""
    lines = fragment.body[:-1].split("\n")
    base = migration.parent

    def rebase(target: str) -> str:
        if not is_relative(target):
            return target
        local, sep, anchor = target.partition("#")
        if not local:
            return target
        resolved = os.path.normpath(os.path.join(fragment.path.parent, local))
        moved = os.path.relpath(resolved, base).replace(os.sep, "/")
        if local.endswith("/") and not moved.endswith("/"):
            moved += "/"
        return moved + sep + anchor

    for i, line in outside_fences(list(lines)):
        line = INLINE_LINK.sub(lambda m: m.group(1) + rebase(m.group(2)) + m.group(3), line)
        m = REFERENCE_DEF.match(line)
        if m:
            line = m.group(1) + rebase(m.group(2)) + m.group(3)
        lines[i] = line
    return "\n".join(lines) + "\n"


def train_versions(migration_text: str, findings: Findings, rel) -> dict[str, tuple[int, int]]:
    """{version: (start, end)} of each well-formed marker block, as character offsets in MIGRATION."""
    blocks: dict[str, tuple[int, int]] = {}
    open_version, open_at = None, 0
    for m in MARKER.finditer(migration_text):
        kind, version = m.group(1), m.group(2)
        if kind == "BEGIN":
            if open_version is not None:
                findings.add(rel, f"marker block {open_version} is not closed before {version} opens")
            if version in blocks:
                findings.add(rel, f"two marker blocks for {version}")
            open_version, open_at = version, m.start()
        else:
            if version != open_version:
                findings.add(rel, f"END marker for {version} has no matching BEGIN")
                continue
            end = m.end()
            if migration_text[end:end + 1] == "\n":
                end += 1
            blocks[version] = (open_at, end)
            open_version = None
    if open_version is not None:
        findings.add(rel, f"marker block {open_version} is never closed")
    for version in blocks:
        if not VERSION.match(version):
            findings.add(rel, f"marker version '{version}' is not X.Y.Z")
    return blocks


def collect(root: Path, findings: Findings) -> dict[str, list[Fragment]]:
    """Every fragment directory, its fragments in assembly order."""
    trains: dict[str, list[Fragment]] = {}
    base = root / FRAGMENTS
    if not base.is_dir():
        return trains
    for entry in sorted(base.iterdir(), key=lambda p: p.name):
        rel = entry.relative_to(root)
        if entry.is_file():
            if entry.name != "README.md":
                findings.add(rel, "a fragment goes in its version's directory, "
                                  f"{FRAGMENTS}/<X.Y.Z>/")
            continue
        if not VERSION.match(entry.name):
            findings.add(rel, "a fragment directory is named for the release, X.Y.Z")
            continue
        fragments = []
        for path in sorted(entry.iterdir(), key=lambda p: p.name):
            if not path.is_file():
                findings.add(path.relative_to(root), "a fragment directory holds files only")
                continue
            fragment = read_fragment(root, path, findings)
            if fragment:
                fragments.append(fragment)
        seen: dict[str, Path] = {}
        for fragment in fragments:
            if fragment.heading in seen:
                findings.add(fragment.path.relative_to(root),
                             f"repeats the heading of {seen[fragment.heading].name}; two steps of one "
                             "train cannot share an anchor")
            seen.setdefault(fragment.heading, fragment.path)
        trains[entry.name] = fragments
    return trains


def check(root: Path) -> tuple[Findings, dict[str, list[Fragment]], str, dict[str, tuple[int, int]]]:
    findings = Findings()
    migration = root / MIGRATION
    text = migration.read_text(encoding="utf-8")
    blocks = train_versions(text, findings, MIGRATION)
    trains = collect(root, findings)
    for version, fragments in trains.items():
        if fragments and version not in blocks:
            findings.add(FRAGMENTS / version,
                         f"has fragments but {MIGRATION} has no `{begin_marker(version)}` block in "
                         "the train they belong to, so the cut would never assemble them")
    return findings, trains, text, blocks


def assemble(root: Path, version: str, trains, text: str, blocks) -> str:
    if version not in blocks:
        raise SystemExit(f"assemble-migration: {MIGRATION} has no marker block for {version}")
    fragments = trains.get(version, [])
    start, end = blocks[version]
    inlined = "\n".join(rewrite_links(f, root / MIGRATION) for f in fragments)
    return text[:start] + inlined + text[end:]


def report(findings: Findings) -> int:
    for error in findings.errors:
        print(f"::error::{error}", file=sys.stderr)
    if findings.errors:
        print(f"assemble-migration: FAILED — {len(findings.errors)} problem(s); the format is in "
              f"{FRAGMENTS}/README.md", file=sys.stderr)
        return 1
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="validate fragments and markers")
    mode.add_argument("--release", metavar="X.Y.Z", help="assemble this version's train")
    mode.add_argument("--pending", metavar="X.Y.Z", help="exit 1 if this version is unassembled")
    ap.add_argument("--dry-run", action="store_true", help="with --release: print, change nothing")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]),
                    help="repository root (default: the checkout this script is in)")
    a = ap.parse_args(argv)
    root = Path(a.root)
    if a.dry_run and not a.release:
        ap.error("--dry-run goes with --release")

    if a.pending:
        version = a.pending
        directory = root / FRAGMENTS / version
        left = sorted(p.name for p in directory.iterdir()) if directory.is_dir() else []
        marked = begin_marker(version) in (root / MIGRATION).read_text(encoding="utf-8")
        if left or marked:
            print(f"assemble-migration: {version} is not assembled — "
                  f"{len(left)} fragment(s) in {FRAGMENTS}/{version}/"
                  f"{', and its marker block is still in ' + str(MIGRATION) if marked else ''}. "
                  f"Run `tools/migration/assemble-migration.py --release {version}` in the release PR.")
            return 1
        print(f"assemble-migration: {version} is assembled")
        return 0

    findings, trains, text, blocks = check(root)
    if a.check:
        rc = report(findings)
        if rc == 0:
            counts = ", ".join(f"{v}: {len(f)}" for v, f in trains.items()) or "none"
            print(f"assemble-migration --check: OK — fragments per train: {counts}; "
                  f"open trains in MIGRATION: {', '.join(blocks) or 'none'}")
        return rc

    if VERSION.match(a.release) is None:
        ap.error(f"--release takes X.Y.Z (got '{a.release}')")
    if report(findings):
        return 1
    assembled = assemble(root, a.release, trains, text, blocks)
    if a.dry_run:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", newline="\n")
        sys.stdout.write(assembled)
        return 0
    (root / MIGRATION).write_text(assembled, encoding="utf-8", newline="\n")
    fragments = trains.get(a.release, [])
    for fragment in fragments:
        fragment.path.unlink()
    directory = root / FRAGMENTS / a.release
    if directory.is_dir() and not any(directory.iterdir()):
        directory.rmdir()
    print(f"assemble-migration: inlined {len(fragments)} fragment(s) into the {a.release} train of "
          f"{MIGRATION} and deleted them", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
