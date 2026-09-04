# 0009 — Task Lifecycle Control

**Status:** the CLI half is done — `task list`, `task stop` and `task resume`, verified
end to end on a real container. The API half waits on [0001](0001-Local-Daemon-API.md),
which does not exist yet.

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

Resuming keeps the image the task has, and says when the project's image has been
rebuilt since. Upgrading silently would discard whatever the agent installed in the
container, which is what resuming exists to preserve.

A task whose container is gone cannot be rebuilt around what it had, because the
workspace lives inside the container. That is [0043](0043-Workspace-Outlives-Its-Container.md)
rather than a gap here.
