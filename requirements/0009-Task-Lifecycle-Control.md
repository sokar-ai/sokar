# 0009 — Task Lifecycle Control

**Status:** done. All four acceptance criteria are met and measured - `task list`, `task stop`
and `task resume` in the CLI, and `List`, `Stop` and `Resume` over the daemon's socket, both
reaching the same code.

Measured on Fedora 44 against a real container, over the socket rather than in a test double:
`Stop` with `purge` on a task holding an unpushed commit answered `HOLDS_WORK` and the container
survived; a plain `Stop` answered `STOPPED` with `helpers 4`; `Resume` answered `RESUMED` with
`started 2 of 2` and the container came back up. The daemon was then killed with `SIGKILL` and the
task stayed up with all four helpers, still listed by the CLI - so a client dying leaves running
tasks untouched, which is now demonstrated rather than argued.

The CLI and the daemon cannot drift, because neither decides anything: `TaskInventory` answers
what tasks exist and `TaskControl` decides what stopping and resuming do. Both callers render what
those return. The refusals are why that matters - one that existed in the CLI and not over the
socket would be a task removed, remotely, with work that existed nowhere else.

Today a task can be started and nothing else. Stopping one means finding its
container by name and using the runtime directly, which is both undiscoverable and
easy to get wrong.

## Acceptance

- List, stop and resume exist in the CLI and over the API.
- Stopping reaps every sidecar the task started, verified, not assumed.
- Resuming a task reuses its workspace rather than starting over.
- Stopping a task that has already gone is not an error.

## Notes

A CLI gap first, an interface feature second. The interface cannot offer what the
domain does not have.

Measured on 2026-09-04, driving a real container rather than a stand-in, which found
three things unit tests could not:

- `stop` removed the container, which is the task's workspace, so a stopped task could
  never be resumed - while reporting that it had been stopped. It now stops; removal is
  `--purge`, asked for by name.
- The count of stopped helpers was taken after the container went away, by which time
  the poststop hook had reaped them and deleted their pid files. It reported none while
  stopping five. The census is taken first, and every helper is waited for and checked
  afterwards.
- A resumed container mounted the *previous* run's socket: a bind mount is bound to the
  file present when the container starts, and the proxy replaced it a moment later. The
  container held a deleted inode, and every request through it went nowhere while the
  same request from the host was answered. Helpers now record whether they must be up
  before the container or need the running container, and are started in that order.

Measured again on 2026-09-07, driving a real container, which found two more:

- **Resuming a task that was already running started its helpers a second time.** Every
  helper of a given name writes the same pid file, so the second one left the first named by
  nothing: two resumes left two `shield watch` processes re-parented to init, and the later
  `task stop` reported `helpers 4 of 4 stopped` while they went on running. A running task now
  answers `already up; nothing to resume` and starts nothing, which is the mirror of stopping
  a task that has already gone. A recorded helper that is still alive is not started again
  either, which covers the half-running case a crash leaves.
- **A stopped task did not fit its own table.** The runtime's longest phrase,
  `Exited (143) Less than a second ago`, is twice the width of the `STATE` column and ran into
  the helper count - exactly when an operator is reading `task list` to find the name to
  resume. The exit code is kept and the age dropped.

The edges hold: stopping a task that never existed says so and exits zero, a name Sokar did
not create is refused, resuming something absent fails with a reason, and a stopped task is
listed rather than hidden.

Resuming keeps the image the task has, and says when the project's image has been
rebuilt since. Upgrading silently would discard whatever the agent installed in the
container, which is what resuming exists to preserve.

A task whose container is gone cannot be rebuilt around what it had, because the
workspace lives inside the container. That is [0043](0043-Workspace-Outlives-Its-Container.md)
rather than a gap here.
