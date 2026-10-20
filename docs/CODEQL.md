# CodeQL coverage and build-mode evidence

The project has two separate Gradle roots: `android/` (Android app) and `proxy-tests/` (portable JVM protocol coverage). GitHub CodeQL **default** configuration failed its Java/Kotlin autobuild on 2026-10-21 because it could not select a single Gradle root; GitHub then fell back to Actions/Python-only scanning. See issue #33 and initial CodeQL setup run `39472911301`.

`.github/workflows/codeql-advanced.yml` supplies one matrix entry per language. `java-kotlin` uses **manual** compilation of both roots (JDK 17, Gradle 8.5, Android SDK 34); Python and GitHub Actions use `none`. Source compilation and CodeQL data extraction are different from executing tests, installing APKs, or proving Android physical behavior.

**Critical cutover:** default and advanced CodeQL setup cannot successfully upload side-by-side. Before treating advanced CodeQL alerts as evidence, disable the repo's *default* CodeQL setup via the GitHub code-scanning settings/API and run this workflow on the exact head. Preserve the existing Python and Actions coverage. Verify all three matrix jobs and uploaded analysis, including Kotlin. If a scan fails, fix the workflow and re-run; do not claim Kotlin security coverage from successful Gradle compilation alone.

Source PR checks/`inspect`, Android/JVM tests and release or field testing remain independent and mandatory as appropriate. Keep code scanning reporting read-only: no signing keys, production network probes, hidden privilege escalation or auto-fixes.
