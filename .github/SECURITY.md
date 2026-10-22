# Security policy

DataNetShare is an actively developed Android networking project. The maintained code lives under `android/`; historical archives are not supported release artifacts. There is no production-ready or long-term-support version at present.

## Report vulnerabilities privately

**Do not file public Issues or Discussions for exploitable vulnerabilities.** Use GitHub's [private vulnerability reporting](https://github.com/Eun-si123/DataNetShare/security/advisories) to send a confidential report to the maintainers.

Include the affected source revision or build, a concise impact assessment, and reproduction steps that use test credentials and devices. Do not include real SOCKS or Wi-Fi passwords, QR payloads, tokens, private IP addresses, other users' traffic, or identifying logs. If evidence contains secrets, redact it before submitting.

Regular defects and compatibility issues that do not expose a vulnerability may use the public bug-report template.

## Security boundaries

SOCKS5 authentication does **not** encrypt a connection. The Android VPN receiver is experimental and IPv4-only; testing or source review does not establish broad device/carrier compatibility. Debug APKs and historical source archives are not signed production releases.

See [security and privacy notes](../docs/SECURITY-REVIEW.md) and [testing limitations](../docs/TESTING.md) for current evidence. Security reports are assessed individually; no fixed response timeline or unverified support guarantee is implied.
