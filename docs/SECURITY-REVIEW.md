# Security and privacy notes — 5.1-dev

Updated 2026-10-16 KST. These notes describe the maintained source and its boundaries. They are not an independent audit, malware certification or proof of production safety. Any older audit bundled in the upstream ZIP describes an earlier version.

## Implemented controls

- **Authenticated in both sender modes:** RFC 1929 user `datanet`, fresh random 32-hex-character password each session. Wi-Fi group credentials are distinct from proxy credentials.
- **Local scope:** Bind to the Wi-Fi Direct group address or an explicitly selected USB/Wi-Fi IPv4 address. No wildcard listener. No unauthenticated bulk speed-test listener is started.
- **Cellular routing by default:** Resolve names and create/bind outbound sockets using a cellular Android Network. Refuse missing/broken cellular routing; no silent default-network fallback. The user can explicitly opt into Android default routing.
- **Bounded resource use:** Connection, UDP destination, flow and queue limits; handshake/connect/idle deadlines; Stop closes tracked sockets and releases wake locks/network requests. No process-wide routing or phone security settings are changed.
- **Flow separation:** Per-original-flow receiver UDP associations and sender association-local destination sockets replace the previous ambiguous shared UDP NAT. Source endpoint validation and connected relay/upstream UDP sockets restrict unsolicited packets.
- **Packet handling:** Validate IPv4/TCP/UDP sizes and offsets; reject fragmented packets; suppress/trim duplicate TCP ingress; track acknowledgements and retransmissions; preserve half-close. This is still an experimental custom netstack.
- **Lifecycle:** Probe credentials before installing VPN routes; fail on socket protection/link selection failures; stop on TUN failure; stale Wi-Fi callbacks cannot restart stopped sessions. Sender/receiver startup is mutually exclusive.
- **Credentials/signing:** No release key/password fallback in the maintained build. Debug package is separate. Passwords are not saved to preferences or logs; notifications omit them. Explicit clipboard copying is a user action.

## Boundaries that matter in use

**SOCKS5 authentication is plaintext, not transport encryption.** Someone able to capture the local link can see credentials and unencrypted application data. Use USB or a protected private wireless link. HTTPS provides its own application encryption; SOCKS5 does not add it. Do not expose the port via public forwarding.

**The default-route option is not a VPN kill switch.** A running VPN may exclude this app, disappear, or change its underlying network. The app deliberately follows Android in this optional mode; verify the actual traffic path if privacy matters. Default mobile-only mode explicitly binds to cellular and therefore does not imply use of an existing sender VPN.

**Built-in receiver is experimental and IPv4-only.** The custom TCP stack negotiates bounded Window Scaling when offered by the peer; it lacks full congestion control, SACK, IP reassembly and broad interoperability coverage. The unreleased loss-recovery regression tests also cover scaled duplicate ACKs and exclude FIN from fast-retransmit detection (see [TESTING.md](TESTING.md)). It does not tunnel ICMP or IPv6. Oversize UDP replies are dropped. Do not infer “all apps/games work” from unit tests or a green build.

**No carrier accounting guarantee.** Tethering detection and allowance depend on the carrier/plan/device/network. No Korean carrier quota or policy behavior has been verified.

**Historical archive remains historical.** `datnet-share-v5-final.zip` is retained for provenance, including obsolete signing configuration and a broken wrapper. Those files are not part of the maintained build. A clean new Gradle file does not erase old material from Git history or archives; do not reuse legacy keys/passwords.

**Debug builds are not releases.** Validate source, commit, APK package and signing certificate. CI-runner debug keys are not a stable update identity. A release requires a private signing process, compatible upgrades and device testing.

## Session QR credentials (5.1-dev.3)

The sender shows its connection QR only after the user taps **Show connection QR** during an active sharing session. The code includes plaintext SOCKS5 password and, if available, Wi-Fi Direct SSID/passphrase. **Hiding the image does not revoke the password.** A photographed QR can be reused until the sender stops/restarts and rotates its 128-bit random session password. This is session-scoped, **not a single-scan pairing token**; that would require a distinct sender-side one-redemption exchange and replay protection.

The receiver's local ZXing scanner accepts only a size-bounded, versioned DataNetShare payload with validated IPv4, port and passwords. It fills form fields but never joins a wireless network or enables an Android VPN without user interaction. The transient form and pending VPN consent live only in an Activity ViewModel and are not written to SharedPreferences or saved instance-state. QR contents are not uploaded to a service, logged or sent to analytics. An explicit **Copy Wi-Fi password** action does use Android clipboard. A nearby observer or screenshot can still capture a visible code; do not share a screenshot publicly.

Camera permission is needed only for scanning. Real camera, OEM, and phone-to-phone networking compatibility remain unverified by JVM tests.

## Privacy behavior

No analytics/telemetry SDK is declared. The receiver configures `1.1.1.1` as its DNS resolver; this alone does not enable encrypted DNS. The optional explicit UDP diagnostic contacts `stun.l.google.com:19302`. The sender resolves client-requested hostnames through its selected upstream. Addresses/route counters are visible in the UI; traffic contents are not intentionally logged. Copying credentials puts them in Android's clipboard and may expose them to clipboard features.

## Validation

See [TESTING.md](TESTING.md) for checks actually run and the outstanding device matrix. Loopback tests exercise authentication, TCP data integrity/half-close, UDP separation/size changes, first-packet delivery, duplicate/loss handling and cleanup. They do not exercise Android permissions, real TUN scheduling, OEM Wi-Fi Direct, cellular/VPN transitions or battery behavior.
