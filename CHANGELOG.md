# Changes

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
