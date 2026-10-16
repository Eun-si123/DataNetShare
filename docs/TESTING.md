# Validation and device acceptance

Updated 2026-10-16 KST. This document separates source/loopback verification from actual Android device behavior.

## Field-loaded latency and safe transport counters (5.1-dev.9)

**User evidence:** Receiver via DataNetShare on a real two-device setup measured download **28.6 Mbps**, upload **17.1 Mbps**, unloaded RTT **38ms**, loaded download RTT **729ms**, loaded upload RTT **251ms**, jitter **7ms**. Sender UI showed 10 normally reclaimed idle TCP relays, no error. Receiver had no recorded errors. A prior sender-native 5G baseline to the VultKR Seoul server was 273.73/37.50 Mbps with 32ms unloaded and 88ms download-loaded RTT; the receiver's exact server for the newer measurement was not provided. These are **not controlled contemporaneous A/B tests**.

Loaded download RTT is ~19.2× unloaded; queue/backpressure is a plausible explanation, **not a localized diagnosis**. A blank error journal is expected for stalls, buffer queues, flow-control limits and successful cleanup; it does not establish healthy traffic.

**Instrumentation change:** receiver aggregate counters now include TCP retransmitted segments, number of nonblocking VPN TUN output wait polls, and cumulative time spent waiting for writable TUN output. They are available in live receiver stats and secret-free clipboard diagnostic text. Normal sender idle cleanup moved from the failure journal into regular sender client/byte activity metrics. This is not a performance fix or proof of native cellular/Wi-Fi Direct queueing.

**Next physical test:** same phone pair, same Ookla destination/server, three paired runs; measure native sender loaded RTT versus receiver VPN loaded RTT. Record before/after deltas for TCP retransmits, TUN waits and milliseconds, TUN drops, TCP attempts/connected, UDP attempts/associated, sender active client peak and client-limit errors. Compare plain SOCKS transfer without TUN and Wi-Fi Direct pure throughput separately to isolate the costly layer. Zero observed waits/retransmits does *not* eliminate cellular or Wi-Fi Direct radio bufferbloat, unscaled TCP window limitations, or userspace ACK overhead.

**Automated verification (2026-10-16):** [PR CI run 38851120303](https://github.com/Eun-si123/DataNetShare/actions/runs/38851120303) completed with **50 host regressions passed** and Android unit tests, lint and debug build successful. [Release workflow 38851291202](https://github.com/Eun-si123/DataNetShare/actions/runs/38851291202) published [v5.1-dev.9](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.9) with signed debug APK and `SHA256SUMS.txt`. APK `DataNetShare-5.1-dev.9-debug.apk`: 6,265,904 bytes, SHA-256 `08f8170f2a57fdfd65fd4794336bce367a37b7b29ea08b691f4326d90176cb38`. Real-device loaded-RTT and bottleneck improvements have **not** been verified.

## 5.1-dev.8 — one-button Android setup UI and sender stop race

**User UX finding:** The app previously showed three separate Start actions (sender Wi-Fi Direct, sender USB/Wi-Fi, receiver VPN) and an unrelated global Stop control, while both complete sender/receiver forms were visible on one long screen.

**Implementation:** [PR #9](https://github.com/Eun-si123/DataNetShare/pull/9) introduced a **single contextual Start / Cancel during startup / Stop when active** button. Share-vs-Receive is an explicit role selector; the unused role's form hides. Active sessions collapse setup and retain sender QR, throughput information and sanitized diagnostics. Sender USB/local Wi-Fi and default Android route are still available, with routing behind Advanced. Receiver QR is prominent; manual IPv4/port/password are expandable, not deleted. Nonsecret role/link preferences persist; secrets and VPN consent remain memory-only.

**Release-blocking concurrency bug found and fixed:** Original publish CI failed in an existing host test when sender Stop called Kotlin `toList()` on `ConcurrentHashMap.newKeySet()` while a socket worker was removing entries. In rare cases this caused `NoSuchElementException` and could affect the new unified Stop action. [PR #10](https://github.com/Eun-si123/DataNetShare/pull/10) replaces the racy size-based snapshots with weakly consistent CHM iteration over sockets and UDP associations. A new regression races Stop against concurrent client closures.

| Verification | Result | Evidence |
| --- | --- | --- |
| Host protocol, QR, connection-action and concurrent-stop regressions | **50 passed** | [Fix PR CI 38848934810](https://github.com/Eun-si123/DataNetShare/actions/runs/38848934810) |
| Android JVM tests, lint and debug APK | **Passed** | Same fix PR CI |
| Public debug v5.1-dev.8 APK, version/signature and digest | **Passed** | [Publish workflow 38849096346](https://github.com/Eun-si123/DataNetShare/actions/runs/38849096346) |
| Android physical-device role selection, rotation, QR scanner and cancel/stop | **Not yet tested** | Requires user acceptance |

Public APK: [DataNetShare-5.1-dev.8-debug.apk](https://github.com/Eun-si123/DataNetShare/releases/download/v5.1-dev.8/DataNetShare-5.1-dev.8-debug.apk), size 6,263,896 bytes, SHA-256 `6bbaae33dfc3981b28ec2265ea029dd393b1fbed26db45efa9232fd03369d718`.

**Device acceptance:** On two phones, choose Share, confirm Wi-Fi Direct starts, obtain QR, scan from Receive, grant VPN permission, then stop from the same primary button. Switch roles after stopping; test manual USB/local share, advanced route control, incorrect QR, denied permission, cancelling while Starting, rotation in both setup and connected modes, small-font/large-font accessibility, reconnect after stop, and that network diagnostics and secrets behave as before. The changes are UX/cleanup only; do not claim improved throughput.

## Same-server field performance baseline — 2026-10-16 KST

The user performed two browser Ookla Speedtest measurements using **VultKR, Seoul** (same destination provider), approximately minutes apart:

| Metric | Sender phone on native LG U+ 5G | Browser on recipient through DataNetShare 5.1-dev.7 | Tunnel / native |
| --- | ---: | ---: | ---: |
| Download | 273.73 Mbps | 35.80 Mbps | **13.1%** |
| Upload | 37.50 Mbps | 16.55 Mbps | **44.1%** |
| Unloaded latency | 32 ms | 88 ms | +56 ms |
| Download loaded latency | 88 ms | 152 ms | +64 ms |
| Upload loaded latency | 208 ms | 204 ms | -4 ms |

Native 5G public IPv4 and the tunneled external public IPv4 were different, but both were reported in LG U+ address space. The user independently verified the sender's cellular-only upstream; differing public IPv4 addresses alone do not prove an unintended Internet source. Different device, path, and measurement time mean these results are a *field baseline*, not a controlled benchmark or exact speed tax attributable to one subsystem.

The sender's prior screen showed **64/64 concurrent SOCKS clients and 200 new-client rejections** on 5.1-dev.6. After v5.1-dev.7 idle cleanup, a later screen showed **32 active clients and 57 reclaimed idle TCP connections** and the browser completed its speed test. This supports an improvement in proxy slot reclamation but **does not establish the cause of the remaining throughput deficit**.

### Likely bottlenecks to isolate (not yet proven)

1. Wi-Fi Direct actual peer PHY/link throughput and packet loss, including whether simultaneous Wi-Fi infrastructure connectivity reduces airtime.
2. SOCKS5 relay throughput over Wi-Fi Direct **without** the VPN/TCP emulation, measured in both directions against a controlled sender or LAN endpoint.
3. Receiver userspace `TcpFlow`/TUN overhead: MSS=1200 payload segments, per-segment IPv4/TCP allocation/checksums, per-flow synchronization/ACK/retransmission, blocking socket/reader/writer threads and a shared serialized TUN write lock; profile CPU, time in write/poll, memory, garbage collection and packets/s.
4. Cellular signal/thermal/power differences between direct and proxy measurements. Run same-server multiple A/B/A trials rather than comparing a single reading.
5. Browser connections/HTTP3 UDP and remote latency overhead; compare a plain reproducible TCP download to Ookla/FAST.com.

**Acceptance for optimization:** Keep the cellular sender and VultKR test location comparable, make at least 3 sequential paired samples, log throughput median/p95, unloaded and loaded RTT, concurrent SOCKS peak, limit rejections, `TCP attempts / connected`, `UDP attempts / associated`, `TUN drops`, device CPU/temperature, and heap pressure. Separate Wi-Fi Direct-only, authenticated SOCKS, and receiver VPN measurements before attributing the bottleneck. Do not raise the 128-client cap arbitrarily; on-device resource measurements govern tuning. Evaluate a mature tun2socks backend behind a feature flag if the Kotlin bridge remains a bottleneck.

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

## 5.1-dev.7 — sender 64-client exhaustion during speed-test attempts

**Grounded physical-device observation:** On the user's sender phone (with cellular-only upstream), the sender reached **64 concurrent connections**, reporting **200 proxy client-capacity rejections** in the new Network diagnostics. The receiving phone could sometimes use FAST.com at ~30 Mbps, but Ookla browser Speedtest still failed with a latency-server connection error. The user has an unlimited 5G plan, which alone does not specify the achievable speed through this app.

**Code-level causes identified:**

1. Sender SOCKS TCP relay removed read timeouts (`soTimeout=0`) and joined a relay direction without an idle expiry. Stale/half-closed keep-alive connections could occupy a semaphore permit indefinitely.
2. Receiver started a SOCKS5 connection immediately upon an app SYN, *before* the app's final ACK in the TCP handshake. Every retry/half-open session could occupy sender capacity.
3. Sender and receiver UDP association idle expiry was 120 seconds, allowing many abandoned UDP sessions to consume client slots during DNS/QUIC bursts.
4. Both sender and receiver had separate 64-concurrent TCP/client caps, with the sender's combined TCP/UDP cap directly observed exhausted.

**Changes in [PR #8](https://github.com/Eun-si123/DataNetShare/pull/8)**, merge SHA `4ca77c15f2c1176e6d164797ed95f46f408ff31d`:

- Wait for client TCP SYN-ACK acknowledgement before dialing sender SOCKS. SOCKS starts only for established client-side TCP.
- Reclaim sender TCP relays when **no bytes pass in either direction for 45 seconds**, using 2-second socket read timeouts and a shared monotonic timer; return the semaphore slot and increment a safe idle-reclamation diagnostic once per connection. Preserve ordinary TCP half-close/ongoing transfer behavior.
- Expire *idle* UDP associations after **30 seconds** on both sides, retaining active session updates.
- Raise bounded sender proxy cap 64→128 and receiver TCP flow cap 64→80. Receiver UDP remains capped at 32; neither thread count nor memory use is unrestricted.
- Add actual-loopback tests asserting idle slot reclamation (including ability to accept the next connection), preventing orphan SYN from opening a SOCKS socket, and preserving pre-existing large downloads and TCP FIN handling.

| Check | Result | Evidence |
| --- | --- | --- |
| Host protocol, IPv4, proxy, QR and relay lifetime regression tests | **45 passed** | [PR CI 38846131295](https://github.com/Eun-si123/DataNetShare/actions/runs/38846131295) |
| Android unit tests, lint and debug APK build | **Passed** | Same PR CI |
| Published 5.1-dev.7 release and package/signature verification | **Passed**, APK asset and SHA256SUMS verified | [Release workflow 38846357526](https://github.com/Eun-si123/DataNetShare/actions/runs/38846357526) |
| On-device 5.1-dev.7 post-fix FAST.com / Ookla latency / battery / RAM | **Not yet run** | Requires user retest |

Release APK [DataNetShare-5.1-dev.7-debug.apk](https://github.com/Eun-si123/DataNetShare/releases/download/v5.1-dev.7/DataNetShare-5.1-dev.7-debug.apk) is 6,251,788 bytes; SHA-256 `eac2b783ef14eaf1b28abb95b5d4a943ea965dfa3d9a5a954d83d50dc261f18d`.

**Next device test:** Both phones should run `5.1-dev.7` with a fresh sender session and QR. Record sender active connections and `Sender reached its concurrent-client limit` vs `Sender reclaimed idle TCP connections` after a browser/FAST.com/Ookla test, plus receiver `TCP attempts / connected`, `UDP attempts / associated`, `TUN drops` and diagnostic failure categories. Compare sender-native mobile data speed to receiver VPN speed under similar network conditions rather than assuming any specific minimum from the carrier plan.

**Still open:** A thread-per-relay Kotlin TCP stack is not a production-grade tun2socks. Evaluate a mature implementation behind a feature flag, with license/security/build provenance review and controlled measurements. Avoid claiming that 30 Mbps is fixed or that browser speed-test traffic is fully supported until real-device measurements confirm it.

## 5.1-dev.6 — observable failures during phone-to-phone speed tests

User-provided real-device evidence (2026-10-16): Chrome repeatedly showed `ERR_CONNECTION_RESET` opening FAST.com; after repeated reload, FAST.com opened but speed stayed `0` with a network/test-server error. Ookla browser Speedtest showed `LATENCY TEST ERROR`. The user confirmed the sending phone's **mobile-data public IP** differed from its simultaneously connected Wi-Fi's public IP, so the sender egress should be investigated as mobile data, not assumed to be the other Wi-Fi. Neither phone displayed the previously advertised `Last ... error` messages, which revealed a coverage and UX problem.

Source: [PR #7](https://github.com/Eun-si123/DataNetShare/pull/7), merged SHA `720786c5c4347181a8d12feba3522a99261f552a`.

- Add always-visible *Network diagnostics* directly below state and transfer statistics on both phones; copied diagnostic categories omit QR, passwords, network IPs and destinations. Session events are bounded/in-memory and cleared on a new share/receive session.
- `UdpFlow` no longer silently discards setup/read exceptions. TCP/UDP connection caps, sender client cap/upstream failures and TUN write congestion also produce events.
- Keep cumulative receiver progress counters `TCP attempts / connected`, `UDP attempts / associated` and `TUN drops`. Events being empty does **not** establish browser functionality.
- Regression tests check finite diagnostics storage, concurrent updates, reset, sanitized exceptions and existing socket/protocol behavior.

| Verification | Status | Evidence |
| --- | --- | --- |
| Host protocol / diagnostics regression tests | **43 passed** | [PR CI run 38842514485](https://github.com/Eun-si123/DataNetShare/actions/runs/38842514485) |
| Android JVM unit tests, lint and debug APK | **Passed** | Same PR CI |
| Release test/build/package/signature, v5.1-dev.6 | **Passed** | [Release run 38842727289](https://github.com/Eun-si123/DataNetShare/actions/runs/38842727289) |
| Physical-device FAST.com/Ookla test on v5.1-dev.6 | **Not yet performed** | Requires user retest |
| Packet-level tunnel progress and last UDP/TCP failure categories on actual phones | **Not yet independently captured** | App now displays these on the first screen |

Public debug APK: [v5.1-dev.6](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.6), 6,250,200 bytes, SHA-256 `d2f5e31293f100e9f6ba3c84fa97d6198e72ae06ad570ba5c26e9157112cf762`.

Next device acceptance: install current preview on both phones (development signing identities may change, requiring uninstall/reinstall), start fresh QR-paired session, open `https://1.1.1.1/cdn-cgi/trace` and a simple HTTPS page, then reproduce FAST.com/Speedtest. Copy the **safe diagnostics** from both phones and compare attempts/connected/associated/TUN drops before and after a failed test; do not share QR, raw credentials or entire connection screens. This implementation is still experimental and does not yet implement mature IPv6/ICMP/fragment/congestion algorithms.

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
