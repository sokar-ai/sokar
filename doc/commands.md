# Sokar Commands

Every command, to find its name. `sokar <command> --help` has every option.
[For a given job](#for-a-given-job) starts from what you want to do instead; the keys of a project
file are in [the project file](project-file.md).

| Group | What it does |
|---|---|
| [`task`](#task) | Runs and inspects agent tasks. |
| [`shield`](#shield) | Inspects and changes what a task may reach. |
| [`vault`](#vault) | Manages stored credentials. |
| [`credentials`](#credentials) | Records which credential each destination is connected to with. |
| [`gate`](#gate) | Reviews and forwards what an agent pushed. |
| [`talk`](#talk) | Shows and moves the messages a task exchanges with other tasks. |
| [`agents`](#agents) | Lists the agents installed on this machine. |
| [`providers`](#providers) | Lists the model providers, and what the vault holds for each. |
| [`project`](#project) | Follows project repositories, and lists the projects here. |
| [`setup`](#setup) | Makes this account ready: OCI hooks, daemon, vault. |
| [`doctor`](#doctor) | Checks whether this machine can run a task. |
| [`prune`](#prune) | Shows what nothing owns any more; removes it with `--yes`. |
| [`panic`](#panic) | Stops every task and helper, removing nothing. |
| [`daemon`](#daemon) | Reaches the daemon on this machine. |
| [`completion`](#completion) | Prints the TAB completion script for a shell. |

## For a given job

TASK is a name as `sokar task list` shows it; leave it out and the command lists the names it would
take. PROJECT is a name from `sokar project list`, never a path.

**Set up this account.** `sokar setup`, then `sokar doctor`; `sokar agents` lists the installed
agents. Preparing the machine itself is in [running Sokar](running.md#preparing-a-machine).

**Get a credential in.** `sokar vault login claude` runs the agent's own login in a throwaway
container and needs nothing installed. `sokar vault import claude` copies what an install already
signed in here holds; a second login would replace what that install uses. `sokar vault put NAME`
stores a value you already have; `sokar providers` says which names to use.

**Open and close the vault.** `sokar vault unlock --for 30m` caches the passphrase for a while (there
is no default limit), `sokar vault lock` drops it, `sokar vault passphrase` changes it. To start
over, `sokar vault clear --yes` removes it, and `sokar vault init` makes a new one.

**Start work.** In a checkout, `sokar task start` works in the project that names its repository,
else in `default`, and names the task after the repository. Elsewhere, name both:
`sokar task start -p PROJECT -r REPOSITORY`. The same verb creates a task, brings back a stopped one
with its workspace, and refuses a running one. Leaving the session keeps the container; `--rm` does
not. For another project's settings, `sokar project follow NAME URL --signed-by KEY`.

**Find and re-enter a task.** `sokar task list`, `sokar task status TASK`, `sokar task attach TASK`,
`sokar task logs TASK gate.log -f` to follow one log.

**End a task.** `sokar task stop TASK` keeps everything. `sokar task remove TASK` destroys the
workspace, which lives in the container; `--rescue` first pushes unpushed work to the gate.
`sokar panic` stops everything and removes nothing.

**Review what an agent pushed.** Inside a task, `git push` goes to the gate. Outside:
`sokar gate pending`, then `sokar gate review`, `sokar gate checkout`, `sokar gate approve` or
`sokar gate reject`, each with `-p PROJECT NAME`.

**Change what a task may reach.** `sokar shield egress --add-set maven` or
`--add-domain example.com --dry-run`; `sokar shield sets` lists the sets. While it runs,
`sokar task clearance TASK prompt` asks about each blocked connection.

| Symptom | Try |
|---|---|
| a command refuses and names no task | `sokar task list`, or leave the name out |
| the agent cannot authenticate | `sokar vault list`, then `sokar doctor` |
| is there work in there I would lose? | `sokar task status TASK`, while it still runs |
| a task starts and then does nothing | `sokar task logs TASK`: something it needs is probably blocked |
| a push never arrives | `sokar gate pending`: it waits for review |
| "hooks are not registered" | `sokar setup`; starting a task does it too |
| everything at once | `sokar panic`, which removes nothing |

## task

| Command | What it does |
|---|---|
| `sokar task start [TASK]` | Creates a task, or brings back a stopped one with its workspace. |
| `sokar task list` | Lists the tasks, with how long each has been in its state. |
| `sokar task status TASK` | Everything known about one task, including unpushed work. |
| `sokar task logs TASK [LOG]` | Shows what the task's helpers on this machine wrote. |
| `sokar task attach TASK` | Opens a shell in a running task. Leaving it does not end it. |
| `sokar task stop TASK` | Stops a task and its helpers. Container and workspace stay. |
| `sokar task remove TASK` | Removes a stopped task: container, state and workspace. |
| `sokar task label TASK [CAPTION]` | Gives a task a caption. Not a rename. |
| `sokar task prepare -p PROJECT` | Builds the project's task image without starting a task. |
| `sokar task clearance TASK MODE` | Sets what happens to a blocked connection: `prompt`, `allow`, `deny` or `off`. |

**`task start`**
- `-p PROJECT`: without it, the project that names the checkout you stand in, else `default`.
- `-r REPOSITORY`: which of the project's repositories to work on (see [names](#completion)).
- Without a name, the task is named after its repository, or the next free name (`my-first-2`).
- By default you get the agent's session. `--attach shell` gives a shell, `-P`/`--prompt` runs the
  agent unattended, `--detach` hands over nothing, `--now` returns before the image is built.
- `--rm` removes the container when you leave. By default it is kept.
- A running task is refused; use `attach`.
- After a reboot, `task list` shows which tasks went down and `sokar task start --restarted` brings
  them all back. Nothing comes back by itself. The first start after a reboot needs the vault unlocked,
  even for a task with no credential of its own, because the task's gate token is kept there.
- A task whose agent names its sessions continues the conversation it was having, after a stop or a
  reboot alike, and the start says whether it continued or began fresh. An agent that names no
  session, or a continuation that failed, begins fresh and says so; nothing runs twice unasked.

**`task remove`** refuses when the task is running (`stop` it first, or `--force`), holds commits
that never reached the gate (`--rescue` pushes them to the gate, `--force` discards them), or its
contents are unknown. A task never removes itself when it ends.

**`task logs`** shows the files of the gate, broker, relay, resolver and clearance watcher, not the
agent's output. `events.jsonl` is what the firewall blocked: look there when a task starts and then
does nothing. The logs **do not survive a restart**. Unpushed work is only known while the task runs,
or from the note `task stop` leaves.

**`task status`** says what was recorded when the task was created: its agent and provider, its mode
(`shell`, `agent` or `unattended`, kept when a stopped task is brought back), the branch its work goes
to, and for an unattended task the prompt it was given, kept after it has finished. `state` is the
container runtime's own words; `activity` says what the work is doing:

| Activity | Meaning |
|---|---|
| `working` | producing output |
| `idle` | up, producing nothing, and not waiting for anybody as far as anything can tell |
| `waiting` | waiting for a person, said by whatever asked (a clearance prompt, say); `waiting` names what about |
| `ended` | up, and its agent's run has ended: nothing more will happen in it on its own |
| `dead` | the container is not up |
| `unknown` | nothing on this side can see what it does, such as a task with a terminal attached |

A quiet task is not a waiting one: waiting is never guessed from how long nothing happened. An ended
run is said on a line of its own, `ended`: `finished at <time>`, or `with an error at <time>`, whose
(the provider's, with its HTTP status, or the agent's) and its text. Work the agent wrote and never
pushed is named there with the command that saves it, `sokar task remove TASK --rescue`; an agent that
cannot read its own end says nothing. The `screen` line is separate, a reading of the agent's own
screen against rules its package declares, and says when an agent declares none. `since` is when the
current state began, as an age and an instant.

## shield

What a task may resolve and reach. See [the firewall](security.md) and [DNS](security.md).

| Command | What it does |
|---|---|
| `sokar shield egress` | Shows what a project may reach; adds or removes sets and hosts. |
| `sokar shield sets` | Lists the egress sets a project can name in `project.yml`. |
| `sokar shield subscribe` | Follows the blocked connections a running watcher reports. |
| `sokar shield read` | Reads blocked connections from the firewall log. Started per task. |
| `sokar shield watch` | Prompts for each blocked connection. Started per task. |
| `sokar shield dns` | The task's resolver for allowed domains. Started per task. |

## vault

Credentials, and the broker that lets a task use one without holding it. See
[authentication](credentials.md) and [vault keyslots](credentials.md).

| Command | What it does |
|---|---|
| `sokar vault init` | Creates an empty vault; asks for its passphrase twice. |
| `sokar vault login [AGENT]` | Runs the agent's own login in a throwaway container and stores the result. |
| `sokar vault import [AGENT]` | Copies a credential the agent already holds on this host. |
| `sokar vault put NAME` | Stores a value from standard input under the provider's name. |
| `sokar vault authorize NAME` | Grants an OAuth authorization once in any browser and keeps it. |
| `sokar vault list` | Lists the names held. Never the values. |
| `sokar vault remove NAME [--without-revoking]` | Removes an entry, revoking a granted authorization first. If revoking fails, the entry is kept unless `--without-revoking`. |
| `sokar vault unlock` | Caches the passphrase in the kernel's user keyring, where the daemon finds it too; `--for` bounds how long. |
| `sokar vault lock` | Drops the cached passphrase. |
| `sokar vault passphrase` | Re-encrypts the vault under a new passphrase. |
| `sokar vault devices` | Lists what can open the vault: the passphrase and each device. |
| `sokar vault revoke ID` | Removes a device's way in. The last way in is never removed. |
| `sokar vault clear [--yes] [--dry-run] [--force]` | Removes the vault, its backup and lock, and what the keyring caches of it, with what the transports keep in it. Lists it first; `--yes` clears it. |
| `sokar vault serve` | The credential proxy a task's requests go through. Started per task. |
| `sokar vault relay` | Forwards a port in a task's namespace to the proxy's socket. Started per task. |
| `sokar vault agent` | An ssh-agent that signs with a key from the vault. Started per task. |
| `sokar vault credential` | git's credential helper. Run by git. |

**`vault authorize`** entry kinds:
- `oauth-device` (settings `client_id`, `device_authorization_url`, `token_url`, `scopes`) shows a
  link and a code.
- `oauth-code` (settings `client_id`, `authorization_url`, `token_url`, `scopes`, `redirect_port`,
  default 9420) answers at `http://127.0.0.1:<redirect_port>/callback`. From another machine,
  forward that port with `ssh -L` first.
- Either takes `revocation_url`.

**`vault lock`** answers one of three things: the passphrase was cleared, nothing was cached, or
whether one is cached could not be established. The last exits 70 and says what to check: treat the
passphrase as still cached until the keyring answers. A cached passphrase that expired (`vault unlock
--for`) or whose keyring was revoked counts as not cached. The cache is looked for in the user
keyring first and the session keyring second, so a long-running process that outlived its login still
finds it. `vault passphrase` says so too when it cannot drop the cached passphrase, which is the wrong
one after the change.

**`vault clear`** removes the vault in one step, in the shape of [clear](#clear): without `--yes` it
lists what would go and removes nothing, and each line says `removed`, `would go`, `not removed` with
why, or `for you`. It names what the vault holds - credentials, grants, the accounts a transport keeps,
stopped tasks' tokens - and removes the vault file with every device's keyslot, its `.old` backup and
its `.lock`, and the passphrase and device shares cached in the kernel keyring. Each deploy key is
named `for you`: it goes with the vault, and only the forge removes it there.

A transport's account goes with it. First, for each project whose `mail.transports.<scheme>` names
settings of its own - a server elsewhere - the transport's `clear --project` runs while the vault still
holds that account's token; then the vault goes; then the transport's account-wide `clear` runs. It
refuses, removing nothing of the vault:
- while a task runs with a token from the vault, even with `--force`: stop the task first. With the
  vault locked, every running task is named, since what it holds cannot be read;
- while the vault is locked, since what it holds cannot be named; `--force` clears it unread;
- when a transport could not clear a project's account; `--force` clears the vault all the same, and
  that account stays on its server.

Afterwards `sokar vault init` starts as on a new machine.

## gate

Nothing an agent pushes reaches a real upstream without passing here.

| Command | What it does |
|---|---|
| `sokar gate pending` | Lists waiting pushes and how long each has waited. |
| `sokar gate review NAME` | Shows what a pending push would change. |
| `sokar gate checkout NAME` | Opens waiting work as a read copy. It can only go back to the gate. |
| `sokar gate approve NAME [--commit=ID] [--signed]` | Forwards a push. `--commit` names the commit you reviewed and refuses if the push moved since. `--signed` merges it as a commit signed with your own git key. |
| `sokar gate reject NAME [--reason]` | Discards a push and tells the task. `--reason` reads why from standard input. |
| `sokar gate protect` | Installs a pre-push hook that catches unapproved agent work. |
| `sokar gate check` | The check that hook runs. |
| `sokar gate backup` | Writes the mirror to a verifiable bundle file. |
| `sokar gate restore` | Restores a mirror from a bundle; never overwrites one. |
| `sokar gate serve` | Serves the mirror for an agent to push to. Started per task. |

All but `pending`, `protect` and `check` take `-p PROJECT` and `-r REPOSITORY`.
`sokar gate pending --project default` lists waiting work of every repository in `default`.

## credentials

Which secret a destination uses, and where it lives. Nothing here prints a value.

| Command | What it does |
|---|---|
| `sokar credentials list` | Lists the credentials this machine connects out with. |
| `sokar credentials declare MATCH --kind=KIND` | Records a destination's credential: `--vault`, `--file`, `--env` or `--agent`. |
| `sokar credentials check URL` | Says which credential a URL would use, and whether it works. |
| `sokar credentials forget MATCH` | Forgets a record. The value is left alone. |
| `sokar credentials deploy-key PROJECT [-r REPOSITORY] [--read-only \| --write] [--new]` | Creates a deploy key: secret half into the vault, public half printed once for the forge. |
| `sokar credentials keys` | Lists this account's ssh keys without reading them. |
| `sokar credentials trust-host HOST [--fingerprint=SHA256:...]` | Shows a host's keys and records the one you confirm. |

**Deploy keys**: read-only for the project's own repository, write for a work repository. Running it
again prints the same key; `--new` replaces it. `project unfollow` forgets these keys and names each
one to remove at the forge.

## talk

A task's mailbox. Each command names the task by its container name.

| Command | What it does |
|---|---|
| `sokar talk peers --project=NAME` | Lists the peers a project's tasks may address. |
| `sokar talk held TASK` | Lists messages waiting for a person: held, refused by the filter, or unsendable. |
| `sokar talk read TASK ID` | Shows one such message in full, with control characters written out. |
| `sokar talk release TASK ID [--refuse] [--reason]` | Sends it on, even if the filter refused it; `--refuse` refuses it for good. The sender is told. |
| `sokar talk hold TASK PEER [--release] [-p PROJECT]` | Holds a peer's messages for every task of the project, or releases them. How closely a peer is watched is set in [`project.yml`](project-file.md) (`mail.rules`), not here. |
| `sokar talk say TASK PEER [--kind=KIND] [--context=ID]` | Writes your own message to a peer, from standard input. |
| `sokar talk tell TASK` | Writes your own words into the task's inbox, from standard input. |
| `sokar talk pass TASK` | Moves the messages along once, without waiting for the daemon. |
| `sokar talk log TASK` | What happened to each message, never its text. |
| `sokar talk verify TASK` | Names the first entry of the message record that does not check out. |
| `sokar talk key [--as=PRINCIPAL] [--publish]` | Prints this machine's signing key as an `allowed_signers` line. |
| `sokar talk join PROJECT PERSON [--reset]` | Makes a person an account in the project's conversation and shows the login once. `--reset` sets a new password. |

A message is never edited. Read it before you release it. A message released despite the filter is
recorded as such, and the receiving peer may refuse it again.

## agents

`sokar agents [--verbose] [--supply-chain] [--directory=<path>]` lists the installed agents.
`--verbose` adds what each needs to reach; `--supply-chain` shows what its install pins.

## project

```
sokar project follow NAME URL [--signed-by=<key> | --unverified] [--dry-run] [--accept-rewrite]
sokar project following
sokar project list
sokar project unfollow NAME [--dry-run] [--force]
sokar project clear NAME [--yes] [--dry-run] [--force]
sokar project default [list | add ADDRESS [--name=NAME] | remove NAME]
sokar project enroll PROJECT [--remove] [--as=PRINCIPAL]
```

- **`follow`** takes the project's configuration from its repository, checked against the
  `--signed-by` key. `--unverified` skips the check, and Sokar says so wherever the project is shown.
- **`following`** lists the follows; **`list`** (also `sokar projects`) lists the projects.
- **`unfollow`** removes the mirror, image, build directory and tasks. The repository and upstream
  are untouched. It refuses while work waits at the gate or tasks run. `--force` removes it all the
  same and destroys that work; with `--force` a project this account does not follow is not an error,
  and whatever is left of it is swept, so it is the one command a cleanup script can run blind after
  a failure.
- **`clear`** clears the project from this machine in one step: its tasks with their workspaces and
  mailboxes, its mirrors and image, the follow, its conversation and what this machine kept for it,
  and its deploy keys from the vault. Without `--yes` it only lists what would go. It names what only
  your credentials can remove: each deploy key at the forge, and this machine's line in the
  project's `machine-signers`. See [clear](#clear).
- **`default`** is the project every machine has and nobody follows. It is `guarded`, reaches only
  the agent's provider, refuses blocked connections and has no conversation. `task start` without
  `-p`, in a checkout no followed project names, adds that checkout's `origin` to it (a token in the
  URL is kept but not shown). It cannot be unfollowed, and its settings cannot be changed: for other
  settings, make a project repository and follow it.
- Approved work in `default` reaches `origin` with your own ssh agent or key, or a token stored with
  `sokar credentials`; never handed into the task. The daemon has no ssh agent, so through it only a
  key without a passphrase works. Trust an ssh host first with `sokar credentials trust-host`; until then the
  task does not start.
- **`enroll`** proposes this machine's message key as a signer of the project, as
  `enroll-<machine>` in the gate. Approve it with `sokar gate approve enroll-<machine> --signed`.
  `--remove` undoes it.

## providers

`sokar providers` lists the model providers declared here (by agent packages or files under
`~/.local/share/sokar/providers`), which agents use each, and what the vault holds for it. It never
shows a value, and says when the vault is locked.

## setup

```
sokar setup [--hooks-only] [--uninstall]
```

Makes this account ready. Each step runs only if needed, so running it again is safe:

- registers the OCI hooks in your podman configuration (a task start does this too);
- starts the daemon (`systemctl --user enable --now sokard`), or says what to run instead;
- creates the vault, **only at a terminal**, since a person must type the passphrase.

It ends by saying whether the account is ready. `--hooks-only` registers only the hooks;
`--uninstall` removes them.

## doctor

`sokar doctor` reports paths, process hardening and whether this machine can run a task. Each
dependency is checked by name: podman, the hook registration, podman's rootless network, dnsmasq's
`nftset` support, `nft`, `git`, `nsenter`, the SELinux policy and the kernel keyring, beside the vault,
the daemon and each followed project. Each line has one of four states:

| State | Meaning |
|---|---|
| `OK` | present and working |
| `DEGRADED` | works, less well than it should; the line says what it costs |
| `UNKNOWN` | the check could not tell, and says so rather than assume the good case |
| `MISSING` | a task would fail, or run without something it needs |

Every state but `OK` names the one thing to do. Only `MISSING` fails the command (exit 69); a degraded
machine runs tasks. A machine using slirp4netns rather than pasta, for example, is degraded: the gate
then binds every interface and is reachable from this machine's network, with the per-task token what
keeps it shut. A binary hidden by another copy is named with both halves:

```
not used /usr/libexec/sokar/agents/sokar-agent-<name>
         hidden by /home/<user>/.local/share/sokar/agents/sokar-agent-<name>
```

The `vault` line says whether a vault exists yet. The `daemon` line says whether `sokard` runs, and
whether it is the same version as this command: after an update it restarts itself, and the line says
when one did not (see [After an update](running.md#after-an-update)). The daemon answers the same
checks, and a `ready` flag by the same rule, so a machine is never ready to one and not to the other.
It runs external programs, so it takes a moment and is not something to poll.

## prune

```
sokar prune [--yes] [--including-work]
```

Lists what nothing owns any more, and removes it only with `--yes`: unfollowed projects whose project
file is gone, leftovers of tasks whose container is gone (after 15 minutes unchanged), and images or
records named after projects that no longer exist. Running tasks and followed projects are never
touched.

**Unreviewed work is kept**: an unreviewed push, a stopped task's workspace, a held or unsent
message. `--including-work` removes those too. The clearance journal is always kept.

## clear

```
sokar clear [--yes] [--dry-run] [--force]
```

Clears this account in one step, for when you are done with a machine: every project as
`project clear` does, the tasks of `default`, then what each transport keeps for the account (a
homeserver it runs, say). Without `--yes` it lists all of it and removes nothing. The vault, your keys
and the installed packages stay: clearing is not uninstalling. `sokar vault clear` removes the vault.

Each line says `removed`, `would go`, `not removed` with why, or `for you`: a deploy key to remove
at its forge, or this machine's line to take out of a project's `machine-signers`. The
machine holds no forge token and not your signing key, so it never tries. It refuses while work
waits at the gate or tasks run; `--force` clears those too. Run again, it says there is nothing to
clear. With the vault locked, the deploy keys it cannot read are said to be unreadable, never answered
as none.

## panic

`sokar panic [--dry-run]` stops every running task and helper. Nothing is removed; everything can be
started again.

## daemon

`sokar daemon connect` bridges standard input and output to the daemon's socket, for use as an ssh
`ProxyCommand`. The daemon is `sokard`; see [the daemon](running.md#the-daemon).

## completion

```
sokar completion bash > ~/.local/share/bash-completion/completions/sokar
sokar completion zsh  > ~/.zfunc/_sokar     # with ~/.zfunc in $fpath before compinit
```

The deb and rpm packages already install completion; this is for a copied binary or a non-standard
completion directory. TAB offers the names each command can use (running tasks for `attach`, all
tasks for `stop`, projects for `unfollow`). `task start` completes only its options. Pressing TAB
never changes anything.

**Names.** No command asks for a missing name; a missing or unknown one is refused with the names
that would work.
- `--project` takes a name from `sokar project list`, never a path.
- A task works on one repository. In a checkout without `-p`, it is the checkout's repository. With
  `-p`, name it with `--repository`, even if the project has only one. The project's own repository
  (holding `project.yml`) is named after the project and can be chosen for planning work.
- A task name is lowercase letters, digits and hyphens, starts and ends with a letter or digit, is not
  only digits, and `sokar-<project>-<task>` is at most 65 characters. A container name such as
  `sokar-myproject-backend` is taken as the task `backend`.
