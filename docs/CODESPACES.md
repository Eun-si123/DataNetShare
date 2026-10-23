# Develop DataNetShare in GitHub Codespaces (free personal allowance)

GitHub Codespaces can provide an on-demand Linux development environment with **JDK 17 and Gradle 8.5** for reviewing Android Kotlin sources and running host/JVM protocol tests. This container uses an official devcontainers Java feature; it does **not** install the Android SDK or pretend to validate actual phone behavior.

## Start

1. From the DataNetShare repository, choose **Code → Codespaces → Create codespace on main** (or select this devcontainer if prompted).
2. When the devcontainer finishes building, open the integrated terminal and run:
   ```bash
   gradle -p proxy-tests test --no-daemon
   python3 -m unittest discover -s scripts -p 'test_*.py' -q
   python3 scripts/check_build_toolchain_alignment.py
   ```
3. Review `docs/TESTING.md`, `AGENTS.md`, and the relevant code before modifying networking/security behavior.
4. Stop the codespace after each session, and delete obsolete codespaces once changes are pushed (export any uncommitted work first).

## Free quota and safety

A personal GitHub Free account provides **120 core-hours and 15 GB-month storage per month** for Codespaces. A 2-core codespace typically spends **2 core-hours per wall-clock hour**, so the included compute is not 120 elapsed hours on a 2-core machine. Persistent codespace storage also counts while stopped. Check account **Billing → Budgets and alerts** and enable *Stop usage when budget limit is reached* to prevent unexpected overage; don't increase budgets to try this environment.

This optional devcontainer has **no prebuilds**, no scheduled compute, no paid marketplace tools, no externally forwarded ports and no release credentials. Creating a codespace consumes the account's included compute/storage; simply storing `.devcontainer/devcontainer.json` doesn't consume Codespaces quota.

Running JVM protocol tests or lint on hosted Linux **does not** prove real Android Wi-Fi Direct, `VpnService`, cellular routing, OEM compatibility or speed. Use existing GitHub Actions Android CI for APK compilation and actual Android handsets for field evidence. Do not put QR secrets, VPN/SOCKS credentials or private device logs in workspace files or public PRs.
