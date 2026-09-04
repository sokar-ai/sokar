# 0002 — Fleet Overview

**Status:** open

A single view, sorted by what needs attention first. Each row carries enough to
decide whether to open it: project, agent, security class, how long it has run, and
what it is doing right now.

## Acceptance

- Tasks appear and disappear as they start and end, without a manual refresh.
- A task waiting for a decision sorts above one that is working.
- The row says which security class the task runs under, because the same agent
  behaves differently under each.
- Tasks that ended recently remain visible long enough to see why they ended.

## Notes

This is the screen the application opens on. If it is wrong or stale, nothing else
in the interface is trusted.
