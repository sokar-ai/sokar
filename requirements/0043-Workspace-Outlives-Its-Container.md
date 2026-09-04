# 0043 — Workspace Outlives Its Container

**Status:** open

A task's workspace lives inside its container: the clone runs in the container and
`/workspace` is part of the container's filesystem, not a mount. Removing the container
therefore destroys whatever the agent has done and not yet pushed, and nothing warns
anyone first. `sokar task stop --purge`, a `podman rm`, or a tidy-up script all do it
silently.

The harm is not that the workspace is unreachable from the host. It is that work can be
destroyed by a command whose stated job is cleanup.

## Acceptance

- Removing a task reports what would be lost, and does not proceed by accident.
- Work that has not been reviewed can still be preserved when a task is removed,
  arriving the same way all other work arrives.
- The container stays the only place agent output exists until it is pushed, so a
  reviewer still sees everything before it reaches the host.
- What is preserved is distinguishable from what was finished, so a rescued workspace
  is never mistaken for work an agent offered up.

## Notes

Two ways to fix this, and they differ in what they give away.

**Preserve on removal, keep one route.** Before removing a task, check the workspace for
commits or changes that were never pushed. Either refuse unless the operator says so
explicitly, or push them to the gate under their own ref - `refs/sokar/incoming/<task>-wip`
or similar - so nothing is lost and everything still arrives through review. Inspecting
rescued work then means `sokar gate review`, not opening files in an editor.

**Mount a host directory at `/workspace`.** Work survives the container, the operator can
open it with ordinary tools, and a task can be rebuilt around what it had. This is what
Terok does - and it names the directory `workspace-dangerous` and documents:

> The container has full write access to this directory and could have rewritten git
> hooks, checked in malicious scripts, or otherwise poisoned the repository. Do not
> execute code or run `git` commands in this directory from the host. The safer way to
> interact with agent work is through the git gate.

That warning is the argument against it, made by the project that took the option. A
mounted workspace is agent-controlled content on the host: `.git/hooks` runs on the next
host-side git command, and `.git/config` can point `core.pager`, `core.fsmonitor` or an
alias at anything, so `git status` in that directory is enough to run the agent's code.
It also puts output on the host with no review, which is the property the gate exists to
provide: today the only crossing is a push landing under `refs/sokar/incoming/`, and
`approve` is the single command that sends anything anywhere.

The first option is preferred. It removes the accidental-destruction harm without adding
a second route out, and it costs an editor-shaped convenience rather than a containment
property.

## To be checked

- Whether the check can be made cheaply and reliably: it means running git inside the
  container while it is stopped, or before stopping it, and a task with no gate has
  nowhere to push at all.
- What "never pushed" means for a workspace the agent left dirty rather than committed.
  Committing on the agent's behalf puts words in its mouth; pushing nothing loses the
  work.
- Whether the same argument covers what the agent installed *in* the container -
  packages, caches, a built toolchain - which is lost with it today, and is why
  [0009](0009-Task-Lifecycle-Control.md) keeps the old image on resume rather than
  upgrading it.
