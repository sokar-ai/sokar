# B44 — One Way To Start Work

**Status:** decided and mostly built on 2026-09-11. It removes commands rather than adding them.
Written after an operator used the obvious command on an existing task and Sokar silently did
something else.

**Where the other half lives.** Points 5 and 6 of *What must be true* are the interface's to render - the action named by its effect, a refusal shown as unavailable with its reason, the reply by its named outcome. **Built on both sides**: Sokar's half is on the wire (`startAction` on the listing, `action` on the final reply), and sokar-frontend confirmed on 2026-09-13 that its half has been built since the lifecycle cut, so no issue exists for it.

Built and landed: points 1, 2 (2026-09-29: the gate token and the provider's stand-in token in the vault,
the task's knowledge saved under the state directory, so `start` after a reboot brings the task back
whole), 3, 4 (the rule, applied by saving the durable files beside the runtime directory rather than
moving them - the container's annotation and mounts name runtime paths), 5, 6's refusal half, and 8.
Outstanding: point 6's `sokar cleanup`, and point 7 (streaming the build).

## What happened

The operator had a stopped task and typed what anyone would type:

    sokar task run sokar-utils4j-shell-5684

They got a shell, worked, typed `exit`, and looked at the list. Their task was still down. Three
things happened, none of them reported:

1. `run` takes a **task name**, not a container name. The container name was accepted as one, with
   no complaint that a container by exactly that name already existed.
2. The container it created was therefore `sokar-utils4j-sokar-utils4j-shell-5684-<runId>`, from
   `sokar-<project>-<task>-<runId>`. The old one was never a candidate to be reused.
3. `run` is ephemeral. On a clean exit its armed teardown **removes** what it made. The entry still
   in the list afterwards was the original, untouched, from two days earlier.

Both attempts are still visible in the runtime directory on the test VM:

    sokar-utils4j-sokar-utils4j-shell-5684-2065495   10:29
    sokar-utils4j-sokar-utils4j-shell-5684-2338937   10:42

Three commands would have been right, depending on a state the operator cannot see from the
command they typed: `resume` for a stopped task, `attach` for a running one, `run` for a new one.

## Why it cannot be fixed where it shows

`TaskLaunch` names a container with **the PID of the process that launched it**:

```java
runner.containerName(project, request.task(), String.valueOf(ProcessHandle.current().pid()));
```

Two invocations never share a PID, so two invocations never share a container. There is no mapping
from "a task" to "its container" - which is why `run` always creates: the question *which one did
you mean* is not answerable from what it holds. Every other complaint here follows from that.

## The shape decided

`docker compose`, not `docker`. A `project.yml` declaring tasks is a compose file, and `compose up`
already means create-or-start; taking `docker start`'s narrower meaning would be the surprising
choice, not the safe one.

| Intent | Today | Decided | compose |
|---|---|---|---|
| create, do not start | `task prepare` | `task create` | `create` |
| create **or** start, at a terminal | `task run` | `task start` | `up` |
| the same, in the background | varlink `Start` only | `task start --detach` | `up -d` |
| throw it away on exit | **the default** | `task start --rm` | `run --rm` |
| go inside | `task attach` | `task attach` | `exec` |
| halt, keep | `task stop` | `task stop` | `stop` |
| delete | `task stop --purge` | `task remove` | `rm` |
| list | `task list` | `task list` | `ps` |
| resume | `task resume` | **gone** | — |

`--keep` disappears because it becomes the default. The dangerous half of this requirement is that
inversion: today the command a person reaches for destroys the container it made.

### 1. One container per task

`sokar-<project>-<task>`, no run id. `start` then has an answerable question: absent, create;
stopped, start; running, say so and name `attach`. A task cannot run twice at once, which nothing
wants - two runs of one task share the project mirror and the gate.

The project name may be taken from the directory the command runs in, which is normally the git
repository's name and normally right. Today `Project.name` is a required field of `project.yml`
and nothing is derived; that `utils4j` matches its directory is diligence, not mechanism. Belongs
with [B25](B25-Names-The-Operator-Should-Not-Have-To-Find.md).

### 2. What survives a restart

Resuming needs the helpers' command lines **and their environment**, because a container's
environment is fixed when it is created: the agent already holds the gate token, and a freshly
minted one would be rejected as a bad credential. `resume.json` therefore carries
`SOKAR_GATE_TOKEN` in clear.

It lives in the runtime directory, which is tmpfs, and the comment says why:

> Under the runtime directory, so the kernel removes it when the session ends and no stale sidecar
> can be picked up by a later run.

Good reason, unwritten consequence: **a reboot makes a task unresumable**. The work in the
container survives; the means of bringing it back does not. Measured - the operator's
`sokar-utils4j-shell-5684` has no runtime directory left, and the frontend reported `Resume` on it
doing nothing.

Decided: `resume.json` becomes persistent, **without the secret**; the gate token goes into the
vault Sokar already has, referenced by name. The cost is accepted deliberately: the first `start`
after a reboot needs the vault unlocked, even for a task that has nothing to do with credentials.

### 3. `remove` refuses a running task

`remove` inherits everything `--purge` protects: `HOLDS_WORK` refuses rather than destroys,
`--rescue` pushes into the mirror first, `NOTHING_KNOWS` refuses when nothing could tell. What is
new is that removal can now meet a *running* task, which it never could while it hung off `stop`.
It refuses and names `stop`; `--force` does both in one step.

### 4. One rule for where a file lives

The per-task directory holds fourteen files with no rule saying which are meant to be volatile.
Two protections were quietly resting on the wrong side of that line - `resume.json` (above) and
the unhanded-work note, which `TaskControl` writes on the way down precisely because "whoever
removes this task later cannot ask the container itself", and which `remove` reads to decide
whether to refuse.

After a reboot that note is gone, `remove` answers `NOTHING_KNOWS`, and the operator has to pass
`--force` - the flag that means *destroy work*. A guard that must be routinely overridden is not a
guard. Anyone who reboots regularly learns to type `--force` always.

The rule: **describes something live, it is volatile; is it knowledge about the task, it is
durable.** Sockets, pid files, the nftables ruleset and the resolver's files stay in the runtime
directory. `resume.json`, the work note and the task's own description move to state.

### 5. A second daemon may not take the socket

Not a verb, but the same failure in the layer below them: **a second `sokard` binds a socket that
is already served, and neither side says anything.** Measured on the Ubuntu test VM on
2026-09-11, after a daemon was started by accident while another was running:

```
u_str LISTEN 0 50 /run/user/1000/sokar/sokard.sock 71525      users:(("sokard",pid=12876))
u_str LISTEN 0 50 /run/user/1000/sokar/sokard.sock 27791903   users:(("sokard",pid=1494773))
```

Two processes, one path, two inodes. The path resolved to the newer one, so every connection
reached a daemon with nothing in it while the first went on holding two running tasks - reachable
by nothing. The interface showed an empty machine; the tasks were fine. Nothing was logged by
either daemon, because from each one's point of view nothing went wrong.

It was found by accident, and it is the kind of thing that is only ever found by accident: the
symptom is *absence*. A task list that is empty looks like a machine with no work on it.

**What must be true:** a daemon that finds a live socket at its path refuses to start and says what
holds it. A stale socket - the path exists, nothing listens - is cleaned up and taken, because that
is what a machine that crashed leaves behind and refusing there would need a person for something
Sokar can decide.

This also makes `sokar doctor` able to answer "am I talking to the daemon I think I am", which it
cannot today.

**The second half is `--version` starting a daemon.** `sokard --version` ignores the flag and runs;
that is how the accidental second daemon came to exist. A flag that prints something must not
start a service.

### 6. Something has to collect what is left over

Three leftovers, all found within an hour of looking on one test machine, none of them reported by
anything:

**A removed container's runtime directory stays.** An operator ran `task run` three times on
2026-09-11; each removed its container on exit, and each left
`$XDG_RUNTIME_DIR/sokar/<name>/` behind - sockets, logs, the nftables ruleset, and `resume.json`
with `SOKAR_GATE_TOKEN` in clear. Four hours later, three gate tokens for three containers that no
longer existed. `task stop --purge` does remove the directory and says so; the ephemeral path of
`run` does not.

**The same is true of `podman rm`.** A person removing a container by hand is not doing anything
wrong, and it mostly works: the poststop hook reads the `*.pid` files and reaps the gate, the
watcher and the resolver, and the task leaves `task list` because that reads the runtime. What
stays is the same directory with the same token. Sokar cannot prevent this and should not try -
but it must be able to notice it afterwards.

**There is no way to clear more than one task.** Every cleanup after a test session is the same
hand-written loop over `task list`.

**Decided: `sokar cleanup`.** It lists by default and removes with `--now`, as `sweep` does, because
the thing it deletes cannot be got back.

The rule it uses has to be narrow, or it becomes a command nobody dares run: **something that
belongs to a named task, where no container of that name exists.** That covers the runtime
directory, the durable state directory from point 2, and any helper process still holding a pid
file in one - a gate serving a token for a container that is gone is exactly what should not
outlive it.

What it must **not** touch, because "unused" is not the same as "orphaned": the task images
(`sokar/<project>`), the build directories under `~/.local/share/sokar/build/`, and the project
mirrors. Those belong to a project rather than to a task, they are what makes the next start fast,
and a machine with no task running has all of them idle by definition. The clearance journals stay
too - they are the record of what happened, and outliving the task is their purpose
([B26](B26-What-This-Machine-Has-Been-Doing.md)).

**And a refusal must not advise the impossible.** Removing the operator's stale task answered:

```
refusing to remove sokar-utils4j-shell-5684: it is stopped and nothing recorded what it holds
    resume it with 'sokar task resume sokar-utils4j-shell-5684' to see, or --force to discard it unseen
```

The refusal is right. The way out it names cannot work: `resume` needs the record the reboot
erased, which is why it is refusing. What was left was `--force` - discard unseen - for a task that
in fact held nothing. Point 2 removes the cause; until then, a refusal that cannot be answered
except by overriding it teaches people to override it.

### 7. A build that takes minutes has to say so

Reported from the machine on 2026-09-11: *"Es dauert recht lange, bis der Container läuft und man
denkt er ist abgestürzt."* A first `start` builds an image, which takes minutes, and prints nothing
at all while it does.

**The cause is not a missing message, it is the shape of the call.** `CommandRunner.run(Command)`
returns a `CommandResult` when the process has ended; there is no streaming variant. While podman
builds, its output exists nowhere - not on the terminal, not in a file.

**The same shape cost a day of diagnosis.** When the build ran into Sokar's ten-minute cap in CI,
the log held this and nothing else:

    sokar: Cannot run 'podman build --tag sokar/e2e-tier1 ...': Timed out after 600 s

No `STEP`, no `Get:`, no error - the wrapper killed the process and reported its own sentence, and
everything podman would have said went with it. Two runs were spent finding out that an Ubuntu
mirror was slow, on two rented machines each, because the one place that knew was discarded.

**Decided on 2026-09-11: stream it.** `CommandRunner` gains a variant that hands each line to a
consumer as it arrives; `Podman.buildImage` takes one; `Start` prints them. Additive - a default
method leaves every existing caller alone - but it touches the process layer everything uses, and
the fake runner in the tests has to feed the consumer too.

The operator chose this over a heartbeat that only prints elapsed time, for the second reason
rather than the first: a spinner answers "is it alive", and the thing that was actually missing was
"what is it doing, and what did it say when it stopped".

**What must be true:**

1. A build that takes minutes shows what it is doing while it does it.
2. A build that fails or is killed leaves what the runtime said, where whoever reads the log next
   will find it.
3. The interface gets the same thing over `Tail`, so a detached start is not a second code path.

**Acceptance criteria:**

- A `start` that builds an image prints the runtime's step lines as they happen.
- A build that hits the cap leaves those lines in the task's log, and a test asserts the log is not
  empty after a killed build.
- Nothing that does not build - a start that resumes, a dry run - prints build output.

### 8. One coordinated cut

`Start`, `Resume` and `Stop` are in `org.fuin.sokar.Tasks1.varlink`, whose compatibility rules
would forbid removing `Resume` or changing what `Start` means. Those rules are marked *not in force
yet* in the file: there is no release, no tag, and the only clients are this daemon and the
frontend beside it.

Decided: **one agreed cut**, contract and daemon here, tiles there, rather than a compatibility
shim neither side will ever need again. Between the two pushes an old frontend gets
`MethodNotFound` and a tile action does nothing. Nobody outside notices; the operator will, and
accepted that.

**And no shim at the command line either, decided 2026-09-11.** `task run`, `--keep` and
`--no-attach` are removed rather than kept as accepted spellings. Keeping them would have been
safe - `run --keep --no-attach` and `start --detach` do the same thing for a task that does not
exist yet, which is every call that uses them - so this is a choice to take the churn now rather
than leave a compatibility layer that somebody has to remember to delete before the release.

Three consequences follow, and the third is a scheduling constraint rather than a technical one:

1. **This repository changes in the same commit**: `buildtools/e2e-tier1.sh` uses `--no-attach`
   twice, and the acceptance kit's `TaskSteps` builds `task run`. The kit is published by the same
   `deploy` job as the CLI, so it cannot lag behind it.
2. **The agent repositories break the moment the package is published**, and stay broken until
   their own `acceptance.sh` is pushed - they install the published CLI and call `task run`.
   Their branches are prepared and unpushed for exactly this.
3. **The window must not straddle Monday 05:17 UTC**, when three update jobs run `acceptance.sh`
   unattended. A cut on a Friday afternoon with nobody pushing until Monday is the one way this
   decision turns expensive.

## What must be true

1. A name that already identifies a task is never quietly reinterpreted. Asking to start a thing
   that exists either does it or fails saying it exists - it never creates a second thing beside it.
2. Which of the two paths `start` took is said plainly. Restarting a workspace and creating an empty
   one are not interchangeable outcomes.
3. Starting work never destroys work.
4. A task that is already running is not started twice; the answer names `attach`.
5. The list says **beforehand** what the verb would do to each task - start it fresh, bring it back,
   or refuse and why. The interface names the action by its effect and shows a refusal as
   unavailable with its reason, rather than finding out by pressing.
6. The reply says what it did, as a named outcome.
7. The CLI and the interface agree on the model.

Points 5 and 6 are the frontend's, asked for on the channel on 2026-09-11.

## Acceptance criteria

- `start` against a stopped task starts *that* task: same container, same workspace, same branch,
  asserted by container id.
- `start` against a running task fails, names it, names `attach`, and its exit code distinguishes
  that from a real error.
- `start` against a name that is a container but not a task fails with what exists, creating
  nothing.
- `start --detach` leaves a task that `attach` reaches, and returns promptly. It says something
  useful about a build it is not waiting for.
- A second `sokard` started against a live socket refuses and names what holds it; one started
  against a stale socket takes it. `sokard --version` prints a version and starts nothing.
- `sokar cleanup` lists what belongs to a task with no container, and removes it with `--now`. A
  test starts a task, removes its container with `podman rm`, and asserts that cleanup names the
  runtime directory, the state directory and any helper still holding a pid file in them - and
  that it names no image, no build directory and no journal.
- No refusal names a way out that is unavailable in the state it is refusing in.
- `start` after a reboot brings the task back whole - container **and** helpers - with the gate
  token it already had, or reports that it did not.
- `remove` on a running task refuses; `remove --force` stops and removes; `HOLDS_WORK` and
  `NOTHING_KNOWS` still refuse, **after a reboot as well**, which is the case that fails today.
- Every one of these is exercised from a terminal by the acceptance suite. The whole defect was invisible to anything
  that did not type what a person types.

## To be checked

1. **Existing containers become unreachable.** Thirteen on the test VM carry a run id and will not
   match the new scheme, so `start` will not find them. `list` shows them and `remove` deletes
   them, but they cannot be resumed. Acceptable for throwaway shells; whether anything else needs
   a migration is not checked.
2. **What `--detach` reports about a build it is not watching.** `Start` streams because a build
   takes minutes and silence is indistinguishable from a hang.
3. **Whether `create` earns its place** or is just `start --detach` stopping one step earlier.
4. Whether the vault prompt after a reboot can be avoided for a task that holds no credential of
   its own - it still needs its gate token, so probably not, but it has not been looked at.
