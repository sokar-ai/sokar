# Sokar Commands

Every command, in the shape the CLI actually has. `sokar <command> --help` is always the
authority — this page exists so you can find the name without guessing it.

The [cheat sheet](cheat-sheet.md) is the other way round: it starts from what you are trying to
do rather than from the command tree. Every key a project file can carry is in
[the project file](project-file.md).

## The fourteen groups

| | |
|---|---|
| [`task`](#task) | Runs and inspects agent tasks. |
| [`shield`](#shield) | Inspects and changes what a task may reach. |
| [`vault`](#vault) | Manages stored credentials. |
| [`credentials`](#credentials) | Records which credential each destination is connected to with. |
| [`gate`](#gate) | Reviews and forwards what an agent pushed. |
| [`talk`](#talk) | Shows and moves the messages a task exchanges with other tasks. |
| [`agents`](#agents) | Lists the agents installed on this machine. |
| [`providers`](#providers) | Lists the model providers declared here, and what the vault holds for each. |
| [`project`](#project) | Follows project repositories, and lists the projects this machine follows. |
| [`setup`](#setup) | Installs the OCI hooks into this user's podman configuration. |
| [`doctor`](#doctor) | Reports paths and process hardening state. |
| [`panic`](#panic) | Stops every running task and every helper, without removing anything. |
| [`daemon`](#daemon) | Reaches the daemon on this machine. |
| [`completion`](#completion) | Prints the TAB completion script for a shell. |

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
want it thrown away. Keeping is the default because the command a person reaches for first must not
remove what it has just made.

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
| `sokar vault init` | Creates an empty vault and sets its passphrase, asked twice. |
| `sokar vault login AGENT` | Runs an agent's own login and stores the credential it produces. |
| `sokar vault import AGENT` | Copies a credential the agent already holds on this host into the vault. |
| `sokar vault put NAME` | Stores a credential, read from standard input, under the name of the provider it is for. |
| `sokar providers` | Lists the model providers declared here, the agents that drive each, and what the vault holds for them. |
| `sokar vault list` | Lists the names the vault holds. Never the values. |
| `sokar vault remove NAME` | Removes a credential from the vault. |
| `sokar vault unlock` | Caches the vault passphrase in the kernel keyring for this session. |
| `sokar vault lock` | Drops the cached passphrase. The next command asks for it again. |
| `sokar vault passphrase` | Re-encrypts the vault under a different passphrase. |
| `sokar vault devices` | Lists what can open this vault - the passphrase and each device - and what each is worth. |
| `sokar vault revoke ID` | Removes a device's way into the vault. The last way in is never removed. |
| `sokar vault serve` | Serves the credential proxy for one task on a unix socket. |
| `sokar vault relay` | Forwards a port in a task's namespace to the broker socket. |
| `sokar vault agent` | Runs an ssh-agent that signs with a key from the vault. |
| `sokar vault credential` | Answers git's credential protocol from the vault. |

**Three ways a credential gets in**, and they are not interchangeable: `login` runs the agent's own
authentication in a throwaway container and needs nothing installed on the machine; `import` copies
what an already-signed-in install is holding; `put` takes a value you have. `serve`, `relay` and
`agent` are started per task rather than typed, and `credential` is run by git.

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

## credentials

Which secret a destination is connected to with, and where that secret lives. No command here reads
or prints a value.

| Command | What it does |
|---|---|
| `sokar credentials list` | Lists the credentials this machine connects out with. |
| `sokar credentials declare MATCH --kind=KIND` | Records what a destination wants and where its value lives: `--vault`, `--file`, `--env` or `--agent`. |
| `sokar credentials check URL` | Says which credential a URL would use, and whether it would work. |
| `sokar credentials forget MATCH` | Forgets a credential record. The value itself is left alone. |
| `sokar credentials keys` | Lists the ssh keys this account has, without reading any of them. |
| `sokar credentials trust-host HOST [--fingerprint=SHA256:...]` | Shows the keys a host offers, and records the one you confirm. |

## talk

A task has a mailbox, and these are the commands for what goes through it. Each names the task by
its container name.

| Command | What it does |
|---|---|
| `sokar talk peers --project=NAME` | Lists the peers a project's tasks may address. |
| `sokar talk held TASK` | Lists the messages waiting for a person - held, or refused by the filter - and the ones kept that nobody may send, each with where it stands and why. |
| `sokar talk read TASK ID` | Shows a held message, or one the filter refused, in full - who wrote it, when, to whom, why, and what it says. |
| `sokar talk release TASK ID [--refuse]` | Sends a held message on its way, or delivers a refused one after all; `--refuse` refuses it for good. |
| `sokar talk hold TASK PEER [--release] [--mode=MODE]` | Holds everything for a peer, releases it, or sets how much is asked: `prompt`, `allow`, `deny` or `off`. |
| `sokar talk say TASK PEER [--kind=KIND] [--context=ID]` | Writes a person's own message into a conversation; the text is read from standard input. |
| `sokar talk pass TASK` | Moves the task's messages along once, rather than waiting for the daemon. |
| `sokar talk verify TASK` | Walks the task's message record and names the first entry that does not check out. |
| `sokar talk key [--as=PRINCIPAL] [--publish]` | Prints this machine's signing key as a peer's `allowed_signers` line. |

**Read before you release.** A held message is waiting for a decision, and `talk read` is how that
decision is about what the message says: it shows the text exactly as written, with every control
character written out rather than acting on your terminal. Released or refused, a message is never
edited - the record shows what the task said.

**A message the filter refused can be sent after all**, by a person who has read it. It goes straight
to its peer's transport, and the record says it went despite the filter. A peer that checks what it
receives may refuse it again. What a person refused for good, and what the filter could not check at
all, can be read and never sent.

## agents

```
sokar agents [--verbose] [--supply-chain] [--directory=<path>]
```

Lists the agents installed on this machine. `--verbose` adds what each one needs to reach;
`--supply-chain` reports what its install pins, so "which version ran" is answerable from the
definition rather than from a build log.

## project

```
sokar project follow NAME URL [--signed-by=<key> | --unverified] [--dry-run] [--accept-rewrite]
sokar project following
sokar project list
sokar project unfollow NAME [--dry-run] [--force]
```

**A project comes to be on a machine by the machine following its repository**, and by nothing
else: `follow` takes the project's configuration from that repository from now on, checked against
the key given with `--signed-by`. `--unverified` follows without a key, and says everywhere
afterwards that whoever can push there decides what tasks here may reach. `following` lists the
follows and what each last applied; `list` lists the projects and what each holds. `sokar projects`
and a bare `sokar project` both list.

`unfollow` stops following and removes what Sokar built for the project - the gate mirror, the task
image, the build directory and its tasks. The repository and the real upstream are not touched. It
refuses while work waits unreviewed at the gate or tasks are still up; `--dry-run` says what would go.

## providers

```
sokar providers
```

Lists the model providers declared on this machine - by the agent packages, or by a file under
`~/.local/share/sokar/providers` - which agents each serves, and what the vault holds for it. It
never shows a value, and says so when the vault is locked rather than reporting nothing stored.

## setup

```
sokar setup [--uninstall]
```

Installs the OCI hooks into this user's podman configuration. **Starting a task does this itself** when
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

The daemon itself is `sokard`, a separate binary: `sokard --help` says what it is and
`sokard --version` which build, and neither starts it. [Running the daemon](daemon.md) covers the
socket's lifetime and the systemd user unit the packages install.

## completion

```
sokar completion bash
sokar completion zsh
```

Prints the script that turns TAB into a question for Sokar. **The deb and the rpm install both
already** — `/usr/share/bash-completion/completions/sokar`, and the zsh one where that
distribution's zsh looks — so on a packaged install there is nothing to do but open a new shell.
This command is for the cases the package cannot reach: a binary somebody copied, or a shell whose
completion directory is not the system one.

    sokar completion bash > ~/.local/share/bash-completion/completions/sokar
    sokar completion zsh  > ~/.zfunc/_sokar     # with ~/.zfunc in $fpath before compinit

**What it completes is read live, not baked in.** Subcommands and options come from the command
tree, and names come from the machine — each command offering the names *it* can use, which is the
point: `task attach`, `task logs`, `task status` and `task clearance` offer the tasks that are up,
`task stop`, `task remove` and `task label` every task, and `project unfollow` the projects. It is
the same list the command prints when you get the name wrong, so the two cannot disagree.

`task start` is the exception, and deliberately: its argument is a task name *within a project* —
`shell`, not `sokar-utils4j-shell` — so the container names the other verbs offer would be the
wrong list there. It completes its options and nothing else.

**A command names a project, it does not point at a file.** `--project` takes the name
`sokar project list` prints, not a path to a `project.yml`. Which project a task belongs to must
not depend on which directory somebody was standing in, and a name resolves through what this
machine knows: the verified clone of a project it follows first, then where a task last read one.
A name this machine does not have is refused with the names it does have.

**A task always names its repository.** `sokar task start shell --repository backend` says which of
the project's repositories the work is for. There is no default, not even for a project that has
exactly one: naming it is one word, and a default would mean a project that grew a second
repository silently changed what an existing command does. The project's own repository - the one
holding `project.yml`, the planning and the issues - is named after the project and is one of the
choices; that is how an agent gets a task for planning. Leaving it out is refused, and the refusal
lists what there is to choose from. `sokar project list` says which repositories a project has.

**A task name** is lowercase letters, digits and hyphens, starting and ending with a letter or
digit, and not only digits; `sokar-<project>-<task>` may have at most 65 characters. Anything else
is refused before an image is built, with a name that would do: `Foo Bar` becomes `foo-bar`. A
container name is taken as the task it names, so `sokar task start sokar-utils4j-shell` in that
project starts `shell` again rather than a second task beside it.

Completion only ever reads. Nothing is started, stopped or changed by pressing TAB, and when
something cannot be answered the answer is no candidates rather than an error in the middle of
your command line.
