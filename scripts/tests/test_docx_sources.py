"""Regression checks for document freshness after Git checkout or archive export."""

from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "docx-sources.py"


class DocumentFreshnessTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "documents"
        self.directory.mkdir()
        self.source = self.directory / "document.md"
        self.output = self.source.with_suffix(".docx")
        self.source.write_text("# Document\nOriginal text\n", encoding="utf-8")
        with zipfile.ZipFile(self.output, "w") as archive:
            archive.writestr("word/document.xml", "<document>Original text</document>")

    def run_command(self, mode, directory=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), mode, str(directory or self.directory)],
            capture_output=True, text=True,
        )

    def record(self):
        result = self.run_command("--record")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_checkout_timestamps_and_relocation_do_not_make_output_stale(self):
        self.record()
        os.utime(self.source, (2_000_000, 2_000_000))
        os.utime(self.output, (1_000_000, 1_000_000))
        exported = Path(self.temporary.name) / "exported"
        shutil.copytree(self.directory, exported)
        result = self.run_command("--check", exported)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_changed_source_fails_even_with_an_older_timestamp(self):
        self.record()
        self.source.write_text("# Document\nChanged text\n", encoding="utf-8")
        os.utime(self.source, (1, 1))
        result = self.run_command("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(self.source.name, result.stderr)

    def test_changed_word_output_requires_a_new_export(self):
        self.record()
        self.output.write_bytes(b"changed output")
        result = self.run_command("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(self.source.name, result.stderr)

    def test_missing_manifest_fails_with_export_instructions(self):
        result = self.run_command("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("export-submission-docx.sh", result.stderr)


if __name__ == "__main__":
    unittest.main()
