# DataNet Share — product direction and open solution space

> **Purpose, not a feature freeze.** For current behavior, supported devices, test evidence, compatibility and release readiness, use [README](../README.md), [architecture](ARCHITECTURE.md), [testing](TESTING.md) and live source/CI. This document does not certify a new capability.

## Why this project exists

Make sharing one Android device's permitted Internet connectivity with another device easier, safer, more dependable and easier to diagnose across realistic conditions. The maintained fork currently provides authenticated SOCKS5 sharing over supported local connection types and an experimental Android VPN receiver. The **user outcome** matters more than retaining any particular proxy implementation, transport, screen flow, code shape or release-number sequence.

## Freedom to explore and invent

There is **no fixed list of allowed technical ideas, algorithms, features, platforms, architectures or future Phases**. When an active task includes exploratory scope, an agent may:

- discover needs or failure modes that were never written down by the maintainer;
- challenge assumptions in existing code, Phase plans or historical source;
- propose or compare native protocol backends, different sharing paths or user flows, interoperability improvements, recovery methods and fundamentally different designs;
- improve maintainability, accessibility, diagnostics, performance, automation and developer tooling alongside product features;
- recommend rejecting an old idea or doing nothing when that better serves users.

These examples are **invitations, not boundaries or required checkboxes**. Creative alternatives should be evaluated rather than dismissed merely because they are unusual, outside the current roadmap or not explicitly requested.

## How to judge a candidate

Explain the concrete user benefit, relevant platform limitations and what observation could show the idea is wrong. Compare simpler approaches and current behavior where useful; prototype or test reversible changes before asserting broad results. Distinguish source/JVM evidence, Android builds and actual two-device network validation.

Security, authentication, user privacy, data integrity and Android OS permissions must not be silently weakened for convenience or benchmark numbers. If a proposal fundamentally changes the product's purpose or public promises, obtain an explicit user choice **before the pivot**; exploring or presenting such ideas remains welcome.

This document **does not expand the authorization of an unrelated agent session**. The existing optional broader-exploration/token-cost rules in [AGENTS.md](../AGENTS.md) and [agent workflow](AGENT_WORKFLOW.md) still apply, as do separate approvals for irreversible operations, releases, exposure or spending.
