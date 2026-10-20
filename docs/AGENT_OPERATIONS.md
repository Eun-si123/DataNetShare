# Agent discovery, concurrency and independent review

This is a **small GitHub-native extension** to the existing AGENTS.md and specialist engineering/testing guides. It does not restore the removed local MCP/SQLite control plane, install a background LLM, deploy anything, or grant automatic merge/release permissions. Live code, PRs, tests and project-specific safety rules remain authoritative.

## 1. Discover useful work without manufacturing activity

- `python3 scripts/agent-ops.py scan` scans committed source areas, surfaces bounded TODO/FIXME/HACK hints, and ranks areas without a review receipt or with changes since a verified review commit. Markers are only leads; investigate the actual failure before writing code or opening an Issue.
- `python3 scripts/agent-ops.py scan --remote` additionally queries open GitHub PRs **and their changed files** and marks overlapping areas. Requires `GITHUB_TOKEN` (or `GH_TOKEN`); missing/failed/truncated API reads fail closed. The GitHub Actions scheduled scan uses the ephemeral read-only token and posts its result in the job summary, not in an ever-growing status commit.
- Choose the highest-impact **confirmed** bug, a meaningful unreviewed area, or a specific improvement backed by tests. Compare with no change. A candidate rank is not an authorization, proof of a bug, or a request to maximize commits.
- The schedule is best effort and only discovers candidates. It does **not** launch a coding agent or guarantee a particular completion time. Existing external hourly development agents must explicitly consult these results and current source.

## 2. Coordinate independent agents

1. Before claiming work, check fresh PRs, Issue scope, CI and current branch/worktree. TeamForge-specific queue/claim rules continue to apply where available.
2. Use a distinct task branch/worktree. Run `python3 scripts/agent-ops.py preflight --area AREA --branch agent/AREA/short-task [--issue N]` with a GitHub read token before editing. A conflicting open PR touching the area blocks automatic selection, even if its branch name looks unrelated.
3. Prefer no simultaneous edits to the same area. If parallelism is necessary, explicitly separate independent files and test contracts; coordinate via Issues/PRs rather than editing the same checkout. GitHub Actions `concurrency` covers its own workflow group only; neither it nor Issue comments form an atomic lock across external agents. **Recheck** immediately before writes/PR submission.
4. Do not discard any agent's branch, force-push over others, close unfamiliar Issues/PRs, or resolve conflicts by overwriting work. API/auth/pagination errors mean *unknown*, not available.

## 3. Independent review is separate from authorship

- `python3 scripts/agent-ops.py review --base origin/main` performs a separate diff/whitespace and risk-sensitive path review, identifying source changes without changed test files. It returns failure for bad `git diff --check`; warnings require investigation, not automatic code rejection.
- The GitHub Actions PR job runs this checklist and its isolated tests. Product-specific CI must still run to validate actual compilation/unit/integration behavior. Prefer a **different agent or reviewer** for semantic threat-model, edge-case and regression analysis when the change is important.
- Record exact-source test/CI links, negative paths, what was **not** tested, physical-device/Unity/Windows limits, and rollback plans in the PR. An automated checklist is not independent physical test evidence or a security audit.

## 4. Durable inspection receipts (no database)

- An agent may write an inspection record **only after actual review**: on a clean checkout at the reviewed commit, `python3 scripts/agent-ops.py record --area AREA --evidence 'tests/checks and PR or report link'`. This writes `.github/agent-inspections/AREA.json` on the current task branch; review and merge it normally.
- Each receipt records the inspected commit, UTC time and bounded evidence. Scans use Git ancestry/diff to detect changes since that commit and automatically raise the area for reinspection. A stale, nonancestor or invalid receipt cannot prove anything.
- Never write a receipt merely because a scan passed; do not put tokens, session URLs, user IP addresses, raw diagnostics or private paths in receipts (including private repos that may be published later). Record findings in the existing Issue/PR/architecture owners instead of duplicating the full evidence here.
- Review receipts are separate from tests and release readiness. Initial missing receipts are expected; let them accumulate through real investigations rather than bulk stamping all areas 'reviewed'.

## Maintenance and recovery

`python3 -m unittest discover -s scripts -p 'test_agent_ops.py'` verifies discovery, stale-receipt detection, invalid paths, diff checks and conflict behavior with temporary Git fixtures. The scanner uses Python standard library and local `git`, no new service, vector DB, SQLite, or runtime dependencies. The scheduled job performs read-only HTTP GETs, does not write Issues/PRs, and produces no Actions artifacts (avoids storage churn).

When an agent is interrupted, use the project's existing handoff/checkpoint mechanism. Before continuing verify live commits, working changes, PR and CI; a saved inspection receipt or advisory preflight is not a substitute for fresh observation.
