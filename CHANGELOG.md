# Changes

## 5.1-dev.13 — 2026-10-17

- Remember user-chosen Wi-Fi Direct passphrases by default, like Android's ordinary hotspot settings. When the sender starts a valid custom Wi-Fi Direct profile, AES-GCM encrypts the password using a non-exportable Android Keystore key and stores ciphertext only in app-private preferences. No plaintext fallback. The password survives app process restarts, but app data clearing/uninstall/reinstall removes it.
- Clear the password field and start sharing to remove the previously saved custom passphrase and switch back to automatic generation; change an active group only by Stop → edit → Start. The per-session SOCKS5 proxy password still rotates and is not persisted.
- When stored ciphertext cannot be restored, notify the user instead of silently claiming their old password is usable. If encrypted saving fails, do not start a session that falsely promises password persistence.
- Use a dedicated, non-exported manifest-declared portrait ZXing capture Activity with orientation locking to prevent QR camera from forcing a landscape UI.
- JVM tests cover AES-GCM random nonce roundtrip, tampering/wrong-key rejection and passphrase validation. Actual Android Keystore/OEM scanner orientation requires physical phone acceptance.

## 5.1-dev.12 — 2026-10-17

Add optional router-like *Wi-Fi Direct group* customization with clear Android API limits:

- Android 10+ (API 29+) sender Advanced settings: customize the `DIRECT-xy` SSID suffix (up to 22 ASCII letters/digits/underscore/hyphen), the Wi-Fi **link** passphrase (8–63 printable ASCII characters), and request Auto / 2.4 GHz / 5 GHz band.
- The SSID must retain Android's required `DIRECT-xy` prefix, where `xy` is randomized for each new custom group. Settings are verified against the *actual* created group; if an OEM ignores them, fail explicitly instead of displaying incorrect credentials. The receiver's existing in-app QR pairing automatically shows the actual Wi-Fi name/password.
- Leaving all values at Auto/blank preserves the original Android-managed Wi-Fi Direct group creation. On Android 8–9, the legacy auto flow remains available; Android 10+ is required for configuring custom Wi-Fi Direct groups through public APIs.
- The optional Wi-Fi password is not persisted to disk or auto-filled after process death; only non-secret SSID suffix and frequency-band preference are remembered. This Wi-Fi passphrase is **different from the per-session SOCKS5 password**.
- Android Wi-Fi Direct does **not** expose third-party controls for WPA2/WPA3 cipher mode, Wi-Fi 4/5/6/7/802.11ax PHY generation, fixed radio channel, or radio WMM/QoS. Requested 5 GHz may fail on a device that does not support it. Avoid pretending these settings are applied.
- Automated JVM tests validate default behavior, SSID restrictions, ASCII passphrases and QR encoding. *Actual Wi-Fi Direct group creation and multi-vendor radio compatibility remain unverified on phones*.

## 5.1-dev.11 — 2026-10-16

Follow-up to a real phone-to-phone 5.1-dev.10 diagnostic report: **113 TCP attempts / 110 SOCKS CONNECT successes, 35 UDP associations, 10 rejects at the fixed 32-UDP-flow cap, 67 TCP retransmissions (one flow exhausted recovery), 0 TUN drops/waits, 72 MB RX / 46 MB TX**. The receiver also had two TCP upload failures and two upstream connection failures. Mobile-data egress was already independently established.

- Increase **bounded receiver UDP association capacity from 32 to 40**, deliberately leaving 8 slots below the sender's 128-client cap with up to 80 TCP flows. Only when admission is full, reclaim 15+-second inactive UDP associations to allow fresh DNS/QUIC connections, instead of waiting for the existing 30-second timeout.
- Report previously silent UDP queue overflows and relay receive errors. Add a compact active-UDP count to receiver performance telemetry. A zero TUN-drop count does not cover UDP queue losses.
- Add TCP fast retransmit after **three duplicate ACKs** for an unacknowledged data segment, avoiding the default ~500 ms retransmission timer for that particular loss pattern; it does **not** implement full TCP congestion control or SACK.
- On unexpected Android `VpnService.onDestroy()` while connected, show a **failed/disconnected** state instead of silently switching to `Ready`. Poll VPN permission during sender health checks to notice cases where local peer protection still works but Android has revoked or replaced the active VPN. Keep explicit user Stop clean. Record revocation/sender disconnection as diagnostic events.
- Add JVM regression tests covering UDP bounds/eviction/overflow and immediate TCP loss recovery; Android unit/Lint/APK CI guards builds. Physical OEM VPN disconnect events and browser throughput **still require real-phone validation**.
- Loaded RTT and throughput limits of the home-grown experimental IPv4 TCP stack remain: these changes should not be described as guaranteed speed/latency fixes. Review Android always-on VPN settings if the OS retains a persistent VPN indicator.

## 5.1-dev.10 — 2026-10-16

- Fix a real phone-to-phone shutdown bug: the sender can be stopped first while the receiver continues retrying with Android VPN routing still installed. The receiver now probes the paired, authenticated SOCKS5 endpoint every **5 seconds** and disconnects the VPN after **3 consecutive failures** rather than leaving an Internet black-hole. A single transient failure does not stop the VPN.
- The Android app's contextual Stop button now dispatches the **same explicit ACTION_STOP service intent** as the persistent notification's Stop button. Both use the receiver's one cleanup path.
- On receiver Stop, failed startup, Android VPN permission revocation, or peer watchdog expiry, **close the TUN/Android VPN descriptor before cleaning up transport workers**. Previously, blocked TCP/UDP bridge cleanup could delay route removal.
- Receiver bridge shutdown now iterates ConcurrentHashMap flows directly rather than Kotlin `toList()` snapshots, preventing removal-vs-stop `NoSuchElementException` races.
- Add deterministic JVM tests for live/wrong-password/offline sender SOCKS5 liveness and consecutive failure threshold. Android UI+VPN teardown and varying OEM settings still need real-device verification.
- No change to TCP throughput/congestion logic. Android system **Always-on VPN** settings may override app-level stop behavior and must be inspected separately if system UI still shows a persistent VPN.

## 5.1-dev.9 — 2026-10-16

- Real recipient speed test: 28.6 Mbps download / 17.1 Mbps upload; unloaded RTT 38 ms, loaded RTT 729 ms (download) and 251 ms (upload); jitter 7 ms. This strongly suggests queueing or flow-control pressure under load, but the bottleneck layer is unconfirmed.
- Expose receiver TCP retransmissions, TUN writability wait count and aggregate wait milliseconds alongside flow successes and TUN drops, including in safe copied diagnostics.
- Treat normal sender idle TCP reclamation as maintenance statistics rather than a yellow network failure.
- No throughput/latency improvement is claimed for this diagnostic preview. A controlled cellular/Wi-Fi Direct/SOCKS/VPN comparison and mature tun2socks evaluation remain open.

## 5.1-dev.8 — 2026-10-16

- Fix a race discovered during release verification: sender Stop no longer snapshots ConcurrentHashMap socket/UDP-association sets via Kotlin `toList()` while other threads remove entries (could throw `NoSuchElementException`). Add concurrent stop test and rerun full CI before publishing.

- Consolidate sender Wi-Fi Direct, sender USB/Wi-Fi, receiver VPN start and the separate Stop action into **one contextual primary button**: Start / Cancel while starting / Stop while connected.
- Select Share or Receive before connecting and display only the selected role's setup. During a session, settings collapse while QR, active status and privacy-safe network diagnostics remain accessible.
- Wi-Fi Direct is the default share method; USB/local Wi-Fi and advanced routing remain available. Receiver QR scanning is primary with collapsible manual IPv4/port/password fields.
- Save only non-secret role/transport preferences. Preserve ephemeral QR/password data and pending VPN permission in the memory-only ViewModel across rotation.
- Add pure action-selection unit tests. Real Android accessibility, foreground-service stop, QR and permission flows still need physical-device acceptance.
- This is a usability preview, not a network throughput improvement.

## 5.1-dev.7 — 2026-10-16

Connection-capacity and lifecycle fix after a real phone-to-phone test showed **64/64 sender SOCKS5 connections and 200 connection-limit rejections**, while FAST.com occasionally measured ~30 Mbps but Ookla browser Speedtest repeatedly failed its latency phase.

- **Avoid wasting SOCKS5 slots on half-open TCP handshakes:** receiver starts the sender-side proxy connection only after the Android app acknowledges the SYN-ACK; orphan or retried SYNs do not create an upstream socket.
- **Reap abandoned relays:** sender TCP reads use bounded polling with a shared bidirectional 45-second idle timer. Legitimate continuous upload/download refreshes the timer; an idle client is closed, its proxy permit released and a diagnostic counter incremented. Sender and receiver UDP associations expire after 30 seconds of inactivity instead of 120 seconds.
- **Bounded capacity:** sender proxy limit 64 → 128, receiver TCP flows 64 → 80; receiver UDP flows remain 32 and resources are still bounded. This is **not** an unlimited connections mode and memory/thread pressure must be measured on devices.
- Add host regressions showing an unacknowledged SYN uses no sender slots and an abandoned TCP client releases a saturated proxy slot. Retain existing half-close, large-download, IPv4/TCP checksum, UDP and QR coverage.
- This addresses **observed concurrent-client exhaustion**, but does **not** establish a 5G throughput target or guarantee browser speed-test compatibility. The receiver is still an experimental Kotlin TCP/IP stack.

## 5.1-dev.6 — 2026-10-16

- User confirmed that the sending phone's **mobile-data** IP differs from its concurrent Wi-Fi public IP. Browser speed test fails with FAST.com showing 0 and Ookla reporting a latency-test server connection error, even with no application error message.
- Show an **always-visible Network diagnostics** panel beneath the Connected status on both phones, with a button to copy categorized, bounded in-memory events without usernames, passwords, QR, endpoints, URLs or packet contents.
- Fix silently swallowed receiver UDP proxy setup/read exceptions, record TCP/UDP capacity limits, sender upstream failures, and TUN output congestion.
- Report cumulative **TCP attempts / connected**, **UDP attempts / associated**, and **TUN drops** so a traffic stall without exceptions can be localized.
- Add host tests for journal bounds/concurrency/privacy, setup failures, and existing protocol regressions.
- Android speed-test compatibility is **not yet proven**. The home-grown IPv4 TCP/IP bridge still needs physical-device validation and comparison with a mature tun2socks engine.

## 5.1-dev.5 — 2026-10-16

Transport robustness after a phone-to-phone report of intermittent `ERR_CONNECTION_RESET` when opening FAST.com and an Ookla `LATENCY TEST ERROR` during speed tests.

- TCP SYN-ACK now advertises 1200-byte MSS instead of omitting MSS (which can force conservative peer defaults); retransmitted SYN-ACK carries the same option. TCP/IP header lengths and checksums account for options.
- Nonblocking VPN TUN output retries temporary `EAGAIN`/`EINTR` errors after a short bounded writability poll, instead of dropping immediately. Prolonged congestion still drops packets and relies on TCP retransmission; it cannot block forever.
- Add JVM regression coverage for MSS and packet checksum, SYN retransmissions, and TUN write retry/backpressure policy.
- Screenshot of Ookla Speedtest displayed an LG U+ egress IP in LG Telecom address space, consistent with mobile data being selected, but this alone does not establish the cause of remote server failures.
- **Not proven:** that the two physical phones, Chrome/FAST.com, Speedtest latency, HTTP/3/QUIC, IPv6 or fragmented UDP are fully supported. The built-in receiver remains experimental.

## 5.1-dev.4 — 2026-10-16

- Investigate user-observed `ERR_CONNECTION_RESET` during phone-to-phone browser speed tests with the sender simultaneously joined to another Wi-Fi network.
- Clarify and report the selected upstream: default stays **cellular-only** regardless of connected Wi-Fi; explicit `Use Android default route` follows Android's actual Wi-Fi/VPN/cellular choice and no longer unnecessarily maintains a separate cellular request.
- Return SOCKS5 network-unreachable (code 3) for absent sender mobile-data routing and host-unreachable (code 4) for DNS resolution failure rather than lumping all failures into generic code 5.
- Display a sanitized recent upstream failure on the sender and recent TCP failure/reset/retransmission category on the receiver, without logging requested destination addresses or secrets.
- Add a 2 MiB+ continuous TCP transfer regression, plus explicit no-cellular SOCKS reply coverage; prior loopback protocol/QR tests remain in CI.
- Real Android networking, browser/Speedtest compatibility and true Wi-Fi Direct plus infrastructure Wi-Fi coexistence **still require user hardware verification**.

## 5.1-dev.3 — 2026-10-16

- Add sender-only active-session QR connection handoff with a strict versioned payload: IPv4 address, proxy port, rotated SOCKS5 password and optional Wi-Fi Direct SSID/passphrase.
- Add offline in-app camera QR scanning to fill the Android receiver form without typing. Show Wi-Fi details, copy Wi-Fi password and link to Android Wi-Fi settings; do not join Wi-Fi or start a VPN without user action.
- Reject untrusted/malformed/oversized/unknown-field QR inputs with automated tests; no public deep-link handler, credential logging, or preference storage.
- Retain the pending VPN permission intent and scanned credentials in an in-memory ViewModel across Activity rotation.
- QR is **session-scoped, not a one-scan token**. Stop and restart the sender to revoke an old QR. Physical-device camera, Wi-Fi Direct and VPN interoperability tests are still outstanding.
- Add the ZXing Android Embedded library 4.3.0 (camera permission used only when scanning).

## 5.1-dev.2 — 2026-10-16

Hotfix preview for Android phone-to-phone receiver startup after a reported `Sender check failed: Could not protect sender socket` on 5.1-dev.1.

- Prepare/bind the outbound socket to the selected local Wi-Fi link *before* calling Android `VpnService.protect()`. On some Android implementations, a newly constructed `Socket()` has no kernel file descriptor until a bind/network operation initializes it.
- Use the same bind-then-protect sequence for sender credential probes, receiver per-flow TCP and UDP control sockets, and UDP relay sockets, before any outgoing connection.
- Add JVM regression coverage for ordering, refusal and bind failures. Host tests and Android build are necessary but **do not** prove the issue is fixed on a physical phone.
- Raise development APK versionCode to 32. The debug signing key is still **not stable**, so 5.1-dev.1 may need to be uninstalled before 5.1-dev.2 can install. No APK credentials are backed up.

## 5.1-dev — 2026-10-16

Development candidate based on upstream v5. The built-in IPv4 receiver remains experimental; real phone-to-phone/OEM/carrier testing is outstanding.

- Require fresh per-session SOCKS5 authentication in both Wi-Fi Direct and USB/Wi-Fi modes. Display proxy and Wi-Fi passwords separately; permit explicit copying without saving them to preferences/logs.
- Select an explicit local listening address for USB/Wi-Fi. Use Android's reported Wi-Fi Direct owner address; bound retries and ignore callbacks after Stop. Leave other apps' groups alone.
- Resolve and connect via a retained cellular Network by default; make Android default routing an explicit optional setting for existing VPN use.
- Preserve TCP half-close replies; cap concurrent clients/UDP destinations and close active sockets on Stop.
- Isolate UDP associations and original receiver flows, preserve first packets during setup, reset receive capacity for every datagram and reject unsolicited peers.
- Configure receiver IPv4/port/password; probe the sender before installing VPN routes; protect/bind proxy sockets to the local link even when cellular is default.
- Add bounded receiver TCP windows, duplicate trimming, ACK tracking, retransmission and half-close handling. Validate packet lengths and reject unsupported fragments. Keep protocol limitations explicit.
- Fix service ownership/state restoration, foreground shutdown, resource cleanup, Android 12 fine/coarse permission requests and stale callback handling.
- Add Korean UI/help, collapsible diagnostics, notification Stop actions and a separate `.dev` package.
- Add 17 real loopback regression tests, an Android-free host test project, Android lint/build CI and downloadable test reports.
- Replace outdated feature/privacy/carrier claims and build/install instructions; document architecture, signing, protocol limits and outstanding real-device acceptance.

Compatibility changes: unauthenticated clients no longer connect; Wi-Fi and proxy passwords differ; the unauthenticated port-8283 speed-test listener/UI is removed; debug package is `com.datanet.share.dev`. Existing upstream release APKs are not builds of this fork.
