# B05 — Health And Diagnostics

**Status:** open

Several dependencies fail silently rather than loudly: a missing capability in a
resolver, a policy that blocks a mount, a stale copy of a binary shadowing the
installed one. Each produces a symptom far from its cause.

## Acceptance

- Every external dependency is probed and reported by name.
- A capability that is present but compiled out is detected, not assumed from a
  version number.
- Shadowed binaries are named, with what is hiding what.
- Each failure carries the single next action.

## Notes

Where a probe cannot answer, it must say so rather than assume the good case.

## To be checked

- **Whether an operator may refuse a working machine that is weaker than it should be.**
  Inherited from the git gate: where podman connects a rootless container with slirp4netns
  rather than pasta, the host's loopback cannot be mapped, so `task run` binds the gate on
  every interface and says so rather than refusing. That keeps the task working at the cost
  of an endpoint on the local network. Whether a machine in that state should be reported as
  merely degraded, or be allowed to refuse to run a task at all, is a question this
  requirement inherits rather than one it invented.
