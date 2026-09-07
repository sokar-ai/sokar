# 0022 — Recovery And Panic

**Status:** open

Two opposite needs: keep a broken task alive to find out what happened, and stop
everything immediately without caring why.

## Acceptance

- A failed task can be held in place with its state intact instead of cleaned up.
- One action stops every running task and every sidecar.
- Both leave the audit record complete.
- Neither requires knowing container names.

## To be checked

- **What the agent installed *in* the container is lost with it, and nothing warns.** Packages,
  caches, a built toolchain: removing a task destroys all of it, and unlike the workspace there
  is nowhere for it to arrive. Holding a failed task in place covers the case where somebody
  decides to keep it; whether anything should say what is about to be lost, the way removal now
  does for unpushed work, is inherited from the requirement that fixed the workspace half and
  is not answered here.

## Notes

Builds on the lifecycle operations that already exist - `task list`, `task stop` and `task resume`, and the same three over the daemon's socket.
