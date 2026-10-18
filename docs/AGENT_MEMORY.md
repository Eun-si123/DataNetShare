# DataNet Share — durable agent memory

This is short, **public-safe cross-session memory**, not a status page, duplicate release log, experiment archive or source of truth. On every new task, verify the live branch, implementation, tests and artifact; use the canonical docs for details.

## Store only durable and useful lessons

- decisions about trust/architecture, why they were made and where the current owner lives;
- a verified root cause or negative experiment likely to be rediscovered;
- an approach explicitly rejected or deferred, including the revisit condition;
- a cross-cutting invariant that an unrelated change could accidentally break;
- links to a canonical PR, commit, test or documentation source.

**Never store** SOCKS/QR/Wi-Fi credentials, raw QR payloads, signing material, tokens, private peer IPs, raw user logs, screenshots containing identifiers, machine-private topology, or confidential conversations. This is a public repository even though test devices belong to individuals.

## Initial verified guidance (2026-10-19; revalidate when using)

- The original upstream `datnet-share-v5-final.zip` is archived provenance. The maintained Kotlin project is `android/`, with `proxy-tests/` host protocol tests. See [Android build instructions](../android/README.md).
- The debug development package `com.datanet.share.dev` is separate from upstream; debug signing identities can differ between environments. A green build or published **prerelease** must not be described as production-certified or guaranteed upgrade-compatible. Check [build config](../android/app/build.gradle.kts) and the exact [release workflow](../.github/workflows/publish-preview.yml).
- The project separates SOCKS session credentials from Wi-Fi Direct link credentials. User-visible QR may contain both and remains replayable during its valid session; hiding a QR does not revoke access. Inspect current [security notes](SECURITY-REVIEW.md), `PairingPayload.kt`, `SenderService.kt` and persistence code before changing secrets handling.
- Receiver peer traffic must avoid tunneling its own upstream SOCKS sockets into its VPN. The earlier socket-preparation ordering failure led to **bind/preparation before `VpnService.protect()` before connect**. See [ReceiverSocketSetup](../android/app/src/main/java/com/datanet/share/receiver/ReceiverSocketSetup.kt) and its tests; device integration remains separate evidence.
- **Browser success != all-app compatibility.** The custom receiver's protocol support and Android local-only Wi-Fi capabilities constrain native apps; isolated speed-test numbers are not demonstrated improvements. Consult current [test evidence and pending A/B](TESTING.md), not a remembered Mbps figure.
- Android's Wi-Fi Direct group may remain after an app is uninstalled while actively sharing; cleanup can then require OS Wi-Fi toggling or reboot. Do not destroy unrelated groups to hide BUSY. Confirm current [host lifecycle](../android/app/src/main/java/com/datanet/share/sender/WifiDirectHost.kt) and [field-test guidance](TESTING.md).

These bullets describe **historical lessons and review pointers**, not an assertion that every current HEAD, OEM or carrier has passed a new test. Promote only newly confirmed enduring knowledge here; temporary work belongs to [WORK_HANDOFF.md](WORK_HANDOFF.md).
