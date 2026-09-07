# 0022 — Recovery And Panic

**Status:** open

Two opposite needs: keep a broken task alive to find out what happened, and stop
everything immediately without caring why.

## Acceptance

- A failed task can be held in place with its state intact instead of cleaned up.
- One action stops every running task and every sidecar.
- Both leave the audit record complete.
- Neither requires knowing container names.

## Notes

Builds on the lifecycle operations that already exist - `task list`, `task stop` and `task resume`, and the same three over the daemon's socket.
