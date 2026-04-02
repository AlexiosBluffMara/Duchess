# .memory/ — Shared Context Bridge

This directory is the **live communication channel** between GitHub Copilot and Claude Code.
Both tools read and write here. It is **tracked by git** — push/pull to sync across sessions.

## Files

| File | Owner | Updated when |
|------|-------|-------------|
| `handoff.md` | Both | **End of every session** — before conversation compacts |
| `project-state.md` | Both | When a deliverable completes or state changes significantly |
| `claude-queue.md` | Both | When tasks are added, started, or completed |
| `decisions.md` | Both | When an architectural or technical decision is made |

## Protocol

### Starting a session
1. Read `handoff.md` — restore context from last session
2. Read `project-state.md` — understand what's done/blocked
3. Read `claude-queue.md` (if Claude) — pick up the next deliverable

### Ending a session (BEFORE compaction)
1. Update `handoff.md`:
   - What was accomplished this session
   - What is currently in-progress (with file paths if possible)
   - Any decisions or gotchas discovered
   - What should be picked up next
2. Update `project-state.md` if state changed
3. Commit `.memory/` changes: `git add .memory/ && git commit -m "memory: update session handoff"`

## Rules

- **No PII** in context docs (worker names, locations, etc.)
- **No real secrets** — reference where they live (local.properties, AWS SM) but never paste values
- **Keep handoff.md < 200 lines** — scannable, not a novel
- **Timestamp every handoff entry** with ISO date (YYYY-MM-DD)
- Both tools should commit memory updates before ending work so the other can pick up
