# Sokar Commands

Every command, in the shape the CLI actually has. `sokar <command> --help` is always the
authority — this page exists so you can find the name without guessing it.

The [cheat sheet](cheat-sheet.md) is the other way round: it starts from what you are trying to Every key a project file can carry is in
[the project file](project-file.md).
do rather than from the command tree.

## The ten groups

| | |
|---|---|
| [`task`](#task) | Runs and inspects agent tasks. |
| [`shield`](#shield) | Inspects and changes what a task may reach. |
| [`vault`](#vault) | Manages stored credentials. |
| [`gate`](#gate) | Reviews and forwards what an agent pushed. |
| [`agents`](#agents) | Lists the agents installed on this machine. |
| [`projects`](#projects) | Lists the projects this machine has run tasks for. |
| [`setup`](#setup) | Installs the OCI hooks into this user's podman configuration. |
| [`doctor`](#doctor) | Reports paths and process hardening state. |
| [`panic`](#panic) | Stops every running task and every helper, without removing anything. |
| [`daemon`](#daemon) | Reaches the daemon on this machine. |

---

## task

Everything that starts, joins, or ends a piece of agent work.

| Command | What it does |
|---|---|
| `sokar task start [TASK]` | Starts a task: creates it, or brings back the one that is stopped. |
| `sokar task list` | Lists the tasks on this machine, with how long each has been in its state. |
| `sokar task status TASK` | Says what a task is doing and what its workspace holds. |
| `sokar task logs TASK [LOG]` | Shows what a task's helpers on this machine wrote. |
| `sokar task attach TASK` | Opens a shell in a running task. Leaving it does not end it. |
| `sokar task stop TASK` | Stops a task and its helpers. The container and its workspace stay. |
| `sokar task remove TASK` | Removes a stopped task: its container, its state and its workspace. |
| `sokar task label TASK` | Gives a task a caption to read it by. Not a rename. |
| `sokar task prepare` | Builds this project's task image without starting a task. |
| `sokar task clearance TASK` | Changes what a running task does about a blocked connection. |

**`logs` is the node's side, not the container's.** The gate, the broker, the relay, the resolver
and the clearance watcher all run on this machine, and each writes its own file; what the agent
printed went to whoever was attached. `events.jsonl` is what the firewall blocked, which is where
to look when a task starts and then does nothing. Which files exist depends on what the task started, so it
lists rather than assumes. They live in the task's runtime directory and **do not survive a
restart**.

**`status` is the one to reach for when something looks wrong.** `list` is the five columns that
fit a table; `status` is everything this machine knows about one task, including whether its
workspace holds changes nobody has pushed. That last answer is only available while the task runs:
the workspace is inside the container, and once it is stopped the only source is the note
`task stop` wrote on the way out - which is also what `task remove` reads before it refuses.

**One verb starts, and it decides from the task's state.** `start` creates a task that is not
there and brings back one that is stopped, with the workspace, the branch and the uncommitted
changes it already has. A task that is already running is refused rather than started twice, and
the refusal names `attach`. `--detach` starts it without handing over a shell; `--now` returns
before the image is built, for a caller that follows the task's phase instead of waiting.

**Stopping and removing are two verbs, because one destroys and the other does not.** `stop` ends
the session and the helpers and keeps everything; `remove` is the one that throws the workspace
away. `remove` refuses three times over: the task is running (`stop` it first, or `--force`), it
holds commits that never reached the gate (`--rescue` pushes them to the gate first, `--force`
discards them), or nothing could say what it holds. They were one command with a flag between
them, which is a verb whose meaning depends on a word people do not read.

**The container is kept when you leave an attached shell.** `--rm` on `start` is how you say you
want it thrown away — it used to be the other way round, and the command a person reaches for
first removed what it had just made.

## shield

The egress policy: what a task may resolve and reach, and what happened when it tried.

| Command | What it does |
|---|---|
| `sokar shield egress` | Shows what a project may reach, and adds or removes sets and hosts. |
| `sokar shield sets` | Lists the curated egress sets a project can name in `project.yml`. |
| `sokar shield read` | Reads blocked connections from the container's firewall log. |
| `sokar shield watch` | Prompts for each blocked connection and allows the ones you approve. |
| `sokar shield subscribe` | Follows the blocked connections a running watcher reports. |
| `sokar shield dns` | Runs the container's DNS resolver, answering only for allowed domains. |

`dns`, `read` and `watch` are started by Sokar for each task rather than typed by hand; they are
listed because an operator reading `ps` will see them.

## vault

Credentials, and the broker that lets a task use one without ever holding it.

| Command | What it does |
|---|---|
| `sokar vault login AGENT` | Runs an agent's own login and stores the credential it produces. |
| `sokar vault import AGENT` | Copies a credential the agent already holds on this host into the vault. |
| `sokar vault put NAME` | Stores a credential, read from standard input. |
| `sokar vault list` | Lists the names the vault holds. Never the values. |
| `sokar vault remove NAME` | Removes a credential from the vault. |
| `sokar vault unlock` | Caches the vault passphrase in the kernel keyring for this session. |
| `sokar vault lock` | Drops the cached passphrase. The next command asks for it again. |
| `sokar vault passphrase` | Re-encrypts the vault under a different passphrase. |
| `sokar vault serve` | Serves the credential proxy for one task on a unix socket. |
| `sokar vault relay` | Forwards a port in a task's namespace to the broker socket. |
| `sokar vault agent` | Runs an ssh-agent that signs with a key from the vault. |

**Three ways a credential gets in**, and they are not interchangeable: `login` runs the agent's own
authentication in a throwaway container and needs nothing installed on the machine; `import` copies
what an already-signed-in install is holding; `put` takes a value you have. The last three commands
are started per task rather than typed.

## gate

Nothing an agent pushes reaches a real upstream without passing through here.

| Command | What it does |
|---|---|
| `sokar gate pending` | Lists what the agent pushed and how long it has been waiting. |
| `sokar gate review NAME` | Shows what a pending push would change. |
| `sokar gate checkout NAME` | Opens waiting work as a copy you can read. It can only go back to the gate. |
| `sokar gate approve NAME` | Forwards a reviewed push to the upstream. |
| `sokar gate reject NAME` | Discards a pending push without forwarding it. |
| `sokar gate protect` | Installs a pre-push hook that catches unapproved agent work. |
| `sokar gate check` | Reads a pre-push hook's input and refuses agent work nobody approved. |
| `sokar gate backup` | Writes the mirror to a single verifiable bundle file. |
| `sokar gate restore` | Restores the mirror from a bundle, refusing to overwrite an existing one. |
| `sokar gate serve` | Serves the project mirror for an agent to push to. |

`check` is run by the hook `protect` installs, not by hand. `serve` is started per task.

## agents

```
sokar agents [--verbose] [--supply-chain] [--directory=<path>]
```

Lists the agents installed on this machine. `--verbose` adds what each one needs to reach;
`--supply-chain` reports what its install pins, so "which version ran" is answerable from the
definition rather than from a build log.

## project

```
sokar project list
sokar project delete PROJECT [--dry-run] [--force]
```

Lists the projects this machine has run tasks for, and removes what Sokar built for one.
`sokar projects` and a bare `sokar project` both list, as `sokar tasks` and a bare `sokar task` do:
a command whose name is a noun answers the question it looks like it is asking.

**`delete` does not delete the project.** The project file, the checkout and the real upstream are
the operator's and are not touched. What goes is the gate mirror, the task image, the build
directory, the registry entry and every task with it - all of which a later run rebuilds, which is
what makes it safe to offer. It refuses while work is waiting unreviewed at the gate, while tasks
are still up, and when a task will not give up what it holds.

## setup

```
sokar setup [--uninstall]
```

Installs the OCI hooks into this user's podman configuration. **A task run does this itself** when
the descriptors are missing or an upgrade left older ones behind, so it is rarely typed. It cannot
be the package's job: podman reads hook descriptors per user, and an install script running as root
does not know whose configuration to write.

## doctor

```
sokar doctor
```

Reports paths and process hardening state, and whether this machine can actually run a task. Each
failing check names the one thing to do about it.

## panic

```
sokar panic [--dry-run]
```

Stops every running task and every helper, without removing anything. Nothing is destroyed and
everything can be resumed.

## daemon

| Command | What it does |
|---|---|
| `sokar daemon connect` | Bridges standard input and output to the daemon's socket, for ssh. |

Used as an ssh `ProxyCommand` so an interface on another machine can reach this one's daemon
without the daemon ever binding a network port.
