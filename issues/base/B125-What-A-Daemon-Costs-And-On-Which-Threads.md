# B125 — What A Daemon Costs, And On Which Threads

**Status:** now; first measurement done 2026-10-07, platform threads stay.

**What must be true.** What one `sokard` costs is measured, idle and at work, and its loops run on
the kind of thread that the measurement and B79's fault support. The decision is written down with the
numbers that made it, not inferred.

## Why

In Walk 11 (2026-10-07) two daemons on one VM used 232 % and 256 % CPU, and a start in the
interface lost its `Credentials` call after 30 seconds. The cause was loops that never waited, not
the kind of thread. Each task's direct chat, and a project's conversation, had a loop that waits on
its transport. When a task had no account in the vault, or the vault was shut, that wait came back
at once with nothing and no error, and the loop asked again with no pause. Two or three such loops
turned a core each, for tasks whose containers had exited two days earlier. The pause, the fix for
that load, is in `caf9585d`. A loop that never waits burns its core on a virtual thread just as on
a platform thread.

The kind of thread is a separate question: how many threads a machine carries, and whether a call on
the socket always finds a thread to run on. The daemon's long-lived passes (`BackgroundPass`) and
every varlink connection run on platform threads since B79. A daemon once answered no call for four
minutes. The likeliest cause was background passes and connection reads on virtual threads, pinned in
native calls, holding both carriers of a two-CPU VM. That cause was inferred, not measured, and the
hang has not been seen since. Whether virtual threads would serve better was the open question.

## Acceptance

- **Measured, numbers in this issue:** CPU over a minute, and the number of threads, of one `sokard`
  that is idle with no project, with exited tasks, with one running task, and with the vault shut and
  open. Each is measured before and after `caf9585d`, on the two-CPU VM. Seen to fail: a daemon with
  exited tasks and no account for them above 5 % of one core.
- **Every daemon loop that waits on something outside it rests when that thing did not wait**, each
  with a test watched to fail. Today these are the two transport loops (`MessageWaitPaceTest`). The
  others sleep or block (`sokar-messages`, `sokar-message-notices`, `sokar-follow`, `sokar-upstream`).
- **Virtual or platform, decided with a measurement:** the B79 scenario (two-CPU VM, a daemon started
  right after a SIGKILL, fifteen or more projects followed, a full acceptance run) run with the
  passes and connections on virtual threads. If calls hang, a thread dump (`SIGQUIT`) names where they
  wait, and the platform threads stay, with the reason recorded here. If none hangs in enough runs to
  say so, the threads that only wait move to virtual threads, and those that block in native calls stay
  on platform threads of their own.
- **The thread count at rest is bounded,** whichever kind: a loop per exited task is not one per
  task ever started. A task with no account in the vault is not waited on at all.

## To be checked

- How many platform threads a daemon holds with N tasks: a direct-chat loop per task, a conversation
  loop per project, a thread per open varlink connection, and whether that matters below a few
  hundred.
- Whether a native call (`podman`, `git`, `ssh`, a transport adapter) pins a carrier in today's
  GraalVM native image, measured rather than taken from the JDK's documentation.
- Whether `matrix`'s `0.4.0~snapshot.309` daemon, with its vault shut, shows the same turning threads
  after the fix.

## Measured, 2026-10-07

The native build `5956eb3f` in the account `core` on the Ubuntu VM (6 vCPUs, 11 GB), every other account's
`sokard` stopped, no heavy run on the host (its load1: median 2.1, max 4.8). Each step started N stub tasks at once,
each working (a line on the screen, a commit every 3 s, a push every tenth step), and sampled `sokard` every 5 s for
10 minutes, with one `List`, `Credentials` and `Following` call over `varlinkctl` (about 100 ms of which is
`varlinkctl`'s own). Platform threads first, then `sokard` restarted with `SOKAR_THREADS=virtual`.

| Threads | Tasks | CPU median / max | Threads | RSS | `List` median / max | `Credentials` p95 / max | Start median / max | Failed calls |
|---|---|---|---|---|---|---|---|---|
| platform | 10 | 0 % / 29 % | 20-21 | 52 MB | 1.2 s / 2.6 s | 108 / 124 ms | 20 s / 23 s | 0 |
| platform | 20 | 27 % / 29 % | 22-24 | 52 MB | 2.5 s / 5.0 s | 133 / 433 ms | 40 s / 47 s | 0 |
| platform | 30 | 27 % / 30 % | 23 | 45 MB | 3.8 s / 6.2 s | 157 / 175 ms | 62 s / 76 s | 0 |
| virtual | 10 | 0 % / 30 % | 18-19 | 48 MB | 1.2 s / 2.6 s | 108 / 128 ms | 21 s / 24 s | 0 |
| virtual | 20 | 27 % / 30 % | 20-21 | 48 MB | 2.5 s / 5.1 s | 111 / 129 ms | 36 s / 45 s | 0 |
| virtual | 30 | 29 % / 31 % | 21 | 46 MB | 3.8 s / 6.7 s | 139 / 524 ms | 60 s / 73 s | 0 |

Every agent worked in every step (1950, 3800 and 5500 commits in all). RSS did not grow while the tasks worked (at
most +0.18 MB/min).

**What follows:**

- **Platform or virtual makes no difference** that this measurement can see, so platform threads stay, with
  B79's reason. `SOKAR_THREADS=virtual` stays, so it can be measured again. `sokard` holds a few dozen threads at
  30 tasks; the work is processes (`podman`, `git`), which a virtual thread does not make faster. Where virtual
  threads fit - many long waits on the network in plain Java - Sokar already uses them: the credential proxy serves
  each connection on one.
- **`List` grows with the tasks**: it reads each running task's screen one after the other (`podman exec tmux
  capture-pane`, about 100 ms each), so 30 tasks take about 4 s. To fix: the screens read in the background, `List`
  answered from what was read.
- **Memory is the limit, and it is the helpers', not `sokard`'s**: per task the host runs `sokar vault serve`
  (179 MB), `shield watch` (54 MB), `gate serve` (47 MB), `shield read` (41 MB) and `dnsmasq` (6 MB), about 330 MB,
  against the stub container's 5 MB and `sokard`'s 50 MB. 30 tasks need about 10 GB. To fix: a heap bound for each
  helper, measured before and after.
- **Small leaks after each removal**: with platform threads `sokard` went from 16 threads and 10 descriptors to 20
  threads and 20 descriptors over three steps; build readers outlived their tasks (two after the first step). To fix,
  each with a test.
- A container that was created but never started is not listed as a task, so `sokar task remove` cannot remove it.

## Second round, measured 2026-10-07

The native build `7c078c0c` in `core`, platform threads, the host's load1 median 2.2 (max 3.9). Each stub task
worked as in the first round, and at every step also sent a streamed request through its credential proxy to a fake
provider on the VM (TLS with a certificate trusted through `~/.config/sokar/ca-certificates.pem`, about 4 s per
answer, one in twenty refused with 429) and wrote 8 MiB to its disk.

| Tasks | CPU median / max | RSS | `List` median / max | `Credentials` p95 | Start median | Proxy answers / 429 | Failed calls |
|---|---|---|---|---|---|---|---|
| 5 | 3 % / 29 % | 68 MB | 0.5 s / 0.7 s | 108 ms | 13 s | 208 / 13 | 0 |
| 15 | 24 % / 27 % | 54 MB | 1.2 s / 1.9 s | 117 ms | 32 s | 774 / 47 | 0 |

- **The helpers per task**, at 15 tasks: `vault serve` 45 MB (179 before), `shield watch` 39 (54), `gate serve` 42
  (47), `shield read` 38 (41) - about 165 MB instead of 330. The VM held 3.6 GB at 15 tasks, 6.3 GB at 20 before.
- **No leak**: three cycles of 5 tasks started, working for a minute and removed left `sokard` at 54 MB, 12-20
  descriptors and 31-32 threads each time. The threads above the 10 of a fresh daemon are idle pool workers, which
  end on their own; build readers no longer outlive their tasks.
- **A start loads every installed agent's adapter** (four here) while it runs, about 15 MB each; they end with the
  start.
- **`List` is faster, not fast**: the screens are now read eight at once, but each read is a `podman exec`, 400-600
  ms under this load. The interface's `Watch` recomputes the listing every 500 ms, so an open interface costs one
  `exec` per running task every 3 s. To fix: the screen is written to a file inside the task when it changes, and read
  from the host without `exec`.
