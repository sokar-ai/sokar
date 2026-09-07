# F06 — Upstream Synchronisation And Backups

**Status:** open

Bringing a project's working copy back in line with its upstream, and recovering when
that goes wrong.

## Acceptance

- Synchronising with the upstream is one action, with progress and outcome visible.
- Staleness is reported before the person asks — a project behind its upstream says so
  in the overview.
- Snapshots taken before a synchronisation are listed, with enough information to tell
  them apart: when each was taken and what it holds.
- Any listed snapshot can be restored, and any can be deleted, both behind a
  confirmation that names what is affected.
- A synchronisation that would discard work says so before running, not after.

## Notes

This is the one place where a wrong action loses work that exists nowhere else. Every
destructive path here is confirmed, and every confirmation names the specific thing.
