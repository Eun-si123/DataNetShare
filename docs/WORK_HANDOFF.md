# DataNet Share — resumable work handoff

This file is **temporary working memory**, not the source of truth for the latest implementation, release, build or field-test status. Keep it short; update only for substantial work that must survive an interruption, and confirm actual GitHub HEAD before continuing. Do not overwrite another agent's active task.

## State at creation — 2026-10-19

- **Task:** add an agent-friendly routing, memory and handoff foundation without changing Android behavior.
- **Baseline inspected:** public `main` at `be234dcdb3808ebe3fd94f5c1d60eb1dcea91823` (5.1-dev.21 source). This is a **snapshot**, not a perpetual pointer to main.
- **Scope:** root `AGENTS.md` and agent workflow, code map, durable memory, temporary handoff documentation only. No runtime, tests, build configuration or workflows are intentionally changed.
- **Current work owner:** the agent-process docs PR that introduced these files (inspect live PR/commit before resuming); do not invent a second concurrent documentation PR.
- **Verification boundary:** verify new Markdown files/relative links and diff in GitHub. There is **no claim** that Android builds, emulator or two-phone tests ran for this docs-only change.
- **Follow-up:** after merging, future coding agents should use this file **only when a concrete unfinished task exists**. If no active task, record `No active handoff` rather than accumulating old summaries.
- **Existing hardware/product backlog:** [TESTING.md](TESTING.md); user-facing current preview [README](../README.md). Do not duplicate detailed test scenarios here.

## Active handoff

No product implementation task is claimed by this document. Before starting new work, inspect current Issues, PRs, branch/HEAD, tests and the relevant canonical doc.

### Template for a future interrupted task

- Goal and acceptance condition:
- Repo/branch/HEAD and clean or dirty status:
- Exact changed paths and committed/uncommitted work:
- Evidence/tests **actually run**, results, and unrun checks:
- Blockers, user decision or physical-device evidence required:
- Next safest action / related PR or Issue:

Remove or condense a completed checkpoint only after ensuring that no other agent still needs it; promote lasting lessons to [AGENT_MEMORY.md](AGENT_MEMORY.md).
