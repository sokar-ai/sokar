# B169 — Containers Inside A Task

**Status:** open.

**What must be true.** A build inside a task can start containers of its own - rootless podman in the task - and they
are held by the same firewall and resolver as the task. Before it is built, an assessment says whether Sokar's
guarantees still hold.

## Why

Many Java builds start containers for their tests, and such a build cannot run in a task today. Comparable tools allow
containers inside their sandbox.

## The shape

- First the assessment: what nested user namespaces open, whether a nested container can leave the task's network
  namespace or reach the host's, what it means for seccomp and capabilities, and whether a kernel of the task's own
  (B59) is the better ground.
- Only then the build: opt-in per project, said by `--dry-run` and `sokar doctor`, off by default.
- Size: large.

## Acceptance

- The assessment, written, with each guarantee of `doc/reach.md` checked against nested containers.
- If built: seen to fail first, then green - a container started in a task reaches a declared host and is refused an
  undeclared one, exactly like the task.

## To be checked

- Whether the assessment ends in "not without B59".
