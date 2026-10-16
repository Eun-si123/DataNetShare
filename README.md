# DataNet Share · maintained fork

[한국어 사용 안내](docs/README.ko.md) · [Build](android/README.md) · [Tests and device checklist](docs/TESTING.md) · [Security and privacy](docs/SECURITY-REVIEW.md)

Share an Android phone’s connection through an **authenticated SOCKS5 proxy**, over Wi-Fi Direct, USB tethering, or a private Wi-Fi network. The same app also includes an **experimental Android IPv4 VPN receiver**.

This is [Eun-si123’s fork](https://github.com/Eun-si123/DataNetShare) of [Keggin-AI/DataNetShare](https://github.com/Keggin-AI/DataNetShare), based on the archived upstream v5 source. The actively maintained project is in [`android/`](android/). **5.1-dev is a development candidate, not a certified production release.** Foundation and build evidence are in [PR #1](https://github.com/Eun-si123/DataNetShare/pull/1); the receiver socket hotfix is in [PR #3](https://github.com/Eun-si123/DataNetShare/pull/3).

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

**Easy install:** Open [GitHub Releases](https://github.com/Eun-si123/DataNetShare/releases) and select the `v5.1-dev.6` **pre-release**. Under **Assets**, download `DataNetShare-5.1-dev.5-debug.apk` directly (not either Source code archive). `SHA256SUMS.txt` is provided for integrity verification. The preview is a debug-signed test build for Android 8.0+; its signing key is not stable across future builds, so future updates may require uninstalling this package and losing local settings. It is **not** a production-certified app.

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
