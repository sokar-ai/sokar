# 0043 — Workspace Outlives Its Container

**Status:** open

A task's workspace is created inside its container: the clone runs in the container
and `/workspace` is part of the container's filesystem, not a mount. So removing the
container destroys whatever the agent has done and not yet pushed through the gate,
and there is no way to get it back.

That makes `podman rm` - or `sokar task stop --purge`, or any cleanup an operator runs
without thinking hard - a destructive operation on work nobody agreed to throw away.
It also means a task cannot be rebuilt around what it had: with the container gone,
there is nothing left to rebuild around.

## Acceptance

- Work that has not been pushed survives removing the container.
- A task can be recreated on a rebuilt image while keeping what the previous container
  had in its workspace.
- Nothing that already works stops working: the gate stays the way reviewed work
  reaches the operator, and the workspace does not become a second, quieter route out
  of the container.
- Removing a workspace is something the operator asks for by name.

## Notes

Found on 2026-09-04 while adding `task resume`. Resuming a stopped container works
precisely because the container still exists; the moment it does not, the task is
gone. The obvious repair - a host directory mounted at `/workspace` - is not obviously
right, because it puts a writable host path inside the container and the containment
argument then has to cover it: what the agent may write there, who else can read it,
and what happens to a file it leaves behind.

The alternative worth weighing is that this is correct as it stands, and the gate is
the answer: work reaches the operator by being pushed, and anything not pushed is by
definition not finished. That has the merit of one route out instead of two.

## To be checked

- Whether a mounted workspace weakens containment enough to matter, given the agent
  already reaches a git endpoint and a credential broker.
- Whether the same argument applies to what the agent installs *in* the container -
  packages, caches - which is lost with it today and is the reason resume keeps the
  old image.
