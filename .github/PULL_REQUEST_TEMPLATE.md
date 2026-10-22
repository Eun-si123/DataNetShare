## Purpose

What confirmed problem or user benefit does this PR address? Link the relevant Issue, if any.

## Scope and safety

- Changed behavior and affected sender/receiver/network paths:
- Protected invariants (SOCKS authentication, credential privacy, socket/VPN lifecycle, bounds, fail-closed behavior):
- Alternative considered, including no change:
- Rollback plan:

## Evidence (check only what actually ran)

- [ ] Relevant host/JVM protocol tests
- [ ] Android unit tests / lint / debug build
- [ ] Relevant negative-path, concurrency or recovery tests
- [ ] Real physical Android devices (describe exact models, versions, networks and observations below)
- [ ] Not applicable or not run (explain below)

**Exact tests/CI links and outcomes:**

**Not tested / remaining risks:**

## Privacy and release

- [ ] PR text, diffs, fixtures and logs contain no passwords, QR/session payloads, tokens, personal identifiers or private network data.
- [ ] This PR does not claim production readiness, carrier-plan behavior, universal app compatibility or speed gains without matching evidence.
- [ ] Release signing, publishing and other external side effects are explicitly called out if relevant.

See [AGENTS.md](../AGENTS.md), [agent workflow](../docs/AGENT_WORKFLOW.md), and [testing](../docs/TESTING.md) for the actual project-specific validation contract.
