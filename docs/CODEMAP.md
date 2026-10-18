# Code and evidence map

A starting index for a new agent; **real source, callers and tests are authoritative**. This map is not a copy of the architecture or product status and should change only when ownership/navigation changes.

| Question / subsystem | Primary code | Nearby verification |
| --- | --- | --- |
| App UI, mode selection, QR scanner | `android/app/src/main/java/com/datanet/share/MainActivity.kt`, `ConnectionControls.kt`, `ConnectionFormState.kt`, `RotatableQrCaptureActivity.kt` | `android/app/src/test/java/com/datanet/share/ConnectionControlsTest.kt`; QR tests under `common/` |
| Session state, diagnostics and QR payload | `android/app/src/main/java/com/datanet/share/common/SessionStore.kt`, `DiagnosticJournal.kt`, `PairingPayload.kt` | `android/app/src/test/java/com/datanet/share/common/` |
| Sender service, local proxy and upstream network | `android/app/src/main/java/com/datanet/share/sender/SenderService.kt`, `Socks5Server.kt`, `AndroidUpstream.kt` | `android/app/src/test/java/com/datanet/share/sender/`; `proxy-tests/` |
| Wi-Fi Direct group and peer usage | `android/app/src/main/java/com/datanet/share/sender/WifiDirectHost.kt`, `WifiDirectProfile.kt`, `PeerUsageLedger.kt` | `android/app/src/test/java/com/datanet/share/sender/`; physical OEM scenarios in `TESTING.md` |
| Receiver foreground VPN and sender transport | `android/app/src/main/java/com/datanet/share/receiver/ReceiverVpnService.kt`, `ReceiverLink.kt`, `ReceiverSocketSetup.kt` | `ReceiverSocketSetupTest.kt`, `SenderHealthProbeTest.kt`, real device VPN teardown |
| TUN bridge, TCP/UDP flow handling | `android/app/src/main/java/com/datanet/share/receiver/Tun2Socks.kt`, `TcpFlow.kt`, `UdpFlow.kt`, `TcpCapacityPolicy.kt` | `android/app/src/test/java/com/datanet/share/receiver/`, `proxy-tests/`, real two-phone load tests |
| IPv4 packet and SOCKS5 codecs | `android/app/src/main/java/com/datanet/share/common/Ipv4Packet.kt`, `Socks5.kt` | `ProtocolTest.kt`, `PacketChecksumEquivalenceTest.kt`, `TcpSynOptionsTest.kt` |
| Network joining, QR link readiness and credential policy | `android/app/src/main/java/com/datanet/share/common/PeerWifiConnector.kt`, `QrPeerProbePolicy.kt`, `ProxyPasswordPolicy.kt`, `WifiPasswordVault.kt` | Corresponding `common/` tests; actual Android Wi-Fi/Keystore scenarios |
| Manifest, build, release identity | `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`, `.github/workflows/` | CI logs plus staged artifact and device installation evidence |

**Do not build from** the root `datnet-share-v5-final.zip`; it is an archived upstream artifact. The maintained source and host test harness are `android/` and `proxy-tests/`.

## Where facts belong

- What a user can currently download/use: [README](../README.md), version/config in the actual build, exact release asset.
- Current design model: [ARCHITECTURE](ARCHITECTURE.md), verified against changed Kotlin source.
- Verified tests and still-pending scenarios: [TESTING](TESTING.md), CI for the exact commit.
- Privacy and threat boundary: [SECURITY-REVIEW](SECURITY-REVIEW.md).
- Stable agent lessons: [AGENT_MEMORY](AGENT_MEMORY.md); transient resumption: [WORK_HANDOFF](WORK_HANDOFF.md).

Be alert to **stale historical numbers**: an older document may describe an old queue, protocol option, connection limit, SDK/version, or unreleased feature. When writing a current claim, check the active code, test and release-contract evidence rather than treating that snapshot as live.
