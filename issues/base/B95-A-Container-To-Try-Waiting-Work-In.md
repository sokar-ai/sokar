# B95 — A Container To Try Waiting Work In

**Status:** soon

**What must be true.** A reviewer can run a build or tests against waiting work, and later attach an
IDE to it, in a fresh container with the project's egress and no way back - never on the host and
never in the agent's own container - and see the result beside the review.

## Why

Reviewing work often means running it: its build, its tests. Run on the reviewer's computer, or on the machine as
the account that holds the vault and the daemon, that executes the agent's unreviewed code with rights the box
exists to keep from it. Run in the agent's own container, it runs in an environment the reviewed work could have
prepared - a cached plugin, a wrapper, `PATH`.

The interface's half is sokar-frontend's: "Run its tests" and "Open in an IDE" beside *Approve*.

## The shape

1. **`sokar gate try <task> -- <command>`** runs a command against the waiting work in a fresh container from the
   project's image, with the waiting ref as its workspace, the project's egress and limits, and no way back to the
   gate.
2. **Its output and exit are kept and shown beside the review**, in the terminal and over the daemon.
3. **The container is removed when the command ends**, and nothing it wrote reaches the mirror.
4. **Later, the same container for an IDE to attach to**: the IDE's backend inside it (installed through the
   project's image, not downloaded at run time), reached through podman, never on the host.

### Not offered

**An IDE's remote backend on the host.** It runs as the account that holds the vault, the daemon's socket and the
deploy keys; building there runs the agent's code with all of it.

## Acceptance

- `sokar gate try <task> -- <command>` runs the command in a fresh container from the project's image, with the
  waiting ref as its workspace and the project's egress and limits.
- The container cannot push back to the gate.
- Its output and exit are shown beside the review, in the terminal and over the daemon.
- The container is gone when the command ends, and the mirror is unchanged.
- An IDE's backend is reached only inside such a container, through podman; nothing of it runs on the host.
- **Seen to fail:** a test whose command pushes to the gate goes red when the push arrives; one whose command
  reaches a host outside the project's egress goes red when it succeeds; one that lists containers after the
  command ends goes red when it is still there; one that compares the mirror before and after goes red when it
  changed; one that reads the review goes red when the output and exit are missing.

## To be checked

1. **Its limits**: the task's, or a review's own - a build or an IDE backend wants more memory than many tasks.
2. **Whether the result is recorded with the review**, so an approval can say which run it followed.

**Guideline points, 2026-10-09:** the operator's review of security guidelines counts this as its item F7,
answering the source document's "same SAST/DAST gates for AI code" (§3), Checkmarx, LinkedIn 11.

