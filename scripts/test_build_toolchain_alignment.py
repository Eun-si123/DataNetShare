"""Focused regression tests for the Gradle/Kotlin declaration guard."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from check_build_toolchain_alignment import WORKFLOWS, verify


class BuildToolchainAlignmentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("android/gradle/wrapper/gradle-wrapper.properties",
                   r"distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip")
        self.write("android/build.gradle.kts",
                   'plugins { id("org.jetbrains.kotlin.android") version "1.9.22" apply false }')
        self.write("proxy-tests/build.gradle.kts",
                   'plugins { kotlin("jvm") version "1.9.22" }')
        for name in WORKFLOWS:
            self.write(f".github/workflows/{name}", "with:\n  gradle-version: '8.5'\n")

    def write(self, path: str, text: str):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def test_current_baseline(self):
        self.assertEqual([], verify(self.root))

    def test_wrapper_major_bump_without_ci_migration_fails(self):
        self.write("android/gradle/wrapper/gradle-wrapper.properties",
                   r"distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip")
        self.assertTrue(any("android-ci.yml" in error for error in verify(self.root)))

    def test_ci_version_drift_fails(self):
        self.write(".github/workflows/publish-preview.yml",
                   "with:\n  gradle-version: '9.8.2'\n")
        self.assertTrue(any("publish-preview.yml" in error for error in verify(self.root)))

    def test_kotlin_plugin_drift_fails(self):
        self.write("proxy-tests/build.gradle.kts",
                   'plugins { kotlin("jvm") version "2.4.21" }')
        self.assertTrue(any("Kotlin plugin mismatch" in error for error in verify(self.root)))

    def test_missing_ci_pin_fails(self):
        self.write(".github/workflows/codeql-advanced.yml", "jobs:\n  analyze: {}\n")
        self.assertTrue(any("no explicit gradle-version" in error for error in verify(self.root)))


if __name__ == "__main__":
    unittest.main()
