#!/usr/bin/env python3
"""Verify that declared Gradle/Kotlin versions match what GitHub CI tests.

The Android wrapper distribution property is not an executable wrapper: the
repository does not currently ship gradlew or gradle-wrapper.jar. CI uses its
own Gradle installation. This check prevents an untested property-only upgrade
from presenting as a green Gradle upgrade.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

WORKFLOWS = (
    "android-ci.yml",
    "codeql-advanced.yml",
    "gradle-dependency-graph.yml",
    "publish-preview.yml",
    "jvm-portability.yml",
)


def verify(root: Path) -> list[str]:
    errors: list[str] = []
    wrapper = root / "android/gradle/wrapper/gradle-wrapper.properties"
    lines = [line for line in wrapper.read_text(encoding="utf-8").splitlines()
             if line.startswith("distributionUrl=")]
    if len(lines) != 1:
        return ["Expected exactly one Gradle distributionUrl property"]
    match = re.search(r"gradle-(\d+(?:\.\d+)+)-(?:bin|all)\.zip(?:\s*)$", lines[0])
    if match is None:
        return ["Unrecognized Gradle distributionUrl format"]
    distribution_version = match.group(1)

    for name in WORKFLOWS:
        text = (root / ".github/workflows" / name).read_text(encoding="utf-8")
        versions = re.findall(
            r"^\s*gradle-version:\s*['\"]?(\d+(?:\.\d+)+)",
            text,
            flags=re.MULTILINE,
        )
        if not versions:
            errors.append(f"{name}: no explicit gradle-version to verify")
        for version in versions:
            if version != distribution_version:
                errors.append(
                    f"{name}: CI tests Gradle {version}, but android wrapper "
                    f"properties declare {distribution_version}. Coordinate "
                    "the version update and run actual build tests."
                )

    # CodeQL init and analyze are one versioned pipeline. Dependabot updating
    # just one action has failed all three language uploads in this repository.
    codeql_source = (root / ".github/workflows/codeql-advanced.yml").read_text(encoding="utf-8")
    codeql_actions = re.findall(
        r"^\s*uses:\s*github/codeql-action/(init|analyze)@([0-9a-f]{40})(?:\s|$)",
        codeql_source,
        flags=re.MULTILINE,
    )
    codeql_pins = {name: [sha for action, sha in codeql_actions if action == name]
                   for name in ("init", "analyze")}
    if any(len(pins) != 1 for pins in codeql_pins.values()):
        errors.append("codeql-advanced.yml: expected exactly one SHA-pinned CodeQL init and analyze action")
    elif codeql_pins["init"][0] != codeql_pins["analyze"][0]:
        errors.append(
            "codeql-advanced.yml: CodeQL init/analyze commit pins must match; "
            "update them together and verify all three language uploads."
        )

    android_build = (root / "android/build.gradle.kts").read_text(encoding="utf-8")
    proxy_build = (root / "proxy-tests/build.gradle.kts").read_text(encoding="utf-8")
    android_kotlin = re.search(
        r'id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"([^"]+)"',
        android_build,
    )
    proxy_kotlin = re.search(
        r'kotlin\("jvm"\)\s+version\s+"([^"]+)"', proxy_build
    )
    if not android_kotlin or not proxy_kotlin:
        errors.append("Unable to determine both explicit Kotlin plugin versions")
    elif android_kotlin.group(1) != proxy_kotlin.group(1):
        errors.append(
            f"Kotlin plugin mismatch: android={android_kotlin.group(1)}, "
            f"proxy-tests={proxy_kotlin.group(1)}. Shared sources must be "
            "validated against the intended coordinated migration."
        )
    return errors


if __name__ == "__main__":
    failures = verify(Path(__file__).resolve().parents[1])
    if failures:
        for message in failures:
            print(f"::error::{message}", file=sys.stderr)
        sys.exit(1)
    print("PASS: Gradle wrapper declaration equals all CI-tested Gradle "
          "versions; Android and JVM Kotlin plugin versions agree.")
