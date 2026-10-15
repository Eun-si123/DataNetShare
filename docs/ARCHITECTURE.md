# DataNet Share 5.1-dev architecture

## Ownership and lifecycle

`MainActivity` observes an in-process `SessionStore` with one sender/receiver owner. Reopening/rotating the activity does not start a second service to query state. Starting a new mode is refused while another mode is starting/active. The sender and receiver are foreground services with Stop notification actions; they use `START_NOT_STICKY` so Android does not resurrect an empty session after killing them.

`SenderService` owns the Wi-Fi Direct controller, proxy sockets, cellular request and wake lock. `ReceiverVpnService` owns the consent/link probe, TUN descriptor, flow bridge and wake lock. Failure stops the corresponding resources and preserves the failure message. UI Stop uses `stopService` rather than launching three new foreground services with STOP intents. Proxy and Wi-Fi credentials are never included in diagnostic logs or notifications.

`WifiDirectHost` uses Android's reported `groupOwnerAddress`. A session generation check rejects delayed callbacks after Stop. It retries BUSY with bounded backoff, has a setup deadline, and removes only a group it created. It does not take over another application's existing group.

## Sender data path

`Socks5Server` has no Android dependency. Its `Upstream` interface supplies TCP connections, DNS resolution and UDP sockets; `AndroidUpstream` supplies the phone-specific routing.

- Default mode requests and retains an internet-capable cellular Network. DNS uses `Network.getAllByName`, TCP uses its socket factory, UDP uses `Network.bindSocket`. No silent unbound fallback occurs if cellular disappears.
- Optional default-route mode uses Android's normal resolver/sockets. This is for intentionally using an existing VPN or other default route, with no VPN privacy/kill-switch guarantee. Route changes are handled by Android; existing TCP connections may break.
- Both Wi-Fi Direct and local modes bind the proxy to one explicit local IPv4 address and require RFC 1929 username/password (`datanet` plus 16 random bytes encoded as 32 hex characters). Credentials rotate each session.
- TCP relay propagates EOF via `shutdownOutput`, preserving the other direction until it finishes. Stop closes tracked client/upstream sockets, including idle handshakes. A connect attempt already inside a platform connect/DNS operation can take until its deadline before its worker returns.
- Each UDP association belongs to its TCP control connection and one pinned UDP client endpoint. Replies come from connected destination sockets, not arbitrary remote senders. The relay address returned is the listener's actual local address, never `0.0.0.0`.
- Limits: 64 simultaneous SOCKS control clients; 32 remote UDP destinations per association; 128 upstream UDP destination sockets globally; 15-second authentication timeout; two-minute UDP idle expiry. A new socket/packet beyond a limit is rejected/dropped. UDP receive capacity resets for every datagram.

## Experimental Android receiver

The receiver accepts an explicit IPv4 sender address and password. Before creating the VPN, a protected socket connects and verifies SOCKS5 credentials. It then installs an IPv4 default route, DNS `1.1.1.1`, MTU 1500 and a nonblocking TUN descriptor. A finite `poll` timeout waits for packets instead of treating `EAGAIN` as a permanent tunnel failure. The read loop uses an owned duplicate descriptor; the service owns the original.

Proxy sockets are protected from the receiver's own VPN and bound to a non-cellular local Network whose link prefix contains the sender. If Wi-Fi Direct is not represented as an Android Network, an explicit matching local interface address is used. Failure to find/protect a local link aborts the connection instead of recursively tunneling it.

`Ipv4Packet` validates header/transport lengths and rejects fragmented IPv4 packets. `IpPackets` creates return packets and checksums. IPv6, ICMP and IP fragment reassembly are absent; the VPN does not enable an IPv6 bypass.

`TcpFlow` handles one original IPv4 four-tuple. App-to-proxy data is accepted in sequence, trimmed when partially duplicated and acknowledged only after fitting in a bounded queue. Its advertised receive window reflects queued bytes. Server-to-app segments are retained until acknowledged, retransmitted with bounded backoff, and limited by the advertised peer window. SYN/FIN consume sequence space; serial arithmetic wraps at 32 bits. Local FIN drains queued writes and half-closes the upstream; the response remains readable until its own FIN is acknowledged. A shared maintenance thread handles retransmission, zero-window probes and idle expiry. Closed flows are removed from the map.

This remains a small custom TCP implementation: no full congestion-control algorithm, window-scale negotiation, SACK, IP reassembly, TCP Fast Open or general RFC interoperability certification. It should remain labeled experimental until tested against real Android apps and adverse links. A maintained native tun/netstack may be a better long-term foundation; replacing this bridge is a separate licensing/build/ABI decision.

`UdpFlow` creates a separate SOCKS5 association for each original IPv4 four-tuple, preventing cross-app response collisions at a common DNS/game endpoint. The first packet is queued while the association opens. A connected relay socket filters the peer; responses are checked against the original destination. Idle/control-close/failure closes both TCP control and UDP sockets, including partial initialization. Responses above the 1472-byte IPv4 UDP payload limit are dropped because fragmentation is not implemented.

Receiver limits: 64 TCP flows, 32 UDP flows, up to 65535 queued TCP upload bytes per flow, up to 65535 unacknowledged download bytes constrained by the peer window, 32 queued datagrams per UDP flow. Idle flows expire after two minutes. Individual flow errors close that flow; TUN read/write failure stops the service visibly.

## Build and package identity

The maintained source is Kotlin-only, minSdk 26 / targetSdk 34. Debug builds use `com.datanet.share.dev`, version `5.1-dev` / versionCode 31, and do not replace upstream packages. Release builds are unsigned until a private signing process is configured. CI uploads development artifacts, not a public release.

## Primary references

- [Android Network: DNS resolution, socket factory and bindSocket](https://developer.android.com/reference/android/net/Network)
- [Android Wi-Fi Direct: groups, permissions and reported owner address](https://developer.android.com/develop/connectivity/wifi/wifi-direct)
- [VpnService.Builder: nonblocking descriptors and IP families](https://developer.android.com/reference/android/net/VpnService.Builder)
- [SOCKS5 RFC 1928](https://www.rfc-editor.org/rfc/rfc1928)
- [Username/password RFC 1929](https://www.rfc-editor.org/rfc/rfc1929)
