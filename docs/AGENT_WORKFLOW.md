# Agent workflow — DataNet Share

This guide extends the concise [root agent router](../AGENTS.md). It is deliberately smaller than the existing product, architecture, security and test manuals: each fact should have **one canonical owner**. Use only the task-relevant parts.

## 1. Establish the actual state

- Identify branch, commit, uncommitted changes (when a worktree exists), active PRs/issues and relevant CI/release status; do not mistake an old feature branch or archived upstream ZIP for main.
- Read `docs/WORK_HANDOFF.md` only for a **currently relevant** resumable task. Inspect actual source before trusting its summary.
- Consult `docs/CODEMAP.md` for navigation, then follow source imports/callers and nearby tests.
- For source changes, identify what is already implemented before adding a redundant API, policy, flag or network subsystem.
- Check potential conflicts with another agent's PR. Prefer extending an existing work item over duplicating it.

## 2. Select work by evidence and user value

Describe: **observed behavior → cause hypothesis → alternatives (including no change) → smallest falsifying test → expected benefit / failure criteria**. Prefer a reproduced bug, test gap, actual UX defect, unsafe default or measurable bottleneck over cosmetic churn.

For ambiguous designs compare compatibility, privacy, security, memory/thread/battery/data cost, failure recovery, reversibility and maintenance burden. If different approaches remain plausible, prototype or document the smallest comparison rather than choosing an irreversible architecture on intuition.

Useful neglected surfaces include sender/receiver startup and Stop races, Wi-Fi Direct BUSY/OS-managed groups, QR and network-liveness transitions, socket bind→protect ordering, DNS/UDP/app interoperability, flow capacity and retransmit backpressure, diagnostics privacy, and release/install identity. These are **inspection candidates**, not automatically confirmed defects.

## 3. DataNet Share-specific invariants

- **Sender:** do not replace explicit local-interface binding with an unbounded public listener. Preserve per-session SOCKS authentication, socket/UDP ownership, bounded resources, cleanup and user-selected upstream route semantics. A SOCKS connection is not a device.
- **Receiver:** protect and bind the SOCKS transport against recursive `VpnService` tunneling before connecting. Preserve TUN teardown on failure/Stop, correct flow isolation/ordering/checksums, bounded queues, cancellation and loss/retransmission handling. An Android local-only Wi-Fi `Network` is not automatically Internet-validated.
- **QR/secrets:** QR material is sensitive and replayable while its session remains valid. Do not log or persist session tokens; differentiate user-configured Wi-Fi link password from SOCKS credentials and preserve encrypted storage guarantees where implemented.
- **Incomplete protocols:** do not pretend a custom IPv4 TCP/UDP bridge implements native TCP congestion control, IPv6, ICMP or fragment reassembly just because browser video works. Check the current implementation before stating a particular missing feature.
- **Build/publishing:** debug `com.datanet.share.dev` is not a stable production signing identity; published preview assets require deliberate, source-bound validation and permission. Do not dispatch publishing or amend releases as part of routine maintenance.
- Preserve resource limits unless load/recovery tests support a carefully bounded change. Never improve measured speed by dropping integrity checks, hiding failures or weakening authentication.

## 4. Evidence ladder and commands

Match the claim to the test, keeping failures and skipped tests visible.

| Evidence | What it can demonstrate | What it cannot demonstrate |
| --- | --- | --- |
| Source review / static diff | Intended code path, invariants, plausible bugs | Runtime or device success |
| `gradle -p proxy-tests test --no-daemon` | Host protocol/packet/relay regressions | Android service, real link or VPN integration |
| `gradle -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon` | JVM tests, lint, debug APK compilation | OEM, carrier, two-phone or real speed compatibility |
| `android-ci.yml` | CI test/build outcome for an exact commit | Physical device acceptance or release readiness |
| Physical sender/receiver A/B | Scenario-specific compatibility, speed, latency, reconnect and resources | Universal improvement on all apps and networks |

Use **JDK 17 / installed Gradle 8.5**, SDK platform 34 / build-tools 34.0.0 according to [android/README.md](../android/README.md). A valid executable Gradle wrapper is **not** supplied by the archived ZIP. Do not fabricate passing output if the environment lacks Gradle, SDK, devices or permissions.

For changes to Android, validate the most relevant unit tests plus lint/build and host suite when packet/relay behavior is affected. For docs-only changes, check Markdown links, public-data safety, contradictions and readability; `android-ci.yml` does not run on a PR that changes only documentation. Device-only scenarios belong in [TESTING.md](TESTING.md), tagged pending with exact build, two devices, network mode, steps, expected result and safe redacted diagnostics. A single browser throughput measurement is a baseline observation, not proof of causal improvement.

## 5. Memory and checkpoints

- `AGENT_MEMORY.md`: concise **durable** design decisions, verified root causes, failed approaches worth remembering, and pointers to canonical evidence.
- `WORK_HANDOFF.md`: **temporary** objective/branch/HEAD, completed work, tests actually run, blockers and next safe step **only while a substantive task is active**.
- `TESTING.md`: test scenario and observed field evidence; `ARCHITECTURE.md`: system model; `README.md`: user-facing current preview; Issues/PRs: individual changes.

Never turn memory into a duplicate change log, raw command transcript or stale status page. At meaningful milestones or before risky operations, leave a small checkpoint when interruption would cause lost work. Re-read the live GitHub state on resumption. After completion, promote only enduring lessons and clear the transient handoff safely; do not silently erase someone else's active checkpoint.

If recurring mistakes can be prevented mechanically, propose a safer default, validator or test rather than adding endless prose instructions. Changing lasting governance, routing or documentation ownership requires a rationale and a small reviewable proposal.

## 6. PR and completion rules

Use a focused branch/PR with a baseline, scope, verification and regression notes. Avoid combining unrelated product changes with process documentation. Never force-push protected/shared history or merge/publish without the applicable policy and authority. Verify resulting file contents and changed paths before calling a task complete.

A useful completion note identifies exact files/commit/PR, what was **observed**, commands/tests that **actually ran**, failures/skips, non-regression evidence, and remaining user-device checks. If a task needs hardware or a consequential product/security choice, record the pending question and continue independent safe work. Do not invent work to force a commit.
