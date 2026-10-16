# Validation and device acceptance

Updated 2026-10-16 KST. This document separates source/loopback verification from actual Android device behavior.

## Reproducible automated checks

```sh
# JDK 17 + Gradle 8.5, no Android SDK required:
gradle -p proxy-tests test --no-daemon

# JDK 17 + SDK 34/build-tools 34.0.0:
gradle -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

The host suite compiles the same production `Socks5Server`, SOCKS codec, IPv4 parser/encoder, TCP and UDP flow classes as the Android build. Test traffic is limited to loopback fixtures; it does not access a carrier, internet destination or another person's network.

Coverage includes:

- Missing authentication method and wrong password rejection.
- A 1 MiB+ TCP upload with a SHA-256 response after client half-close, verifying complete data in both directions.
- Concurrent UDP associations to one echo server, including a one-byte packet followed by 60,000-byte and 4096-byte payloads.
- Stop while a SOCKS handshake and authenticated control connection are idle; refusal of an unavailable upstream.
- IPv4/TCP/UDP length/offset/fragment checks, header checksum and IPv4 endpoint validation.
- IPv4, IPv6 and domain SOCKS datagram framing; malformed/reserved/fragmented frame rejection.
- Receiver first-UDP-packet delivery during association setup, two app source ports to a common destination, and subsequent 1400-byte response preservation.
- Receiver TCP SYN-ACK loss/retransmission, duplicate upload suppression, delayed/lost downstream packet recovery, sequence wrap and half-close response integrity.
- Out-of-order data/FIN does not advance the receive acknowledgement; closing UDP before initialization is idempotent.
- Active TCP upstream shutdown, rejection above the 64-client cap, and zero-window pause/resume without download overshoot.

## 5.1-dev.5: TCP MSS and VPN write backpressure

The user provided Android phone-to-phone screenshots with intermittent Chrome `ERR_CONNECTION_RESET` (FAST.com) and Ookla browser `LATENCY TEST ERROR`. The Ookla screen displayed an LG U+ public Internet egress. This is consistent with the configured mobile-data-only sender upstream, but does **not** by itself identify why the browser or remote speed test failed.

**Code deficiencies repaired in [PR #6](https://github.com/Eun-si123/DataNetShare/pull/6):**
- Previous receiver-generated TCP SYN-ACK packets carried **no MSS option**, allowing a low default MSS at the client. The receiver now advertises 1200 bytes and retransmits identical TCP options. A dedicated regression verifies IP/TCP checksums, header offsets and option bytes.
- Previous nonblocking TUN writes dropped any packet on a transient `EAGAIN`/`EINTR`. The implementation now polls for writability for up to three 20 ms waits and retries up to four times before dropping under sustained congestion. Fatal output errors remain fatal. Testing explicitly covers successful, congested, no-writability and thrown-error outcomes.

| Validation | Result | Evidence |
| --- | --- | --- |
| Host unit/protocol regression suite | **38 passed**, including MSS/checksum, SYN retransmission, TUN retry and existing 2 MiB continuous download | [PR CI #38840737641](https://github.com/Eun-si123/DataNetShare/actions/runs/38840737641) |
| Android unit tests, lint and APK build | **Passed** | Same PR CI Android job |
| Public preview v5.1-dev.5, checksum and signature verification | **Passed** | [Release CI #38840924324](https://github.com/Eun-si123/DataNetShare/actions/runs/38840924324) |
| Real-phone FAST.com/Speedtest after fixes | **Not run** | Requires user retest |
| Concurrent infrastructure Wi-Fi and Wi-Fi Direct + actual transport route | **Not independently verified** | Inspect sender route and/or external egress |

Public preview APK: [v5.1-dev.5](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.5), SHA-256 `8f87214cbbb90b8d532cbf429db362c309824aeb838f2ed84eb259872b77c9c2` (6,240,360 bytes).

**Next steps:** Run a known working IPv4 HTTPS page, then FAST.com and Ookla. Capture both phones' `Last upstream error` / `Last TCP error` and sender `Android default route` or `Mobile data` state, avoiding passwords and QR screenshots. The home-grown receiver remains limited by IPv4-only handling, missing fragment reassembly and a minimal TCP congestion/flow-control implementation. Evaluate a maintained production tun2socks engine (for example [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)) behind a feature flag, with reproducible Android JNI builds, upstream license/security review, actual-device throughput/CPU/battery comparison, preserved local-link socket protection and strict no-secret-logging controls. The current changes do **not** constitute full browser or speed-test compatibility.

## 5.1-dev.4: user-observed browser reset and upstream route diagnostics

A user reported that phone-to-phone setup on the 5.1-dev.3 preview progressed far enough to try a browser speed test, but Chrome showed `ERR_CONNECTION_RESET`. The sending phone was connected to another infrastructure Wi-Fi network. **This does not establish what Android selected for the sender's actual Internet egress**, nor whether the receiver's custom IPv4 TCP stack, a failed SOCKS CONNECT, or remote destination policy generated the reset.

Source fix: [PR #5](https://github.com/Eun-si123/DataNetShare/pull/5), merge `ff3ea875f2bc9593624f2b950e00b0ed456cada8`.

| Verification | Result | Evidence |
| --- | --- | --- |
| Host protocol regression suite | **30 tests passed**, including a 2 MiB sustained TCP download/ACK/FIN integrity regression and a missing-cellular SOCKS code 3 check | [PR CI 38839058413](https://github.com/Eun-si123/DataNetShare/actions/runs/38839058413) |
| Android unit tests, lint and debug APK build | **Passed** | Same PR CI (android job) |
| Published debug-signed prerelease | `v5.1-dev.4`, package `com.datanet.share.dev`, versionCode 34, checksummed and APK-signature verified by CI | [Release workflow 38839345417](https://github.com/Eun-si123/DataNetShare/actions/runs/38839345417) |
| Wi-Fi Direct concurrent infrastructure Wi-Fi and actual upstream egress | **Not independently verified** | OEM/Android dependent |
| Browser speed test and reported `ERR_CONNECTION_RESET` on this fix | **Not retested on device** | Requires same user's phones |

Debug preview APK: [v5.1-dev.4](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.4), SHA-256 `be7d1005a93f9ddccffe226eca30e40daaabdd662c8f226469b9aee0853b18d8`.

**Required next device check:** record sender `Mobile data` / `Waiting for mobile data` or `Android default route: ...` plus any `Last upstream error`, and receiver `Last TCP error`. Compare a simple HTTPS page to a speed-test page. With an infrastructure Wi-Fi sender, changing `Use Android default route` requires stopping/restarting the sender; scan its newly rotated session QR again. Verify actual IP-based egress and cellular accounting separately rather than inferring egress from connected Wi-Fi indicators.

## Latest verification: 5.1-dev.2 receiver hotfix

Source merged as `9de0f1b945b76d2c429797104156cbc5990440eb` ([PR #3](https://github.com/Eun-si123/DataNetShare/pull/3)).
A reported Android phone-to-phone receiver startup error on 5.1-dev.1 was traced to calling `VpnService.protect()` before an unbound TCP socket necessarily had a valid file descriptor. The fix binds/selects a local link before the protect call for the credential probe and subsequent TCP/UDP sockets.

| Check | Recorded result | Evidence |
| --- | --- | --- |
| Host socket/protocol regressions | 21 tests passed, including 4 bind-then-protect ordering and failure tests | [PR CI 38834796662](https://github.com/Eun-si123/DataNetShare/actions/runs/38834796662) |
| Android JVM unit tests, lint and debug APK compile | Successful | Same PR CI, android job |
| Post-merge release verification, APK package/version/signature | Successful (v5.1-dev.2, versionCode 32, `com.datanet.share.dev`) | [Release workflow 38835092927](https://github.com/Eun-si123/DataNetShare/actions/runs/38835092927) |
| Android physical-device `VpnService.protect()` and Wi-Fi Direct test | **Not run / user retest required** | The JVM tests mock protection and do not assert a real Android VPN protected FD |

Public pre-release: [v5.1-dev.2](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.2), `DataNetShare-5.1-dev.2-debug.apk` (5,885,575 bytes).
APK SHA-256: `eba71dfd99db258e886ed0bc4868360373a2106e10e582a263b8aef12619717b`.
The release includes `SHA256SUMS.txt`. Its CI debug signing identity may differ from prior APKs; update installs are not guaranteed.

This is evidence of successful automated regression tests, build and signing checks, **not evidence that the phone-to-phone bug has been fixed on every Android/OEM device**.

## Previous 5.1-dev evidence (historical)

Core relay suite first verified at `f9030e8b1ee705ce8bd7a9b9e8faf4113ddc9677`. Current APK/UI revision: `2dc1faae013024aabe9f905d941fb58468658cfc` ([PR #2](https://github.com/Eun-si123/DataNetShare/pull/2)); relay/test source is unchanged by the UI contrast fix.

| Check | Result | Evidence |
| --- | --- | --- |
| x86_64 / JDK 17 host socket and protocol suite | 17 tests, 0 failures/errors/skips | [GitHub Actions run 38827841966](https://github.com/Eun-si123/DataNetShare/actions/runs/38827841966), host-protocol-tests job |
| Android JVM unit tests (same flow/relay suite) | 17 tests, 0 failures/errors/skips | Same run, uploaded Android JUnit reports |
| Android lint | 0 errors/fatal issues, 22 warnings | Same run, uploaded lint XML/HTML |
| Android development APK compile | Success | Same run, datanetshare-debug-apk artifact |
| ARM64 Linux / S20 agent chroot / JDK 21 targeting JVM 17 | 17 tests, 0 failures/errors/skips on a fresh `--rerun-tasks` run; source head unchanged before/after | Owned S20 Linux loopback environment; not an Android-host/TUN or Wi-Fi Direct test |
| Delivered APK integrity | GitHub artifact ZIP digest matched; extracted APK hash matched an independently downloaded copy; APK v2 signature verified | See hashes below |

The 22 nonfatal lint warnings cover dependency/target age, UI text/styles, backup extraction guidance, an optional monochrome icon, safe inlined SDK constants/obsolete checks and deliberately session-owned wake locks. They do not constitute zero-warning or production certification. Dependency/target-SDK upgrades and OEM battery testing remain separate work.

Delivered APK: `DataNetShare-5.1-dev.apk` (5,884,499 bytes).

- APK SHA-256: `f7e80872d1484739510aabdcfaab974a0b132b5acd457417f8429bea24317ed5`
- Debug signing certificate SHA-256: `1b9313f5b193f2f9045681cfbb7515990cad1f78a0405e1e613fc7bc507b1e76`
- GitHub APK artifact ZIP SHA-256: `36e2cc438c095eaa3e58b731b24f90dd9f7f8dfc5f06dbf7647c68d2400ad730`

These hashes identify the earlier delivered APK, including the selected-interface contrast fix. It passed the full host/Android unit/lint/build workflow above; this final documentation update does not change that runtime. Always match a new APK to its own source revision and signature.

CI uploads the debug APK only after tests, lint and compilation succeed. Test reports are also uploaded on failure. A green build demonstrates those checks, not OEM Wi-Fi Direct compatibility, stable phone routing or carrier quota behavior.

## QR pairing acceptance (5.1-dev.3 — physical devices not yet tested)

The automated host suite now tests valid and invalid versioned QR payloads, UTF-8 and special-character round-trips, duplicate/malformed fields, and an actual ZXing **QR image encode→decode** cycle. Android CI additionally compiles and lint-checks the QR camera launcher and UI. These are not evidence that a real camera can focus or that Wi-Fi Direct/receiver VPN works on an actual handset.

| Scenario | Expected behavior | Device state |
| --- | --- | --- |
| Sender starts Wi-Fi Direct; presses Show connection QR | QR shows active session IPv4/port/password and Wi-Fi Direct credentials | Not run |
| Receiver scans QR in-app before joining sender network | Host/port/SOCKS password populated; SSID and Wi-Fi password shown; no automatic VPN | Not run |
| Receiver joins Wi-Fi via Android settings and returns | Scanned inputs survive switching between apps and Activity rotation (same process) | Not run |
| Invalid QR / malformed or duplicate fields / unknown scheme | Rejected without altering existing connection data | Not run |
| Sender stops and restarts; old QR rescanned | Old password rejected; newly displayed QR authenticates | Not run |
| Permission denial (camera, VPN) and process death | No crash or persistent saved password; request fails visibly | Not run |
| Android QR scanner / preview APK on target OEM | Camera opens and focus/resolution allow one-scan handoff | Not run |

**Important:** Session QR means the password rotates on stop/restart; it does not mean the QR can only be scanned once. Do not label this one-redemption until sender-side replay protection exists.

## Real-device acceptance matrix — outstanding

Record models, Android versions, tested commit, APK certificate and network setup for each row. Do not mark these passed based on a host test or build.

| Scenario | Expected observation | State |
| --- | --- | --- |
| Android 8–12 Wi-Fi Direct permission and Location-off path | Useful failure/retry; no leaked group or foreground service | Not run |
| Android 13+ nearby-device and notification permission denial | Only required path blocked; no crash | Not run |
| Wi-Fi Direct sender + regular Wi-Fi client | Reported SSID/password/IP connects; SOCKS authentication required | Not run |
| USB sender + computer | Selected USB IPv4 only listens; curl with remote DNS succeeds | Not run |
| Private Wi-Fi sender + authenticated client | Cellular egress and DNS are used by default, even if local Wi-Fi has no internet | Not run |
| Android receiver with wrong address/password | Probe fails before installing a VPN; other connectivity remains available | Not run |
| Android receiver with cellular still default | Proxy sockets reach sender over local link, avoiding recursive VPN routing | Not run |
| Android receiver v5.1-dev.2 socket protection on a real phone | Correctly prepares/protects sender probe, TCP and UDP while Wi-Fi remains local | Not run |
| Browser/download/upload/video and two apps using same UDP endpoint | Correct data hashes and app-specific responses | Not run |
| Stop/restart, fast mode switching, rotation and app reopening | One owner, current state and credentials, no stale callbacks | Not run |
| Screen off / 30+ minute session / OEM battery restrictions | Expected progress; disconnects visible; resources reclaimed after Stop | Not run |
| Sender loses cellular and regains it | New default-mode connections fail while absent; no default-route leakage | Not run |
| Sender's default-route option with VPN exclusions/on/off | Observed traffic path matches documented Android behavior; no assumed kill switch | Not run |
| Receiver VPN revoked / competing VPN / always-on lockdown | Service exits or fails visibly; document OS restrictions | Not run |
| IPv6-only destinations, ICMP, IP fragments, large UDP replies | Unsupported behavior remains explicit; no claim of full coverage | Not run |
| Carrier tethering quota/policy | No bypass claim unless separately measured for an actual plan | Not run |

For phone-to-phone acceptance, use two owned devices on an isolated local link. For privacy assertions, capture the actual egress path/DNS on equipment you control. Compare downloaded/uploaded hashes, rather than only confirming that a page opened.

## Remaining engineering limits

The receiver lacks full congestion control, SACK/window-scale negotiation, fragment reassembly and IPv6/ICMP transport. It uses bounded worker threads rather than a native event-driven netstack. No throughput, battery-life, thermal or long-duration reliability improvement is claimed without equivalent before/after measurements. Release signing and stable update delivery are not configured.
