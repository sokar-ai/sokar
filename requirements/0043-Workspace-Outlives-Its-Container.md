# 0043 — Workspace Outlives Its Container

**Status:** the refusal and the rescue are built and now cover the two cases that got past
them - `sokar task stop --purge` refuses when the workspace holds work that never reached the
gate, whether the task is running or was stopped earlier, `--rescue` pushes it to a ref of its
own first and fails loudly if nothing arrives, and `--force` discards it deliberately. Whether
the workspace should live on the host at all is still open, and the answer below is still no.

Two silent losses were measured on Fedora 44 and closed, both of the kind this requirement
exists for - a cleanup command destroying work while reporting success:

- **A task stopped first was purged without a word.** The refusal asked the container what it
  held, and nothing can ask a stopped container. The census is now written into the task's
  state directory on the way down, while it is still knowable, and read back by whoever
  removes the task later. A stopped task whose note is missing entirely - stopped by
  something other than Sokar, or its runtime directory gone with a logout - is refused rather
  than guessed at, and says to resume it or pass `--force`.
- **`--rescue` reported a rescue that never happened.** In a repository with no initial
  commit the push command asked for `HEAD` before it committed, printed `nothing to push`,
  exited zero, and the container was removed as rescued. Measured: the mirror held no ref and
  the file went with the container. It now commits first, which creates that initial commit,
  and an empty workspace exits non-zero. Verified by removing a task whose workspace held one
  uncommitted file and nothing else: `refs/sokar/incoming/shell-rescued` now carries it.

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

Two questions this used to leave open are answered by what was built:

**How the check is made without host-side git.** A running task is asked inside its
container. A stopped one cannot be asked at all, so the answer is written down while it is
still knowable and read back later; a stopped container's filesystem does not change, so the
note cannot go stale. Reading the workspace from the host instead was rejected for the reason
this requirement rejects the mount: `.git` is agent-controlled content, and hooks, `core.pager`,
`core.fsmonitor` and aliases all run host-side code, so `git status` in it is enough to run
whatever the agent wrote.

**What "never pushed" means for a dirty workspace.** Both are counted - commits that are not
on the gate, and changed files - and a rescue commits what is uncommitted as
`agent: uncommitted work`, on the `-rescued` ref rather than the reviewed one. That does put
words in the agent's mouth, and the commit message says as much; losing the work was the worse
of the two.

## To be checked

- Whether the same guard should cover `podman rm` directly. Nothing Sokar writes can stop
  the runtime's own command, so the note is a defence for `sokar task stop --purge` only.
- Whether the same argument covers what the agent installed *in* the container -
  packages, caches, a built toolchain - which is lost with it today, and is why
  [0009](0009-Task-Lifecycle-Control.md) keeps the old image on resume rather than
  upgrading it.
