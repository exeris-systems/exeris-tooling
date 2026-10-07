"""Tests for assemble-migration.py. Run: python3 -m unittest discover -s tools/migration -v"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "assemble_migration", Path(__file__).with_name("assemble-migration.py"))
am = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = am
_SPEC.loader.exec_module(am)

FRONT = ("---\ntitle: \"step\"\ntype: migration-guide\nvisibility: public\n"
         "owning-repo: exeris-tooling\nstatus: active\nlast-verified: 2026-10-07\n---\n\n")

MIGRATION = """# Migration

## 0.9.0 train

### Old step

Text.

---

## 0.10.0 train

Intro that stays.

<!-- BEGIN migration-fragments 0.10.0 -->
Pointer text the cut removes.
<!-- END migration-fragments 0.10.0 -->

---

## Reference
"""


class Repo:
    def __init__(self, tmp: str, migration: str = MIGRATION):
        self.root = Path(tmp)
        (self.root / "docs").mkdir()
        (self.root / "docs" / "diagnostics.md").write_text("# d\n")
        (self.root / am.MIGRATION).write_text(migration)
        self.train = self.root / "docs" / "migration" / "0.10.0"
        self.train.mkdir(parents=True)

    def fragment(self, name: str, body: str, front: str = FRONT) -> Path:
        path = self.train / name
        path.write_text(front + body)
        return path

    def run(self, *args: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = am.main([*args, "--root", str(self.root)])
        return rc, out.getvalue(), err.getvalue()


class CheckTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def assertFails(self, needle: str):
        rc, _, err = self.repo.run("--check")
        self.assertEqual(1, rc, err)
        self.assertIn(needle, err)

    def test_well_formed_fragments_pass(self):
        self.repo.fragment("java-01-a.md", "### A\n\nText.\n\n#### Sub\n\n```sh\n# a comment\n```\n")
        self.repo.fragment("ts-01-b.md", "### `exeris-codegen-ts`: B\n\nText.\n")
        rc, out, err = self.repo.run("--check")
        self.assertEqual(0, rc, err)
        self.assertIn("0.10.0: 2", out)

    def test_bad_filename(self):
        self.repo.fragment("my-step.md", "### A\n")
        self.assertFails("filename must be")

    def test_missing_front_matter(self):
        self.repo.fragment("java-01-a.md", "### A\n", front="")
        self.assertFails("front matter block")

    def test_missing_front_matter_key(self):
        self.repo.fragment("java-01-a.md", "### A\n", front=FRONT.replace("owning-repo: exeris-tooling\n", ""))
        self.assertFails("'owning-repo' is required")

    def test_wrong_type(self):
        self.repo.fragment("java-01-a.md", "### A\n", front=FRONT.replace("migration-guide", "reference"))
        self.assertFails("type must be migration-guide")

    def test_body_must_start_with_h3(self):
        self.repo.fragment("java-01-a.md", "Intro.\n\n### A\n")
        self.assertFails("`### ` heading")

    def test_second_section_is_refused(self):
        self.repo.fragment("java-01-a.md", "### A\n\nText.\n\n### B\n\nMore.\n")
        self.assertFails("opens another")

    def test_h2_is_refused(self):
        self.repo.fragment("java-01-a.md", "### A\n\n## Not here\n")
        self.assertFails("opens another")

    def test_heading_inside_fence_is_content(self):
        self.repo.fragment("java-01-a.md", "### A\n\n~~~yaml\n## a yaml comment\n~~~\n")
        rc, _, err = self.repo.run("--check")
        self.assertEqual(0, rc, err)

    def test_missing_final_newline(self):
        self.repo.fragment("java-01-a.md", "### A\n\nText.")
        self.assertFails("exactly one newline")

    def test_trailing_blank_line(self):
        self.repo.fragment("java-01-a.md", "### A\n\nText.\n\n")
        self.assertFails("exactly one newline")

    def test_ts_step_needs_prefix(self):
        self.repo.fragment("ts-01-a.md", "### A\n")
        self.assertFails("`ts` step's heading")

    def test_java_step_must_not_carry_ts_prefix(self):
        self.repo.fragment("java-01-a.md", "### `exeris-codegen-ts`: A\n")
        self.assertFails("is a `ts` step")

    def test_unresolved_relative_link(self):
        self.repo.fragment("java-01-a.md", "### A\n\nSee [d](diagnostics.md).\n")
        self.assertFails("does not resolve")

    def test_duplicate_heading(self):
        self.repo.fragment("java-01-a.md", "### Same\n")
        self.repo.fragment("java-02-b.md", "### Same\n")
        self.assertFails("repeats the heading")

    def test_fragments_without_a_marker(self):
        (self.repo.root / am.MIGRATION).write_text("# Migration\n")
        self.repo.fragment("java-01-a.md", "### A\n")
        self.assertFails("would never assemble")

    def test_stray_file_beside_the_version_directories(self):
        (self.repo.root / "docs" / "migration" / "java-01-a.md").write_text(FRONT + "### A\n")
        self.assertFails("goes in its version's directory")


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)
        self.repo.fragment("ts-01-c.md", "### `exeris-codegen-ts`: C\n\nT.\n")
        self.repo.fragment("java-02-b.md", "### B\n\nSee [d](../../diagnostics.md#x).\n")
        self.repo.fragment("java-01-a.md", "### A\n\nA.\n")

    def tearDown(self):
        self._tmp.cleanup()

    EXPECTED = MIGRATION.replace(
        "<!-- BEGIN migration-fragments 0.10.0 -->\nPointer text the cut removes.\n"
        "<!-- END migration-fragments 0.10.0 -->\n",
        "### A\n\nA.\n\n### B\n\nSee [d](diagnostics.md#x).\n\n### `exeris-codegen-ts`: C\n\nT.\n")

    def test_dry_run_prints_and_changes_nothing(self):
        rc, out, err = self.repo.run("--release", "0.10.0", "--dry-run")
        self.assertEqual(0, rc, err)
        self.assertEqual(self.EXPECTED, out)
        self.assertEqual(MIGRATION, (self.repo.root / am.MIGRATION).read_text())
        self.assertEqual(3, len(list(self.repo.train.iterdir())))

    def test_release_inlines_and_deletes(self):
        rc, _, err = self.repo.run("--release", "0.10.0")
        self.assertEqual(0, rc, err)
        self.assertEqual(self.EXPECTED, (self.repo.root / am.MIGRATION).read_text())
        self.assertFalse(self.repo.train.exists())
        rc, _, _ = self.repo.run("--check")
        self.assertEqual(0, rc)

    def test_release_is_deterministic(self):
        first = self.repo.run("--release", "0.10.0", "--dry-run")[1]
        second = self.repo.run("--release", "0.10.0", "--dry-run")[1]
        self.assertEqual(first, second)

    def test_release_refuses_a_malformed_fragment(self):
        self.repo.fragment("java-03-bad.md", "no heading\n")
        rc, _, _ = self.repo.run("--release", "0.10.0")
        self.assertEqual(1, rc)
        self.assertEqual(MIGRATION, (self.repo.root / am.MIGRATION).read_text())

    def test_pending_until_assembled(self):
        self.assertEqual(1, self.repo.run("--pending", "0.10.0")[0])
        self.repo.run("--release", "0.10.0")
        self.assertEqual(0, self.repo.run("--pending", "0.10.0")[0])


class RepositoryTest(unittest.TestCase):
    """The fragments in this checkout pass the check."""

    def test_checkout_passes(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = am.main(["--check"])
        self.assertEqual(0, rc, err.getvalue())


if __name__ == "__main__":
    unittest.main()
