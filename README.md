# DataNet Share · maintained fork

[한국어 사용 안내](docs/README.ko.md) · [Build](android/README.md) · [Tests and device checklist](docs/TESTING.md) · [Security and privacy](docs/SECURITY-REVIEW.md)

Share an Android phone’s connection through an **authenticated SOCKS5 proxy**, over Wi-Fi Direct, USB tethering, or a private Wi-Fi network. The same app also includes an **experimental Android IPv4 VPN receiver**.

This is [Eun-si123’s fork](https://github.com/Eun-si123/DataNetShare) of [Keggin-AI/DataNetShare](https://github.com/Keggin-AI/DataNetShare), based on the archived upstream v5 source. The actively maintained project is in [`android/`](android/). **5.1-dev is a development candidate, not a certified production release.** Foundation and build evidence are in [PR #1](https://github.com/Eun-si123/DataNetShare/pull/1); the receiver socket hotfix is in [PR #3](https://github.com/Eun-si123/DataNetShare/pull/3).

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

## v5.1-dev.17 — receiver capacity, UDP queues and QR recovery

User field diagnostics showed 106 TCP limit rejections with only 80 permitted flows and eight UDP queue overflows. The receiver now shares a bounded 120-flow TCP/UDP admission budget (up to 112 TCP when UDP is idle), reclaims only genuinely idle or abandoned flows under pressure, and expands per-UDP packet queues 32 to 64. Receiver diagnostics expose reclaimed idle TCP and UDP queue overflows. QR-requested Wi-Fi now retries only transient route errors before instructing the user to check the currently active sender SSID and fall back to manual connection. Throughput improvement is NOT established; real-device A/B tests are required.

## v5.1-dev.16 experimental packet efficiency (real-phone A/B pending)

Receiver TCP MSS was raised from 1200 to 1400 inside the 1500-byte VPN MTU, and per-packet TCP pseudoheader/IPv4 header checksum **temporary byte-array allocations** were removed. The checksum behavior is regression-tested; real-world speed, latency, CPU and battery improvements are **not yet verified**. Current small unscaled TCP window and Kotlin userspace TCP stack remain bottleneck candidates. See docs/TESTING.md for same-server, same-device v5.1-dev.15 vs v5.1-dev.16 test criteria.

## Experimental QR Wi-Fi automation and fallback (v5.1-dev.15 candidate)

This candidate is **not yet validated on real Android hardware**. On Android 10+, receiver QR scanning can request an OS-approved Wi-Fi connection to the sender's access point and, after IPv4 provisioning, start the normal receiver VPN consent flow. Manual connection remains available for denied/unsupported network requests.

Sender Advanced settings now offer an optional **session-only custom SOCKS5 password** (12–128 printable ASCII characters without spaces) and **cellular-first with validated Wi-Fi fallback**. Blank custom password produces a new random password each session. Reusing a custom password also preserves old QR authentication across sessions; do not treat QR codes as single-use. When the cellular network recovers, **new** connections return to cellular. Existing sockets are not migrated and local-only Wi-Fi Direct is not used as an Internet fallback. Using Android default route is a separate option.

Receiver can optionally release the QR-requested local Wi-Fi when its sender disconnects, letting Android use mobile data or a previously saved Wi-Fi network. This does not auto-join arbitrary networks or detect every case of sender-upstream Internet failure. **The existing ~30 Mbps throughput problem is not claimed fixed.**


## Real-phone performance / VPN stop investigation (5.1-dev.11)

A real device report showed **110/113 TCP connections, 35 successful UDP associations, 10 UDP flow-limit rejections (at the old 32 cap), 67 TCP retransmissions, 1 retransmission exhaustion, and no TUN packet drops**. This indicates the sender and SOCKS authentication are mostly working, but does **not** imply the VPN's throughput or latency is acceptable.

The experimental receiver now admits **up to 40 UDP sessions** and reclaims flows that have been idle for at least 15 seconds when full. UDP queue overflow and relay receive failures appear as diagnostics. A receiver TCP segment lost with three duplicate ACKs may be retransmitted immediately instead of waiting for the 500 ms timer. **This is not a full TCP implementation or a guarantee of speed improvements.**

If Android stops the receiver's running VPN service without the app's explicit Stop, the app now records a disconnect/failure instead of silently showing Ready. Android VPN permission is also checked periodically during paired-sender liveness probing. Process-level force kills and OEM always-on VPN behavior cannot be fully detected while the app is dead.

**Device retest:** check whether the VPN button/state switches to disconnected after using Android's VPN Disconnect UI (and after sender-first Stop). Then compare active UDP count, rejected UDP requests and TCP retransmit counts on a speed test, without sharing QR or credentials.

## Remembered Wi-Fi password and freely rotating QR scanner (5.1-dev.14)

A manually configured Wi-Fi Direct password is **remembered automatically** after starting sharing, like Android hotspot settings. It is encrypted with AES-GCM using a non-exportable Android Keystore key before storage in app-private preferences. It persists across app/process restarts. No plaintext fallback is used. Clearing app data, uninstalling, or installing with an incompatible development signing key will erase it. The SOCKS5 session password still rotates and is never saved.

To change the password, **Stop sharing → Advanced Wi-Fi Direct settings → Edit Wi-Fi password → Start sharing**. A running group cannot be reconfigured in place. Clearing the password and starting again also removes the saved credential and returns to Android/random generation.

The QR scanner now uses a dedicated rotation-aware capture activity. It neither forces landscape on launch nor locks portrait: with the phone's Auto-rotate enabled, rotate the device to switch portrait ↔ landscape; if Auto-rotate is off, it honors the locked user orientation. QR rotation and Android Keystore behavior still need real-device testing.

## Wi-Fi Direct settings — Android 10+ (5.1-dev.14)

On the sender phone choose **Share this phone → Wi-Fi Direct → Advanced connection settings**. You can configure:

- **Network name suffix** (e.g. `MyPhone`): Android requires `DIRECT-xy` at the beginning, so the actual SSID will be `DIRECT-xyMyPhone`, with a fresh two-character tag. Only ASCII letters, digits, `-` and `_`, max 22 characters.
- **Wi-Fi password:** leave blank for an Android/generated random password, or enter 8–63 printable ASCII characters for a custom Wi-Fi Direct link password. **This is not the SOCKS5 proxy password.** Custom Wi-Fi passwords are remembered by default as Android Keystore-protected AES-GCM ciphertext in app-private preferences, not plaintext.
- **Frequency band:** Automatic (recommended), request 2.4 GHz, or request 5 GHz. Unsupported requests may cause group creation to fail. You may have to switch back to Auto.

The sender displays the *actual* Wi-Fi Direct SSID/passphrase after the OS creates the group. The existing receiver QR encodes those actual values, so the receiver still scans and joins using Android Wi-Fi settings. With all options at default, legacy automatic group creation remains unchanged. Android 8/9 can only use that automatic mode.

**OS capability boundary:** ordinary third-party Wi-Fi Direct apps cannot set hotspot WPA2/WPA3 modes, Wi-Fi 4/5/6/7 generations, exact wireless channels or WMM/QoS priority via these APIs. Wi-Fi Direct security is system-managed; switching 2.4 vs 5 GHz does not mean selecting Wi-Fi 4 vs 5. Full Soft AP/hotspot management needs OEM/system privileges and is a separate subsystem, not this application's Wi-Fi Direct group. All OEMs and Android versions need on-device verification.

## What works and what still needs testing

| Path | Implemented behavior | Verification boundary |
| --- | --- | --- |
| Sender SOCKS5 TCP | Per-session authentication; half-close; bounded clients; closes sockets on Stop | Automated loopback data-integrity tests; Android/OEM/carrier tests pending |
| Sender SOCKS5 UDP | RFC 1928 association; separate sockets per destination; association tied to control connection | Automated small/large datagram and concurrent-client tests |
| USB / private Wi-Fi | Bind only the local interface address you select | USB drivers, tethering setup and device routing need real devices |
| Wi-Fi Direct | Creates its own group; uses Android’s reported group-owner IP; shows SSID and Wi-Fi password | OEM group formation and legacy Wi-Fi client compatibility need real devices |
| Android receiver | Configurable sender address/port/password; checks credentials before enabling VPN; separate UDP flows; bounded TCP buffering/retransmission and half-close | Experimental custom IPv4 stack; synthetic loss/duplicate tests do not replace interoperability testing |
| IPv6 / ICMP / IP fragments | Not implemented by the receiver | The VPN receiver blocks IPv6 rather than providing an IPv6 tunnel; no ping support |

**No claim is made that this app bypasses a carrier’s tethering accounting, guarantees VPN privacy, or works with every game or app.** Data use, tethering policy, VPN exclusions, Android routing and battery management depend on the actual environment.

## Get an APK (public preview)

**Easy install:** [Download the current v5.1-dev.21 preview APK directly](https://github.com/Eun-si123/DataNetShare/releases/download/v5.1-dev.21/DataNetShare-5.1-dev.21-debug.apk) (Android 8.0+). You can also open the [exact v5.1-dev.21 release page](https://github.com/Eun-si123/DataNetShare/releases/tag/v5.1-dev.21) and expand **Assets** for `DataNetShare-5.1-dev.21-debug.apk`. This is a **pre-release**, not the repository's stable Latest release. Do not download the Source code archives. `SHA256SUMS.txt` is provided for integrity verification. The preview is a debug-signed test build for Android 8.0+; its signing key is not stable across future builds, so future updates may require uninstalling this package and losing local settings. It is **not** a production-certified app.

**Alternative (development CI):** Open a successful [Android source verification](https://github.com/Eun-si123/DataNetShare/actions/workflows/android-ci.yml) run, download and unzip `datanetshare-debug-apk`, then install `app-debug.apk` on your phone(s):

```sh
adb install -r app-debug.apk
```

The development APK uses **`com.datanet.share.dev`** and can coexist with upstream `com.datanet.share`. Do not uninstall your current app just to try it. CI artifacts expire and may require a signed-in GitHub account. Do not treat an upstream release APK or the archived source ZIP as a build of this fork. Debug signatures from different machines/runners may differ; see [build instructions](android/README.md#install-and-signing).

## Start sharing

1. Enable mobile data on the sender.
2. For **Wi-Fi Direct**, enable Wi-Fi and tap **Share via Wi-Fi Direct**. Grant nearby-device permission on Android 13+, or location permission and enable Location services on Android 8–12. Join the displayed SSID using its **Wi-Fi password** from the other device’s normal Wi-Fi settings. Some OEMs instead require their Wi-Fi Direct settings.
3. For **USB / Wi-Fi**, set up USB tethering or connect both devices to a trusted local network. Tap **Refresh addresses**, select the phone’s USB/Wi-Fi address, then **Share via USB / Wi-Fi**. No wildcard `0.0.0.0` listener is used.
4. Configure your client for the displayed IPv4 address, port **8282**, username **`datanet`**, and the displayed **SOCKS5 password**. This differs from the Wi-Fi password and changes every session.

The default upstream is **mobile data only**: DNS resolution and sockets use the selected cellular Android Network, with no silent default-route fallback. While mobile data is unavailable, the listener may remain active, but internet connections will fail.

To use an existing sender VPN, enable **Use Android default route** *before* sharing. This deliberately follows Android routing and **does not provide a VPN kill switch**. Verify that the VPN includes this app; its exclusions or disconnection can expose traffic. Stop and restart sharing after changing this option.

### Computer or third-party SOCKS5 client

Use a client that supports **SOCKS5 username/password authentication** and remote DNS. System proxy settings and browser proxy dialogs do not all support authentication or cover every app. For a connectivity check using curl:

```sh
# Replace PHONE_IP with the address on the sender screen.
# curl prompts for the session password; it is not written into the command.
curl --socks5-hostname PHONE_IP:8282 --proxy-user datanet https://example.com/
```

For system-wide routing on a computer or iOS device, use a compatible third-party VPN/tun client with SOCKS5 authentication; check that client’s documentation. iOS does not run this APK. There is no bundled universal iOS profile or verified list of compatible iOS clients.

### Phone-to-phone QR pairing (5.1-dev.3)

1. On the **sending phone**, start Wi-Fi Direct sharing (or USB/private Wi-Fi), then tap **Show connection QR**.
2. On the **receiving phone**, tap **Scan sender QR (camera)** in DataNet Share and scan the sender screen. Camera permission is needed.
3. Sender IPv4, SOCKS port and password are filled automatically. For Wi-Fi Direct, tap **Copy Wi-Fi password → Open Android Wi-Fi settings** to join the sender's SSID, then return to DataNet Share. Some devices require their dedicated Wi-Fi Direct settings.
4. Tap **Start receiver VPN** and approve Android's VPN permission. The IPv4 receiver remains experimental.

**Security:** The QR contains plaintext SOCKS5 and possibly Wi-Fi credentials. Display it only to the intended recipient. It is a *per-sender-session* code, **not one-scan**: anyone with a photo can reuse it until sharing is stopped/restarted, which rotates the proxy password. QR contents are not uploaded or stored in preferences. Do not post QR screenshots.

### Android receiver in this app

1. Join the sender’s network first. Keep it connected even if Android reports “no internet”.
2. Enter the **sender IPv4 address, proxy port and SOCKS5 password** in the receiver section. Username is `datanet`.
3. Tap **Start receiver VPN** and accept Android’s VPN prompt. A credential/link probe runs before VPN routes are installed.
4. The receiver protects its proxy sockets and selects the local link to the sender even when cellular is the default network. You can run an external STUN UDP reachability test under **diagnostics**.

Only one sender/receiver mode runs per app process. Use **Stop connection** or the notification’s **Stop** action before switching modes. Opening the app again shows the current state; a killed service is not silently resurrected. The development receiver remains experimental; use an established SOCKS5-capable client when broad protocol compatibility matters.

## Automatic receiver VPN shutdown when the sender disappears (5.1-dev.10)

Previously, stopping sharing on the **sender phone** left the receiver's experimental IPv4 VPN installed, so Android apps kept retrying unreachable Internet connections. The receiver now performs a protected SOCKS5 authentication health check against its paired sender every five seconds. **Three consecutive failures** close the Android VPN and show a sender-disconnected message; successful checks reset the failure count. The probe does not forward any destination traffic or log passwords.

The main app's single **Stop connection** button now dispatches the **same explicit service STOP command** as the notification's Stop action. Shutdown closes the VPN file descriptor before waiting for TCP/UDP bridge cleanup; the receiver's concurrent flow-collection shutdown was also made safe. Users can therefore stop the receiver directly in the app, even if the sender is already offline.

**Expected test:** connect two phones, turn the sender off first, and confirm the receiver automatically leaves VPN mode within a few health-check intervals; also press the receiver's in-app Stop button while connected and verify the Android VPN indicator disappears. Transient radio outages can still cause a deliberate fail-closed disconnect after three missed checks. If Android **Always-on VPN** or **Block connections without VPN** is enabled for this app, that OS-level configuration can affect the displayed VPN state and normal Internet fallback. This is a prototype; no device verification is claimed yet.

## Diagnose high loaded latency (5.1-dev.9)

A recipient speed-test measured **28.6 Mbps down, 17.1 Mbps up, 38 ms idle ping, 729 ms download-loaded ping and 251 ms upload-loaded ping**. This is consistent with queueing or userspace TCP backpressure, but the location of the bottleneck is not proven.

The receiving phone now shows **TCP retransmits** and **TUN waits (count and accumulated milliseconds)** alongside existing TCP/UDP connection counters and TUN drops. **Copy safe diagnostics** includes these aggregate metrics even when no exceptions are recorded. Normal **sender idle TCP reclaimed** is maintenance, displayed in regular stats rather than the yellow failures panel. Compare counter changes before and after a speed test; zero TUN waits/retransmits do not rule out cellular/Wi-Fi queueing or TCP window limitations. This release adds observation tools, not a demonstrated speed fix.

## One connection button and focused setup (5.1-dev.8)

The previous app showed separate Wi-Fi Direct, USB/Wi-Fi, receiver VPN and Stop buttons on one long screen. Now choose **Share this phone** or **Use shared data**, configure only the selected role, and use **one primary button**. It starts the selected role, becomes **Cancel connection** while starting, and changes to **Stop connection** while connected. Settings collapse during an active session; sender QR/session details and the privacy-safe diagnostic panel stay visible.

For sharing, select Wi-Fi Direct (default) or USB/existing Wi-Fi. Android default routing is behind **Advanced connection settings**. For receiving, scan the sender QR or expand **Enter address and password manually**; the QR flow still requires explicit Wi-Fi joining and VPN approval. Stop before changing roles.

Role/transport preferences are non-secret; QR data, SOCKS5 credentials and pending VPN permissions are kept in app memory. This is a **usability change**, not a proven transport-performance optimization.

## Browser connection-capacity fix (5.1-dev.7)

A real sender screen showed **64 active connections and 200 rejected attempts**, while FAST.com sometimes reached ~30 Mbps and Ookla Speedtest failed the latency phase. The 5.1-dev.7 sender reclaims idle TCP relays after 45 seconds (with a shared timer for both transfer directions), and sender/receiver UDP sessions after 30 seconds. The sender's bounded limit is now 128; the receiver allows up to 80 TCP and 32 UDP flows. The receiver also waits for the client TCP handshake ACK before opening a sender SOCKS5 connection, preventing unacknowledged SYNs from consuming sender slots.

These changes reduce wasted resources but can disconnect legitimate connections that are idle for a long time. Keep-alives or long-running streaming connections should refresh their activity timer when they actually transfer traffic. **Measured mobile 5G speed is not a guarantee of throughput through Wi-Fi Direct + userspace SOCKS5 + experimental VPN**; the receiver still lacks mature congestion handling, modern transport interoperability and efficient per-flow threading. Test Speedtest/FAST.com again and compare sender active connections + rejected counts. New app diagnostics also show reclaimed idle sessions.

## Network diagnostics (5.1-dev.6)

On both phones, open DataNet Share and look **directly below Connected and the transfer counters**. An always-visible **Network diagnostics** panel counts sender upstream failures, connection-capacity exhaustion, receiver TCP/UDP setup/read errors, and VPN output congestion. Tap **Copy safe diagnostics** to share categories and counts; the copied text omits QR secrets, SOCKS5/Wi-Fi passwords, IPs and website URLs.

The receiver's live stats now also show **TCP attempts / connected**, **UDP attempts / associated**, and **TUN drops**. This helps narrow down stalls where neither Android nor the SOCKS server throws an exception, such as a speed test stuck at 0. Counts are cumulative and should be interpreted with the test scenario. **No recorded error does not mean that the tunnel passes application traffic correctly.**

## Browser resets and speed-test transport limitations

The 5.1-dev.5 preview improves the receiver's TCP SYN-ACK by explicitly advertising MSS 1200 and retries short nonblocking TUN output congestion. This can reduce per-packet overhead and transient packet loss, but **does not certify** Ookla, FAST.com, Chrome HTTP/3/QUIC or IPv6. An Ookla latency server failure plus intermittent browser resets may be caused by upstream routing, receiver TCP state, unsupported UDP/fragmentation or site/server behavior. Record **Last upstream error** on sender and **Last TCP error** on receiver before treating a specific cause as proven. First try a basic IPv4 HTTPS page; only then run a speed test.

## Sender Internet path: mobile data versus Wi-Fi

**Connected to Wi-Fi on the sending phone does not imply Wi-Fi egress.** With **Use Android default route** off (default), DataNet Share explicitly resolves/connects over Android's selected **cellular Network**; if that Network is not available, outbound SOCKS5 requests fail even though the proxy and receiver VPN are connected. The sender displays `Mobile data` or `Waiting for mobile data` and its last upstream failure.

If you want the sending phone's other Wi-Fi connection to be used, stop sharing, enable **Use Android default route**, and restart. The sender now reports the current Android default transport (Wi-Fi, VPN or cellular). The Android default may change on Wi-Fi Direct startup, especially on single-radio/OEM configurations. The app does not guarantee that Wi-Fi Direct and an existing infrastructure Wi-Fi connection can coexist.

An upstream route failure can be translated by the experimental receiver into an ordinary TCP reset reported as `ERR_CONNECTION_RESET`. Read **Last upstream error** on the sender and **Last TCP error** on the receiver to distinguish route, SOCKS authentication, and TCP retransmission failures. The 5.1-dev.4 regression suite additionally checks a continuous 2 MiB+ download; Chrome speed test and actual handset throughput are still not certified.

## Troubleshooting

- **Receiver error `Could not protect sender socket` on 5.1-dev.1:** Use the 5.1-dev.2 preview, which prepares the local-link socket before requesting Android VPN protection (fix in [PR #3](https://github.com/Eun-si123/DataNetShare/pull/3)). Because debug signing keys are not stable, uninstall the old *development* package if updating is rejected; this clears its settings. Physical-device verification of the fix is pending. If it persists on 5.1-dev.2, ensure the receiver's Android VPN consent is granted and another VPN/always-on VPN is not taking ownership.
- **Wrong password / sender check fails:** Use the SOCKS5 password, not the Wi-Fi password. Check the sender address; `192.168.49.1` is only a starting suggestion and is not universal. Make sure both devices share the local link.
- **Wi-Fi Direct BUSY:** Another application/group may be using Wi-Fi Direct. Stop it or toggle Wi-Fi yourself, then retry. The app does not tear down another app’s group to take ownership.
- **Active sender, no internet:** Check mobile data and the displayed route status. Turn off the default-route option for explicit cellular routing, or inspect your VPN’s exclusions if intentionally using it.
- **Local address changed:** Stop, refresh addresses, select the new USB/Wi-Fi address and restart. The listener stays on its chosen address rather than automatically exposing a new interface.
- **UDP test fails:** A network may block external STUN or UDP. The test contacts `stun.l.google.com:19302`; failure alone does not identify which link failed or prove that all UDP is broken.
- **Unexpected VPN behavior:** Android permits one active VpnService per user/profile. Starting another VPN can revoke this receiver. Always-on/“block without VPN” settings and OEM battery restrictions require device testing.
- **APK update says incompatible:** Different debug signing keys cannot update one another. Use a consistently signed local development build or a coordinated release key. Uninstalling removes app settings.

## Development and provenance

- Kotlin/JVM; Android **8.0+ (API 26)**; compile/target SDK **34**.
- JDK **17**, Gradle **8.5**, Android Gradle Plugin **8.2.2**, Kotlin **1.9.22**.
- [`proxy-tests/`](proxy-tests/) runs real loopback socket tests without an Android SDK.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) explains routing, limits, credentials and service ownership.
- [`docs/TESTING.md`](docs/TESTING.md) records automated evidence and the outstanding device test matrix.
- The root `datnet-share-v5-final.zip` is retained only as an **unmodified historical source archive**. It includes obsolete code, README claims and legacy signing configuration. Do not execute its broken wrapper or reuse its signing material. The extracted maintained source and current docs supersede it.

## Privacy and license

No analytics or telemetry SDK is included. Normal proxy traffic goes to destinations the client requests; the receiver configures DNS `1.1.1.1` and the optional UDP diagnostic contacts Google STUN. DNS is not automatically DoH/DoT. SOCKS5 authentication is **not encryption**; prefer USB or a protected private Wi-Fi link. Credentials stay in process memory and on the unlocked screen unless you explicitly copy them. Receiver endpoint settings persist; proxy passwords do not.

MIT. Original upstream attribution remains in [LICENSE](LICENSE). No independent security certification is claimed.
