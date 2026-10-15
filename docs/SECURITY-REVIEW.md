# DataNetShare fork: source-based security and reliability notes

Date: 2026-10-16. Based on the v5 source archived in upstream `datnet-share-v5-final.zip`. Findings here are *code-review hypotheses*, not proof of exploitation or a device-level security certification. The bundled upstream `DataNetShare-Security-Audit.md` refers to v3.5/v4, **not** this v5 fork.

## Implemented in hardening branch

1. **Local-only SOCKS5 authentication:** Require RFC 1929 username/password on port 8282, generated afresh for each local session; show credentials on the sender screen. P2P mode is unchanged and NO_AUTH still applies over Wi-Fi Direct.
2. **Fail closed on upstream routing:** TCP no longer falls back to unbound sockets when cellular is absent or connect fails; UDP raises on missing/broken cellular binding. This reduces the risk of sending outside the intended mobile-data path (VPN-active routes still require runtime validation).
3. **Local-only speed-test listener disabled:** Previously an unauthenticated listener bound all interfaces on port 8283 and generated a fixed ~10 MB download for each connection.
4. **TCP ingress sanity:** Reject malformed IPv4/TCP/UDP lengths and duplicate/out-of-order TCP ingress payload rather than acknowledging/forwarding them as new data. This is *not* a complete TCP implementation.
5. **UDP response mapping guard:** Drop concurrent local flows to the same remote UDP endpoint if their source differs. This prevents an obvious cross-app response delivery, but **sacrifices UDP availability** in this case; real per-flow NAT is still required.
6. **UDP idle lifecycle:** Track activity on each UDP association rather than comparing global byte counters which could keep unrelated sessions alive.
7. **Release signing hygiene:** Remove hardcoded keystore path and leaked password fallback from unpacked Gradle sources; debug APK CI does not use release credentials.
8. **Source/CI:** Unpack readable source, add narrowly scoped JVM tests for sequence acceptance and UDP guard, and compile a debug APK in GitHub Actions.

## Residual risk / high-priority next phase

- SOCKS5 local credentials are transmitted **in cleartext** over the connected LAN. Password authentication mitigates unauthenticated use, but no confidentiality against local packet capture. Preferred safety option: USB and/or encrypted transport.
- Local-only proxy binds to **0.0.0.0**. It requires a password, but the TCP port is still reachable on any interface. Restrict to an explicitly chosen USB/Wi-Fi interface before shared-network use.
- Wi-Fi Direct mode relies on the group password and accepts SOCKS5 NO_AUTH traffic from any group participant.
- TCP bridge lacks sender-side retransmission, receive-window accounting, out-of-order queueing and robust half-close semantics; investigate established tun2socks/netstack libraries instead of a bespoke TCP stack.
- UDP multiplexing needs per-original-flow upstream socket identity; guard intentionally blocks a second app using an already occupied remote endpoint.
- Full fail-closed VPN routing is **not** guaranteed when the sender's VPN turns on/off mid-session or excludes the app.
- Embedded upstream v5 ZIP and upstream README include old signing-credential material. Remove/rotate in a future coordinated release if appropriate; simply deleting plaintext from new Gradle files does not erase Git history or archived ZIPs.
- Verify whether the provided APK matches the source and is signed with the expected certificate before trusting it.

## Validation plan

1. Confirm CI JVM tests and Android debug build pass; inspect the failure logs before changing APIs.
2. Run sender+receiver on isolated owned test phones. Verify correct startup/stop, Wi-Fi Direct and USB, 30-minute idle, screen off/on, airplane mode and lost cellular connectivity.
3. From an authorized second computer on the same test LAN, verify unauthenticated SOCKS5 handshakes receive 0x05 0xFF in local-only mode, invalid credentials fail, and valid credentials succeed.
4. Use synthetic packet loss, reordering and duplicated segments to check TCP file hash integrity, no wrong-app UDP delivery and socket cleanup.
5. Check packet destination and VPN behavior when switching cellular/Wi-Fi/VPN. Do not assume an upstream failover is secure without a captured trace.
6. Review APK dependency supply chain, manifest, permissions, certificate and binary/source equivalence. This static review is not dynamic malware analysis.

## Scope note

No carrier-specific tethering bypass claims were validated; policy compliance and behavior depend on subscription and network conditions. Do not conduct intrusive tests on other people's networks.
