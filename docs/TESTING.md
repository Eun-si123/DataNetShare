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

## Current evidence

Validation of the 5.1-dev candidate is in progress in the maintenance branch. Inspect the latest source-verification workflow and its test/lint reports before relying on its APK. The older 5.0 source-baseline build is not evidence for the rewritten 5.1-dev runtime.

CI uploads the debug APK only after tests, lint and compilation succeed. Test reports are also uploaded on failure. A green build demonstrates those checks, not OEM Wi-Fi Direct compatibility, stable phone routing or carrier quota behavior.

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
