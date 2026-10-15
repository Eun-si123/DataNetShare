# DataNetShare Android source (fork maintenance branch)

This `android/` project is extracted from the upstream `datnet-share-v5-final.zip` source archive. The original ZIP remains in this repository for provenance. Unlike the upstream source README, the extracted v5.0 code contains **no native C++ build**.

## Build & tests

Requires JDK 17, Android SDK platform 34, build tools 34.0.0, and Gradle 8.5. The upstream ZIP contained `gradle-wrapper.jar.broken` instead of a usable wrapper. We deliberately do **not** publish or execute that binary; use a locally installed verified Gradle 8.5, or the GitHub Actions workflow.

```sh
cd android
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

This generates an **unsigned release** if `assembleRelease` is run. For distribution, sign the APK using a **new private signing key outside the repository**, or add a secure environment-based signing process. The original ZIP exposed a legacy default signing password; do not reuse those legacy credentials.

## Changed local-only SOCKS5 authentication

`START LOCAL ONLY (USB/WiFi)` generates a new random 32-character password for each session. The app displays the password in its credentials panel. Configure your desktop SOCKS5 client with:

- Proxy host: the phone's USB tethering or local Wi-Fi IP (shown under interfaces)
- Port: `8282`
- Username: `datanet`
- Password: the random password shown in the sender app

If your client supports only unauthenticated SOCKS5, **local-only mode will no longer connect**. This change is intentional. A SOCKS5 password does **not encrypt** the proxy protocol, so avoid untrusted/shared Wi-Fi; prefer USB or a private Wi-Fi Direct group. Wi-Fi Direct sender mode still uses unauthenticated SOCKS5 inside its WPA-protected P2P group and is not a fully authenticated transport.

## Known limitations (do not treat as production-ready)

- TCP bridge implements in-order duplicate suppression, **not** complete TCP flow control, retransmission, congestion control, and half-close semantics.
- UDP currently multiplexes upstream traffic by destination host/port. A defensive guard drops a conflicting second local client instead of allowing wrong-client responses. Full support requires separate per-flow upstream UDP sockets or a redesigned relay.
- No IPv6 tunnel support.
- Sender VPN routing is detected only when SOCKS server starts; VPN behavior can change later. Wi-Fi Direct traffic uses WPA protection only, no application-layer encryption.
- Runtime testing across OEMs, Android releases, packet loss/reordering, prolonged idle, VPN combinations and Korean carrier plans remains outstanding.

Refer to `docs/SECURITY-REVIEW.md` for a prioritized threat model and a safe test checklist. This branch has not been independently certified or tested on a real Android device.
