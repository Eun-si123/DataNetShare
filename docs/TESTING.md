# Validation and device acceptance

Updated 2026-10-18 KST. This document separates source/loopback verification from actual Android device behavior.

## 5.1-dev.21 — negotiated TCP Window Scaling and protocol diagnostic counters

2026-10-18 field baseline: browser speed-test screen shows 45.60 Mbps download, 30.38 Mbps upload, and 160 ms ping. It is a single result, not evidence that a change has improved throughput. YouTube/web browsing works, while native speed-test apps still fail ISP/server discovery.

Receiver TCP now negotiates RFC 7323 Window Scale only if incoming SYN offers valid WSopt. It sends aligned MSS1400 + shift=2 SYN-ACK options, and applies the peer shift to subsequent ACK windows. SYN window remains unscaled. Receive payload buffer is capped at 262140 bytes per scaled flow (at most ~28 MiB queued uploads across 112 TCP flows, excluding other retransmission and thread buffers). Malformed or absent WSopt falls back to the old 65535-byte window and MSS-only handshake. Tests cover options/checksums, retransmits, memory/window limits, legacy behavior and sustained TCP transfers. This is not a native TCP engine and may not substantially improve Internet speed.

The receiver now also reports established flows with negotiated WS and counters of unsupported IPv4 ICMP and IPv6 packets seen on its TUN interface. This is diagnostic only: no ICMP/IPv6 connectivity was added and zero counts do not prove other apps work. It does not record destination addresses or passwords.

A/B acceptance: test dev.20 vs dev.21 three or more times per version with same phones/location/server; record throughput, idle/loaded latency, retransmits, TUN drops/waits, WS count and ignored-protocol counts. Check real native Speedtest ISP/server discovery separately. Monitor CPU, memory, temperatures and reconnect/fallback; roll back if stability declines. Ping from the Internet speed test is not necessarily local peer TCP RTT. Other candidates: mature tun2socks backend and DNS/UDP/IPv6 compatibility, after controlled evidence.

## 5.1-dev.20 — sender session peer IP statistics, before transport-engine redesign

**User field evidence (2026-10-18):** local-only QR Wi-Fi and normal Wi-Fi icon now work; web-based speed test showed approximately 41.7 Mbps download and 37.34 Mbps upload in a blurred image. Android sender reported 106 simultaneous SOCKS connections, 27 idle TCP cleanup events, ten upstream connect failures (latest SocketTimeoutException), and four sender client-limit events. YouTube and web browsing work while native Speedtest/Speednet-style apps cannot discover ISP or servers. This is **partial application compatibility**; a single browser speed result does not demonstrate full IPv6/UDP/ICMP support or remove the ~40Mbps throughput ceiling.

A SOCKS *connection* is not an attached *device*: one receiver can create many TCP streams and UDP associations. Increasing the sender's maxClients from 128 without reducing per-flow thread/socket cost is not a defensible throughput fix. For diagnosis, the sender now maintains **bounded, session-only per-remote-IP authenticated SOCKS usage**: active connection count, payload RX (client uploads toward Internet) and payload TX (client downloads from Internet). Counters include TCP relay payload and successfully relayed UDP payload, not SOCKS handshake, radio/MAC overhead or retransmissions. Repeated remote-IP connections are aggregated, but MAC/device-model mapping is unavailable from the proxy and multiple apps on one phone share one row. Show this ONLY on the sender display; never include the IP list in the privacy-scrubbed Copy Diagnostics action. Support at most 32 IP entries to bound memory. Reset the list on session Stop, do not write it to disk, and throttle UI snapshots to ~1Hz.

**On-phone validation:** run one receiver while generating several browser and video flows; confirm one IP row has multiple SOCKS connections. Run a second physical receiver (if supported), verify separate IP rows and non-mixed counters. Repeat a large TCP download and UDP (DNS/QUIC) traffic, compare per-IP totals against global sender RX/TX (all traffic from untracked clients or startup timing can differ); verify Stop clears rows and restart begins from zero. Check CPU and RAM at high concurrency.

**Remaining real issues:** Native Speedtest likely has different network validation/DNS/UDP/IPv6/ICMP expectations than browser and YouTube; identify with system logs and controlled protocol tests, not app-name guesses. Next structural performance experiment should compare the existing Kotlin userspace TCP bridge and a mature Android tun2socks engine behind an explicit feature flag, with packet protection, ABI/signing, connection lifetime, loaded RTT, CPU, and battery validation. Do not remove safety limits until load-tested. This release does not claim to fix the Speedtest native app or improve Mbps.

## 5.1-dev.19 — local-only QR Wi-Fi and apps that report "no Internet"

User's non-Samsung receiver now successfully joins QR Wi-Fi and loads browser pages through the DataNet Share VPN, but Android shows an exclamation/question mark and "available through DataNet Share Dev"; Ookla Speedtest fails to find ISP or servers. This is a **partial-success** report, not proof of end-to-end connectivity for all apps.

- QR connection uses Android WifiNetworkSpecifier, which by design can create a local-only Wi-Fi network without NET_CAPABILITY_INTERNET or VALIDATED. Android may not show a normal Internet Wi-Fi symbol. Do not claim to "repair" this underlying Wi-Fi by adding Internet capability, modifying system network validation or forcing process-wide routing.
- Receiver's SOCKS sockets are explicitly bound to the local sender network. Before establishing the VPN, Builder.setUnderlyingNetworks() now declares that network, and the VPN service refreshes it after a successful link change. Android's VPN API explicitly documents this for bound underlying socket traffic and UI status; it **does not certify Internet validation**.
- Safe receiver diagnostics now display available Android VPN network and selected underlying network INTERNET/VALIDATED/VPN/WIFI flags, plus whether the QR-requested Wi-Fi remains active. No addresses, network names, credentials, or raw HTTP queries are reported. Sampling is capped at every five seconds.
- Test on the same phone: QR join, browser HTTPS site, Speedtest native app ISP/server discovery, web speed test, then copy both sender and receiver *sanitized* diagnostics. If VPN network is reported unvalidated, that is a strong candidate for app refusal; if validated but Speedtest fails, check DNS/UDP, IPv6, ICMP/protocol support. Neither diagnosis alone establishes which check Ookla internally uses.
- When uninstalling with an active sender Wi-Fi Direct group, the app may lose the ability to remove that P2P group and the OS can return BUSY on reinstall. Always stop sharing before uninstall. If already orphaned, toggle sender Wi-Fi or reboot. Do not forcibly delete another app's group.
- Current Kotlin receiver remains IPv4 TCP/UDP only; IPv6, ICMP, fragment reassembly and mature congestion control remain unsupported. Throughput and Speedtest compatibility are unverified; do not promise a change in the Wi-Fi icon or server discovery.

## v5.1-dev.18 — QR local Wi-Fi sender-reachability readiness

Some Samsung Wi-Fi Settings pages show the same Wi-Fi Direct SSID both as an app-requested local-only connection and as a saved network with automatic selection disabled due to no Internet. These separate entries do not themselves establish a bug. The user experienced an EHOSTUNREACH SOCKS probe after the QR-requested Wi-Fi got IPv4, while manual connection later worked.

Receiver QR auto-join now waits until a TCP socket created by the QR-requested Android Network's socketFactory connects to the current sender's local SOCKS port. Merely receiving IPv4 no longer triggers VPN startup. This probe makes up to five bounded attempts; it sends no SOCKS credentials and does not bind the whole process to the local-only network. Cancel/disconnect invalidates the probe. On failure, the app instructs the user to confirm a fresh QR/SSID and use manual Wi-Fi join. App's IPv4+socket checks and Android VPN auth remain distinct. Wi-Fi Direct BUSY and 30–40Mbps throughput remain separate outstanding issues.

Real device acceptance: fresh QR and normal Android approval, Wi-Fi denial/unavailable, sender absent, sender port delayed, lost QR network during probe, manual fallback, Android Wi-Fi duplicate network presentation and VPN still reachable after launch. This patch has not yet been device validated.

## 5.1-dev.17 — Receiver capacity and QR peer-route readiness

User's v5.1-dev.16 receiver diagnostics: 246 TCP attempts/222 connected, 106 TCP admission rejections at the fixed 80-flow limit, 17 TCP timeouts, 1 TCP upload error, 8 UDP packet queue overflows, 26 retransmissions, zero TUN drops and zero TUN write waits. Sender: 24 clients, 22 idle reclaimed, no recorded internal failure. User's Speedtest showed a **best-record** 42.2 Mbps down/19.5 up, not a controlled single-run result.

- Bounded shared TCP+UDP budget 120 (sender supports 128, reserve 8 for probes), max 112 TCP, max 40 UDP. Under saturation, reclaim only half-open SYNs older than 6s or established flows quiet for 20s with no unacknowledged or queued data. Background idle timeout 90s established / 10s half-open; no forced active-download eviction. UDP queue 32 to 64 packets per association. Surface new idle TCP reclaim and UDP queue overflow metrics.
- QR-requested Wi-Fi may receive IPv4 before the group owner is actually routable. Retry only route errors (EHOSTUNREACH/ENETUNREACH) up to three short attempts when app-requested peer Wi-Fi exists; never retry password, VPN-protect or unrelated errors. If it fails, show the sender SSID/QR matching and manual-Wi-Fi guidance. Different Wi-Fi names visible in screenshots at different times do not prove they were from the same session.
- Same-device/same-server A/B against v5.1-dev.16: repeat at least 3 runs and record down/up, unloaded and loaded ping, TCP admissions and idle reclamations, UDP queue overflows, TCP retransmits, TUN waits/drops and device CPU/temp. Test QR system approval, mismatched/expired QR, manual join, system VPN revocation, sender stop during retry. This does NOT prove the existing 30–40Mbps download bottleneck resolved, and increasing active flows may raise memory/thread usage.

## 5.1-dev.16 — experimental receiver packet overhead reduction

**This is a low-risk CPU/packet-overhead experiment, NOT a verified throughput fix.** Preserve 5.1-dev.15 as the field baseline.

- Receiver TCP bridge raises SYN-ACK MSS from 1200 to 1400 (VPN TUN MTU remains 1500). Generated packets are still at most 1440 IPv4+TCP bytes for option-free data. This can reduce TCP packet processing count at equal payload throughput; Android/OEM acceptance and real performance are unverified.
- Packet encoder no longer allocates a pseudoheader copy or an IPv4 header copy per TCP packet solely for checksum generation. The real IPv4/TCP checksums must be identical. Independent checksum regressions cover odd/even payload sizes, SYN-ACK options and UDP header compatibility.
- **Unchanged:** 65,535-byte unscaled TCP window, Kotlin socket and per-flow thread model, full-per-packet payload copies, serialized VPN writes, existing congestion/loss recovery, Wi-Fi Direct band. These remain bottleneck candidates.
- The receiver now parses SYN TCP MSS options and bounds the 1400-byte send size to the peer's advertised MSS (RFC fallback 536 when absent). Verify unusual low-MTU/nested-VPN behavior on real handsets.
- Same-phone-pair A/B testing v5.1-dev.15 vs dev.16: identical sender 5G signal and receiver location; VultKR Seoul same Speedtest server and 3+ repeated measurements of down/up speeds, unloaded/loaded RTT, TCP retransmissions, TUN waits/drops, sender active clients, Android CPU/temperature and battery. Compare medians and note server/transport selection. A code-compile pass is not an objective speed result.
- Next larger opportunity: mature tun2socks behind a feature flag (e.g. upstream MIT-licensed hev-socks5-tunnel) with Android JNI reproducible build/ABI checks and socket protection/route safety; compare side-by-side with the Kotlin backend. Do not remove Kotlin fallback until measured.

## 5.1-dev.15 candidate — QR Wi-Fi request and route fallback

**Development candidate: Android hardware acceptance remains unverified.**

- Android 10+: after QR scan optionally request an OS-approved Wi-Fi connection to the QR's exact SSID/password. Wait for IPv4 provisioning before starting the receiver VPN consent flow. Manual Wi-Fi settings remain available on unsupported devices or when permissions/request fail. No system approval bypass.
- Sender Advanced settings: optional SOCKS5 password 12–128 non-space printable ASCII, held only in memory for the session. Blank = new random secret. If the same custom secret is reused, previously issued QR codes can remain valid, unlike auto-generated secrets.
- Sender optional cellular-first route policy: on missing/unvalidated cellular, new flows choose an Android-validated infrastructure Wi-Fi route. When cellular is Android-validated again, new flows prefer cellular. Existing TCP/UDP sockets are not migrated. Wi-Fi Direct peer-only link is not a validated Internet fallback.
- Receiver optional loss behavior: after three consecutive sender probe failures, close VPN and release the QR-requested Wi-Fi link, allowing Android to select mobile data or an already saved Wi-Fi connection. Arbitrary nearby APs are not joined. If the sender is still reachable but has lost upstream Internet, this is not detected by its liveness probe.
- Changes do not claim performance improvement for the previous ~30 Mbps bottleneck.

**Physical acceptance checklist:** QR request grant/deny/cancel and rotation; local IPv4/SOCKS reachability; coexistence with regular Wi-Fi; cellular → validated Wi-Fi → cellular route using observed public egress and network state; Wi-Fi Direct only (no invalid Internet fallback); receiver VPN stop/local Wi-Fi release after sender loss; credentials/QR lifetime; repeat same-server loaded-latency and throughput measurements. Test user-disabled Android Auto-rotate, VPN Always-on/Lockdown, and different Android OEMs.

## 5.1-dev.14 — user-controlled QR scanner rotation

A user clarified that the QR scanner must not be **portrait locked** either. The requirement is to avoid forcing either orientation while allowing the user to freely rotate the camera screen.

- Replace the dedicated `PortraitQrCaptureActivity` with a non-exported `RotatableQrCaptureActivity`, declared with Android `screenOrientation="fullUser"`. ZXing `ScanOptions` now uses `setOrientationLocked(false)`; no app-enforced portrait or landscape orientation.
- Respect the device's system **Auto-rotate** preference: when enabled, rotating the phone should switch QR scanning between portrait and landscape; when disabled, opening QR scanning should respect the user-locked orientation and not suddenly switch to landscape.
- Main sender QR output, network pairing payload, saved Wi-Fi Direct password and receiver form handling are unchanged.

| Scenario | Expected | Current verification |
| --- | --- | --- |
| Auto-rotate off, phone portrait, tap Scan QR | Camera opens portrait, no forced landscape | Real handset pending |
| Auto-rotate on, phone portrait, tap Scan QR | Camera opens portrait | Real handset pending |
| Auto-rotate on, phone turned landscape during scan | Camera rotates landscape and scanner continues operating | Real handset pending |
| Auto-rotate on, turn back portrait during scan | Camera returns to portrait and still scans | Real handset pending |
| Scan or cancel in either orientation | Receiver form restored without credentials lost or unintended role changes | Real handset pending |
| Android manifest and Kotlin scanner API build | App compiles and manifest validates | CI |

The previous 5.1-dev.13 portrait lock remains historical release behavior; do not change the immutable published APK. The user-requested auto-rotation correction is versioned in 5.1-dev.14.

## 5.1-dev.13 — persistent Wi-Fi passphrase and portrait-only QR scanner

Changes to sender Wi-Fi Direct advanced settings:

- Custom Wi-Fi Direct link password is now **remembered automatically** when sharing is started with a valid custom profile. At rest it is AES-256-GCM ciphertext in app-private SharedPreferences, encrypted using a non-exportable Android Keystore key; no plaintext fallback. User can edit it after stopping sharing and starting a new Wi-Fi Direct group. SOCKS5 password still rotates for every session.
- Empty Wi-Fi password on Start explicitly deletes the stored secret; default-group creation and optional SSID/band settings continue to generate their own randomized Wi-Fi password.
- A memory-only ViewModel differentiates field cleared on rotation from a first app launch that should restore the saved password. Existing receiver QR/temporary proxy credentials are not persisted. If Android Keystore decryption or storage fails, show a meaningful, non-secret error; never report successful persistence if it failed.
- Receiver QR scanning uses an explicit `PortraitQrCaptureActivity : CaptureActivity`, registered non-exported with manifest `screenOrientation=portrait` and `ScanOptions.setOrientationLocked(true)`; no more default landscape scanner.

| Verification | Expected behavior | Status |
| --- | --- | --- |
| AES-GCM roundtrip, random IVs, tamper and wrong-key rejection, passphrase length constraints | JVM cryptography regression tests pass | Automated test |
| Android SDK/JVM tests, Lint and APK build | No compile or manifest errors | CI validation |
| Save custom Wi-Fi password → force-stop app → reopen sender advanced settings | Custom password is restored; SSID/band retained | **Physical Android Keystore acceptance pending** |
| Change remembered password → Stop/restart sender → re-pair with fresh QR | New Wi-Fi passphrase used; previous code/password not reused | **Physical device pending** |
| Clear Wi-Fi password → Start → restart app | Former saved password stays cleared; app uses automatic generation | **Physical device pending** |
| Open QR scanner on a portrait Android phone → scan or cancel | Scanner stays portrait; returns upright to receiver controls | **Physical device pending** |
| Android KeyStore unavailable/corrupt ciphertext | Explains saved password cannot be recovered, asks to re-enter; no plaintext fallback | **Physical device pending** |
| App uninstall/reinstall with changed debug signing key | Saved password/settings erased; no unexpected reuse | **Physical device pending** |

This feature does not implement live rekeying of a currently running Wi-Fi Direct group, Android hotspot/SoftAP settings, or radio WPA/WMM controls. Automatic tests cannot reproduce proprietary OEM Wi-Fi firmware behavior.

## 5.1-dev.12 — Wi-Fi Direct group-owner settings (Android 10+)

**Release verified 2026-10-17 KST:** [v5.1-dev.12](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.12) is a public immutable development prerelease with uploaded signed/debug-verified `DataNetShare-5.1-dev.12-debug.apk` (**6,286,508 bytes**, SHA-256 `9909cf56d3490709fb27c504f49043e3a53ca2671344b092db0d98ada2a2bb89`) and `SHA256SUMS.txt`. [Release CI 39022482025](https://github.com/Eun-si123/DataNetShare/actions/runs/39022482025) completed **success** after host protocol, Android JVM unit tests, lint, APK package/version and signature validation. [PR CI 39022378119](https://github.com/Eun-si123/DataNetShare/actions/runs/39022378119) passed **66 host tests** and the Android check suite. None of these tests have proven real OEM support for custom group passphrases or 5GHz.



Added an optional Wi-Fi Direct advanced configuration flow ([PR #14](https://github.com/Eun-si123/DataNetShare/pull/14)) while preserving default behavior for older Android releases and devices that cannot create custom groups.

| Scenario | Expected behavior | Android hardware status |
| --- | --- | --- |
| Android 8–9, Auto defaults | Existing `createGroup(channel, listener)` creates system-generated SSID/passphrase | Not yet tested on physical device |
| Android 8–9, custom profile requested | UI disables unsupported settings; service rejects explicit custom request | Not yet tested on physical device |
| Android 10+, Auto/default | Unchanged legacy group creation, system chooses radio and password | Not yet tested on physical device |
| Android 10+, suffix MyPhone + custom ASCII passphrase | Requested group `DIRECT-xyMyPhone`; resulting Android group SSID/password must match before sender SOCKS starts | Not yet tested on physical device |
| Android 10+, choose Auto, 2.4 or 5 GHz | Android tries chosen band (no silent fallbacks); unsupported request yields visible group-creation error | Not yet tested on physical device |
| User sets a Wi-Fi passphrase then rotates Activity | Memory-only ViewModel preserves the secret while process lives; preferences never persist it | Not yet tested on physical device |
| QR pairing after custom group starts | QR uses *actual* SSID/passphrase returned by Android; receiver manual Wi-Fi join remains required | Not yet tested on physical device |
| OEM refuses/ignores custom group parameters | App fails rather than presenting different actual settings as requested success | Not yet tested on physical device |
| Local USB/existing-Wi-Fi sharing | P2P-specific settings hidden and never applied | Not yet tested on physical device |

Unit regression tests exercise Wi-Fi Direct profile validation, 32-byte SSID size, ASCII password requirements, legacy-default detection, suffix prefix rules and QR serialization. CI validates source compilation and host protocol tests; it **cannot** emulate real Android firmware radio configuration.

**Limits:** Wi-Fi Direct does not let regular applications select WPA2/WPA3, Wi-Fi 4/5/6/7, exact channel, 6 GHz availability or WMM/QoS. 2.4 vs 5 GHz selection is an operating-band request and is not equivalent to PHY standard selection. System hotspot (`SoftApConfiguration`) is a separate product API with privileged operations not used by DataNetShare. Keep custom Wi-Fi passwords out of docs, telemetry, screenshots and GitHub Issues.

## 5.1-dev.11 — UDP flow pressure and unexpected Android VPN teardown

**Verified release (2026-10-16):** [v5.1-dev.11](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.11) was publicly published as a prerelease (not draft). [Release CI run 38858720567](https://github.com/Eun-si123/DataNetShare/actions/runs/38858720567) completed successfully, including protocol tests, Android unit tests/lint/APK build, package and signature checks. Asset `DataNetShare-5.1-dev.11-debug.apk` is **6,270,360 bytes**, SHA-256 `e57ad979ca799051aad3880fcafaa0db49cda27d5a0fa5a83e826facc7c5de51`, with `SHA256SUMS.txt` also uploaded. CI success is not real Android OEM/device acceptance.



A real two-phone 5.1-dev.10 diagnostic (2026-10-16) reported **113 receiver TCP attempts / 110 connected; 35 UDP attempts / 35 associated; 10 denied UDP admissions at the 32-flow ceiling; 67 TCP retransmits with one retry exhaustion; 2 TCP upload failures, 2 upstream failures; 0 TUN drops and waits; 72,034,500 B received / 45,763,960 B sent.** The user reported throughput still being slow and an Android VPN disconnect not clearly reflected as a stopped/failed app state. The sender's cellular data route had been independently confirmed against its other infrastructure Wi-Fi public IP.

Implemented in [PR #13](https://github.com/Eun-si123/DataNetShare/pull/13):

- Increase bounded UDP admission from 32 to **40**; worst-case 80 TCP + 40 UDP leaves **8 of 128** sender client slots available to sender watchdog/probes. At full capacity, reclaim only UDP associations idle for 15+ seconds before dropping new flows, instead of waiting for their regular 30-second timeout.
- Report previously invisible UDP queue overflow and relay socket read failures, plus active UDP count. `TUN drops = 0` did not measure per-UDP-flow queue drops.
- Trigger TCP fast retransmit after three valid duplicate ACKs on outstanding data, rather than always waiting ~500 ms. This is limited loss recovery, not Linux-like congestion control.
- Preserve explicit failed VPN state when Android unexpectedly destroys `ReceiverVpnService` while active, check revoked permission during the 5-second sender health probe and classify an actual lost sender. Explicit STOP remains graceful. **An OS process kill cannot be reported from dead in-process callbacks**; Android Always-on VPN may also affect system UI behavior.

| Check | Result / evidence |
| --- | --- |
| Host protocol/UDP admission/recovery tests | **60 passed**, including [PR CI 38858374352](https://github.com/Eun-si123/DataNetShare/actions/runs/38858374352) |
| Android JVM unit tests, Lint, APK compile | **Passed** in same PR CI |
| On-device Android Settings VPN disconnect, sender-first Stop and race | **Not yet verified**; report button/state and system VPN indicator separately |
| Physical-device loaded latency, FAST.com, Ookla, UDP slot saturation | **Not yet retested**; capture receiver safe diagnostics before/after the same test |
| Production-grade TCP congestion, IPv6, IP fragment handling and HTTP/3 compatibility | **Not provided by this preview** |

Do not claim that the user's speed has been fixed based solely on JVM tests. Physical retest should note Android Settings VPN indicator, app status after system disconnect, sender watchdog result, current `UDP active`, `UDP capacity reached`, TCP retransmission delta, real down/up speed and loaded RTT. Never share QR codes or passwords.

## 5.1-dev.10 — stop from UI and sender-first disconnect

**Verification:** [PR CI 38852680309](https://github.com/Eun-si123/DataNetShare/actions/runs/38852680309) passed **55 host socket/protocol/health-policy regression tests**, Android unit tests, lint and debug APK compilation. [Release CI 38852834944](https://github.com/Eun-si123/DataNetShare/actions/runs/38852834944) published the public, debug-signed [v5.1-dev.10](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.10) preview and SHA256SUMS. APK: `DataNetShare-5.1-dev.10-debug.apk` (6,268,492 bytes), SHA-256 `9f3d2c720f19b97ab8d64105557269b1ac4eb1df652f7b7e943e9506373586fc`. Hardware VPN teardown and sender-first disconnect **still require user verification**.



**Reported by real-device user:** Stopping **sender first** caused the receiver to keep trying to reach the Internet via its still-installed VPN. Receiver's single in-app Stop button did not recover the situation, while the notification STOP action could stop the service but the Android VPN indicator sometimes remained visible.

**Findings in current sources:**
- `ReceiverVpnService` did only a one-time sender SOCKS5 probe before TUN establishment; no sender liveness detection existed afterwards.
- `MainActivity` called `stopService()` for its red Stop button, while notification STOP uses `ACTION_STOP`; behavior was inconsistent across lifecycle states.
- `ReceiverVpnService.cleanup()` stopped all TCP/UDP flow workers **before** closing the VPN descriptor, which could leave Android routes active while workers were blocked.
- `Tun2Socks.stop()` used `tcp.values.toList()` and `udp.values.toList()` against concurrently mutating maps, the same pattern known to race in the sender's earlier Stop path.

**Implementation:**
- A protected, locally bound, authenticated SOCKS5 health probe runs every 5 seconds after the receiver TUN is started; three consecutive misses cause a fail-closed receiver shutdown with visible sender-disconnected message. Individual success resets the miss counter; no destination traffic is generated.
- In-app Stop dispatches the same `ACTION_STOP` as the notification Stop, for both roles. The receiver's cleanup invalidates generation callbacks, interrupts peer monitoring, **closes its TUN file descriptor first**, then stops flow workers and foreground service. `Tun2Socks.stop()` uses weakly consistent ConcurrentHashMap iteration, not `toList()`.
- Loopback SOCKS5 regression tests cover correct password (healthy), wrong password (not healthy), closed sender listener (not healthy), protection denied, consecutive failure threshold/reset; existing host protocol tests and Android tests/lint must pass.

**Required real-device acceptance:**
1. Two phones installed with the same new preview; start sharing and receiver VPN, browse normally, ensure watchdog does not drop a healthy session.
2. **Stop the sender first**; the receiver should show `Sender disconnected or unreachable` after consecutive heartbeat failures and **its Android VPN should disappear** without notification actions.
3. Reconnect. Stop the receiver from the in-app red button; confirm foreground notification and VPN indicator disappear and normal Internet access resumes. Repeat while the receiver is still `Starting`.
4. Repeat stopping via notification STOP and switching sender/receiver roles; verify no stale VPN state or crash during dozens of active flows.
5. Enable/disable the Android system **Always-on VPN** and **Block connections without VPN** settings separately to distinguish an OS-enforced persistent VPN from an app-owned descriptor leak.
6. Reconnect after a short Wi-Fi stall to ensure a single missed heartbeat does not trigger auto-disconnect; sustained disconnect should fail closed rather than black-holing users.

**Limitations:** Android radio scheduling and real Wi-Fi Direct drops cannot be reproduced by local JVM tests; authentication probes may fail if the sender is heavily saturated even while physically nearby, so three consecutive misses are deliberately required. No hardware success or throughput improvement has been claimed.

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

The receiver negotiates bounded Window Scaling, but lacks full congestion control, SACK, fragment reassembly and IPv6/ICMP transport. It uses bounded worker threads rather than a native event-driven netstack. No throughput, battery-life, thermal or long-duration reliability improvement is claimed without equivalent before/after measurements. Release signing and stable update delivery are not configured.

## Unreleased — UDP MTU visibility and DNS transport evidence

Checked 2026-10-18 UTC against `be234dc` on independent branch `fix/udp-mtu-diagnostics-20261018`. [PR #25](https://github.com/Eun-si123/DataNetShare/pull/25) separately fixes TCP duplicate-ACK recovery; it is not included in this branch's 97-test count. Existing docs incorrectly described 64 sender clients, 64/32 receiver flows, absent Window Scaling and always-rotating custom secrets. Architecture/security notes now follow the current source; the stale diagnostic text claiming an 80-flow TCP cap is also corrected.

**Confirmed issue:** `UdpFlow` silently drops a destination-validated SOCKS reply with payload above 1472 bytes, since the 1500-byte IPv4 VPN does not support fragmentation. A successful UDP association and zero TUN-write drops therefore did not establish UDP delivery. Add a session-only aggregate `oversizedUdpReplies` counter to bridge stats, receiver display and copied telemetry. Count only after destination validation; retain no endpoints or contents and emit no per-packet logs/UI events. Packet handling and limits remain unchanged. This does **not** fix oversized UDP, implement DNS rewriting or establish why a particular native app fails.

Host evidence (Gradle 8.5 / OpenJDK 21, production SOCKS/flow code and loopback sockets, no external DNS/server):

| Test | Actual result |
| --- | --- |
| UDP response 1472 bytes | Returned as a valid 1500-byte IPv4 packet |
| UDP response 1473 bytes | Still dropped; diagnostic test failed before counter wiring, passes after; exactly one drop counted |
| Small reply after oversized reply on same association | Delivered; RX total excludes oversized payload; association stays usable |
| DNS A and AAAA responses via IPv4 UDP | Both first queries survive setup and return exact DNS response bytes; AAAA resolution does not require IPv6 transport |
| UDP DNS TC response, then explicit client retry over TCP | Complete >2000-byte DNS answer delivered over several MTU-bounded TCP segments |

All **97 tests passed** on this branch (94 existing + 3 new). TCP PR #25 also passed its 97 host tests and JDK 17 Android unit/lint/debug-build CI. The TCP commit `49e8ea5` and UDP commit `286b069` merged without conflicts in an isolated temporary worktree; all **100 combined host tests passed**, with zero skipped/errors/failures. These are correctness checks, not timed performance comparisons. Android/JDK 17 validation for [UDP PR #26](https://github.com/Eun-si123/DataNetShare/pull/26) is tracked on its CI checks. `DnsTransportTest` emulates a resolver's TCP retry: it neither runs Android DnsResolver nor proves any native app performs that retry. A large UDP answer with TC unset remains unsupported and can still time out. Larger buffers/client limits do not address this cause. Increasing MTU or adding fragmentation/DNS rewriting would change transport behavior and needs separate correctness/resource-bound tests; aggregate visibility is the smaller safe change.

### Consolidated handset checks for the next session

Use two owned phones and the same link/upstream/server. Save sanitized diagnostics before/after each case; keep QR, credentials, SSIDs, private addresses, raw logs and packet captures out of public reports.

| Hypothesis / check | How to distinguish it |
| --- | --- |
| DNS UDP versus TCP | In a diagnostic client actually included in the receiver VPN, compare `dig @1.1.1.1 example.com A +bufsize=1232`, the same AAAA query, and `dig @1.1.1.1 example.com A +tcp`. Small UDP failure with working TCP differs from a large-response MTU drop. Test a controlled large DNS answer with/without TC separately. A zero oversize count does not exclude loss of IPv4 fragments before `UdpFlow`. |
| General UDP / QUIC | Use a controlled UDP echo or STUN service and HTTPS on the same reachable server; compare association attempts, successful replies, queue overflows and the new oversize counter. Do not infer UDP support from TCP browsing. |
| IPv6 / ICMP | Compare IPv4 and IPv6 HTTPS explicitly; IPv6 routing and remote ping remain unsupported. A working AAAA DNS answer is not a successful IPv6 connection. Do not interpret locally synthesized ping replies from another engine as remote reachability. |
| Android network validation | Capture safe VPN/underlay INTERNET/VALIDATED flags before and during native server discovery, alongside browser success/failure. Record QR-requested versus manually joined Wi-Fi. Do not modify validation or infer an app's internal decision from the flags alone. |
| TCP / resource cost | Compare repeated same-server upload/download, idle/loaded RTT, retransmits, TUN drops/waits, active TCP/UDP, CPU/RAM and temperatures. At least three comparable runs per build; old 40–45 Mbps observations are not an A/B benchmark. |
| Lifecycle / recovery | Deny/cancel QR and VPN permissions, rotate, turn screen off, stop sender first, revoke VPN, then reconnect. On test phones, exercise Stop during P2P group creation and subsequent restart; record whether a late creation leaves BUSY. Do not delete unrelated groups. |

### Engine alternatives and open findings

Source-only comparison of [HevSocks5Tunnel at `780b396b0fc3d0355fc0614e221e3446933669c4`](https://github.com/heiher/hev-socks5-tunnel/tree/780b396b0fc3d0355fc0614e221e3446933669c4): root MIT license, lwIP/task-system/core/YAML submodules, Android NDK build and dual-stack/TCP/UDP features. No native build, dependency audit, packet trial or performance benchmark was performed here.

| Approach | Security, compatibility and maintenance tradeoff | Decision |
| --- | --- | --- |
| Retain current code unchanged | Known loss-recovery/diagnostic gaps remain; no new integration risk or evidence of better throughput | Rejected for the confirmed small defects |
| Small Kotlin corrections | Preserve binding/protection/authentication and bounds; host-reproducible behavior, little build cost; custom-stack limitations remain | Implemented in independent TCP and UDP PRs |
| Replace only TCP with another stack | Two packet/session engines must coordinate TUN ownership, UDP routing and shutdown; more boundary states, uncertain performance | No implementation without a measured prototype |
| Optional complete receiver backend | Potentially broader TCP/IPv6 handling and different per-flow scheduling; requires JNI/ABI/dependency updates, bounded resources and protected/bound sockets | Best next structural experiment only after safeguards below; keep Kotlin fallback |
| Immediate full replacement | Removes fallback before OEM/API/routing/lifetime validation; upstream benchmark numbers do not predict these phones | Deferred |

Concrete integration obstacles found in that pinned candidate: `Application.mk` defaults to Android API 29 (this app supports 26), sample MTU is 8500, and `max-session-count` defaults to zero/unlimited. The inspected `hev_socks5_session_bind` configures socket marking/TCP Fast Open; its JNI entrypoint does not supply this app's `VpnService.protect()` and local `Network.bindSocket()` adapter. A prototype must prove every TCP/UDP control/relay socket is protected and bound before connect, fail closed on errors, preserve sender budgets and disable unsafe defaults. The C API offers `main_from_str`, preferable to persisting session secrets in a config file. Use standard SOCKS UDP mode: its UDP-in-TCP extension is not implemented by this sender. Advertised optional ICMP Echo is a **local reply**, not remote ICMP tunneling. Pin/review transitive code/licenses and test API 26/28/29/34+, ABIs, 16 KiB pages, descriptor ownership, Stop/restart and loaded-latency/CPU/RAM before any default switch.

Other unresolved candidates from source review: a Wi-Fi Direct create callback arriving after Stop is ignored before ownership is recorded, so cleanup may miss a just-created group (needs controlled OEM reproduction); sender read-idle checks do not bound a blocked TCP write; per-flow reader/writer/control threads and timed waits remain scheduling/stack-memory candidates. These are hypotheses/limits, not confirmed causes of the field speed or app failures. Next work should prioritize the handset protocol matrix, then a host blocked-write/Stop stress fixture and P2P cancellation state-machine reproduction before broader redesign. Keep the existing [agent-workflow PR #24](https://github.com/Eun-si123/DataNetShare/pull/24) separate; no overlapping agent guidance/handoff files were created here.
