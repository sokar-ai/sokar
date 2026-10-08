# Getting started

This page takes you from nothing to a first task and an approved change. At the end an agent
works inside a hardened container, your real credential stays on your machine, and the
agent's work waits for your review before it goes anywhere.

Debian, Ubuntu, Fedora and RHEL differ in two places only: how you install, and SELinux.
Both are marked. Everything else is the same.

For every command with a line each, see [commands](commands.md). For the whole setup as one
block to paste, see
[the README](https://github.com/sokar-ai/sokar/blob/main/README.md#getting-started).

## Before you start

**Sokar runs on Linux only.** It is built on kernel machinery: an nftables ruleset inside the
container's network namespace, OCI hooks, user namespaces and the kernel keyring. On macOS or
Windows all of that would sit on the far side of a virtual machine.

**Rootless podman must work as your own user.** `podman info` must succeed without `sudo`.

**Podman 5 or newer is required.** Podman 4 has no `pasta`, which maps your machine's
loopback into a container. Without it the git gate would have to listen on every interface,
on your local network. Sokar refuses to start a task rather than run with less, and
`sokar doctor` says so. **Ubuntu 24.04 LTS cannot provide it**: it ships 4.9.3, and a stable
release never changes a package's major version. Ubuntu 25.10 and 26.04, Debian 13 and
Fedora 43 and 44 ship podman 5.

Sokar calls `podman`, `nft`, `dnsmasq`, `git` and `nsenter`. The packages declare them, so
the package manager pulls them in.

## 1. Install

Two packages: `sokar` is the tool, `sokar-agent-claude` is one agent. Sokar finds agents at
runtime, so a new agent needs no new release of Sokar.

`releases` holds the released versions, `snapshots` every build of `main`; to follow the
builds, change that one word.

### Debian and Ubuntu

```
sudo apt install -y ca-certificates curl gnupg
curl -fsSL https://fuinorg.jfrog.io/artifactory/api/security/keypair/sokar-packages/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/sokar.gpg
echo "deb [signed-by=/usr/share/keyrings/sokar.gpg] https://fuinorg.jfrog.io/artifactory/sokar-dist-deb releases main" \
  | sudo tee /etc/apt/sources.list.d/sokar.list
sudo apt update
sudo apt install sokar sokar-agent-claude
```

- **Dearmor the key.** `apt` wants the binary form at that path. The armored `.asc` fails
  with a verification error that never mentions the format.
- **A new snapshot keeps its version string**, so `apt` says `sokar is already the newest
  version` and you keep the old binary. Reinstall instead:
  `sudo apt install --reinstall sokar sokar-agent-claude`.

### Fedora and RHEL

```
sudo tee /etc/yum.repos.d/sokar.repo <<'EOF'
[sokar]
name=Sokar
baseurl=https://fuinorg.jfrog.io/artifactory/sokar-dist-rpm/releases
enabled=1
gpgcheck=0
EOF
sudo dnf install sokar sokar-agent-claude
```

- **`gpgcheck=0` is deliberate, and temporary.** The repository metadata is signed; the RPMs
  are not yet, so `dnf` would refuse them. Once they are, this becomes `gpgcheck=1` with a
  `gpgkey=` line.
- **A new snapshot keeps its version string**, so `dnf` says `Nothing to do`. Remove and
  install again. `dnf reinstall` silently skips a package that is not installed yet, which
  leaves you with a fresh `sokar` and no agent:
  `sudo dnf remove -y sokar sokar-agent-claude && sudo dnf install sokar sokar-agent-claude`.

**From a local build**, see [building](build.md). It produces Sokar and no agent; the Claude
Code agent is built in [sokar-claude-code](https://github.com/sokar-ai/sokar-claude-code).

## 2. Set up your account

Once per user:

```
sokar setup
```

It registers podman's hooks for your account, starts the daemon (and starts it with your
account from now on), and creates the vault. It asks for the vault passphrase twice, so a typo
cannot become it. Each step runs only if it is not done yet, so running it again is safe.

- **The vault needs a terminal**, because only you may type its passphrase. Without one (a
  script, `ssh` without `-t`) `setup` does the rest and says the vault is left for later.
  Then run `sokar vault init` at a terminal.
- `sokar setup --hooks-only` registers the hooks and nothing else. A task start also
  registers or updates them when they are missing or old, and says so.
- The package cannot do this for you. Podman reads hooks per user, and an install script
  running as root does not know whose configuration to write.

## 3. Check the machine

```
sokar doctor
```

It says where everything lives and what is missing, with the command that fixes it. Read it
before your first task. One line matters on every system:

```
dnsmasq nftset      yes
```

That is how a declared domain becomes reachable, not only resolvable: dnsmasq adds each
address it answers to the firewall's allow set. A dnsmasq without it fails silently. Names
resolve, nothing connects, and no error says why. If it says anything but `yes`, install a
dnsmasq with nftset support (2.87 or later).

### Debian and Ubuntu

Nothing more: `selinux policy` reads `not needed - this machine does not run SELinux`.

### Fedora and RHEL

`doctor` reports one more line until Sokar's SELinux policy is installed:

```
selinux policy      MISSING - a task cannot reach the vault proxy
                    -> sudo /usr/share/sokar/selinux/install-selinux-policy.sh
```

Run that command. It compiles and loads the policy on your machine, because a compiled module
is tied to the policy version of the machine that built it. Without the policy, a task cannot
connect to the vault socket, the denial is not audited, and you see an agent that cannot
authenticate with **nothing in the audit log**.

## 4. Put a credential in the vault

> [!WARNING]
> **Never log in from inside a task.** If an agent there offers a login prompt or a sign-in
> URL, something is wrong. A login inside the container would mint a fresh key there, which
> is the one thing Sokar exists to prevent. Authenticate on your machine and store the result.

Three ways in:

| Your situation | Command |
|---|---|
| the agent is already signed in on this machine | `sokar vault import claude` - **prefer this**; it copies what is there and logs in nowhere |
| anything else, even with nothing installed | `sokar vault login claude` - runs the agent's own login for you |
| you have an API key | `sokar vault put anthropic --type api-key` - asks for it without echoing it |

**`vault login`** builds a throwaway image with the agent and runs the agent's own login in
it, on your terminal, then stores the credential and removes the container. There is no
browser in there: open the printed URL yourself. **Not at this machine? Forward the port
first** (`ssh -L <port>:localhost:<port> <this machine>`), or the redirect to `localhost`
lands on the wrong machine. If the agent is already signed in here, `vault login` refuses
and points to `vault import`, since a second sign-in may revoke the first; `--force` logs in
anyway.

**`vault put`**: type or paste the key at the prompt. A pipe puts it in your shell history.
If you must pipe (a script), run `sokar vault unlock` first, because a piped `put` has no
terminal to ask for the passphrase; use `printf '%s'`, not `echo`, whose newline would become
part of the key. Never pass a credential as a command-line argument.

**Which kind.** Claude Code takes an API key (`--type api-key`) or a subscription token
(`--type oauth`); the wrong one fails like a wrong key. The kind is stored with the
credential, so no task repeats it. The name is the provider's: `sokar providers` lists them.

**A lost passphrase cannot be recovered.** `sokar vault clear --force --yes` removes the vault unread,
`sokar vault init` makes a new one, and you store your credentials again.
Unlocking, locking and automation without a prompt are in [credentials](credentials.md).

## 5. Start a task

In a checked-out repository with an `origin`:

```
sokar task start
```

With no project named, the repository goes into `default`, the project every machine has. Its
settings are Sokar's and cannot be changed: class `guarded`, base image `ubuntu:24.04`, and no
egress beyond what the agent needs. The task is named after the repository (`myproject`, then
`myproject-2`, ...); `sokar task start <name>` chooses another name.

It builds the image, starts the container, starts the agent and reports what it wired up:

```
task           myproject
project        default
security class guarded
agent     claude 2.1.236
vault     /run/user/1000/sokar/sokar-default-myproject/vault.sock -> https://api.anthropic.com
token     ANTHROPIC_API_KEY=sokar_pt_lYyo...
provider  api.anthropic.com reachable; the credential is not, only a task-scoped token
container sokar-default-myproject
gate      http://host.containers.internal:38359/myproject.git
workspace /workspace ready
```

Four of these lines are the security model:

- **`token`**: the agent gets a stand-in token, valid for this task only, never your key.
- **`vault`**: the socket that swaps that token for the real credential on the way out.
- **`provider`**: the provider is reachable, but what the container holds for it is worth
  nothing there.
- **`gate`**: where the agent pushes, on your own machine.

**Commit before you start.** The task takes your checkout's committed history, local commits
included; uncommitted changes stay behind. Your remote is yours: Sokar neither pulls from it nor
pushes to it.

- When the agent exits you get a shell in the workspace. `--attach shell` skips the agent;
  `-P "your prompt"` runs it without a terminal.
- The container is kept when you leave. `sokar task start` puts you back in,
  `sokar task list` shows tasks, `sokar task remove <name>` removes one. `--rm` removes it on
  exit, except after a failure: a failed task is always kept so you can look at it.
- **If something goes wrong and you do not know what**, `sokar panic` stops every task and
  every helper. It removes nothing; everything can be resumed.

## 6. Hand the work back

Inside the container, `/workspace` is a clone from the gate with a remote already set up:

```
git add -A && git commit -m "what the agent did"
git push sokar HEAD:"$SOKAR_TASK_REF"
```

The push lands under `refs/sokar/incoming/`, never on a branch. Nothing has left your machine.

## 7. Review and approve

Back on your machine, from any directory:

```
sokar gate pending                           # what waits, in every project
sokar gate review  -p default myproject      # the full diff
sokar gate reject  -p default myproject      # discard it
sokar gate approve -p default myproject      # push it to the upstream
```

**`approve` is the only command that sends anything anywhere.** It pushes to the repository's
upstream - in `default`, the checkout's `origin`; in your own project, `upstream:` in
`project.yml` - or to `--upstream <url>`. With none it refuses: "No upstream is configured for
this project". `--branch` picks the branch; the default is `main`. In an
`offline` project it always refuses.

The next task starting from that repository is brought up to the upstream first, so approved
work is its base. What waits for review is never touched.

## 8. A project of your own

You need one for settings of your own: more hosts to reach, another security class, a
conversation between tasks. **You write `project.yml` at the root of your repository and commit it.** Sokar writes no
project file anywhere. Only `name`, `security_class` and `base_image` are required:

```yaml
project:
  name: "myproject"
  security_class: "guarded"
  # upstream: "git@github.com:you/myproject.git"   # where approve pushes; required by online
image:
  base_image: "ubuntu:24.04"
egress:
  sets: [os-packages-debian, git-hosting]
```

Every key is in [the project file](project-file.md).

**A machine gets the project by following that repository:**

```
sokar project follow myproject <git-url> --signed-by "ssh-ed25519 AAAA..."
sokar task start --project myproject --repository myproject
```

- `follow` fetches the file, checks the commit's signature against the key you gave, and only
  then applies it. A later commit is picked up the same way.
- **`--signed-by` must come from a person** - a colleague, a wiki - never from the repository
  it verifies. `--unverified` follows without a key and says so wherever the project is shown.
- `--repository` names which of the project's repositories the task works on. A project with
  only its own repository has one, named after the project. Leave it out and Sokar refuses and
  prints the line to type.

The security class decides where work may go. A task cannot raise it.

| Class | The container's `origin` | How work reaches your real remote |
|---|---|---|
| `offline` | the gate on your machine | never |
| `guarded` | the gate on your machine | you review, then `sokar gate approve` |
| `online` | the gate on your machine | the gate passes the task's own branch on to your remote at once, unreviewed |

See [security](security.md) for what each class keeps out, and [running Sokar](running.md)
for the gate's mirror and its seeding.

### Working alone, with no forge

Your code's own repository on this machine can be the whole setup. Commit `project.yml`
into it, follow the path, and approve onto a branch of its own:

```
sokar project follow myproject ~/myproject --signed-by "ssh-ed25519 AAAA..."   # or --unverified
sokar task start --project myproject --repository myproject
sokar gate approve -p myproject myproject --upstream ~/myproject --branch sokar/myproject
git -C ~/myproject merge sokar/myproject
```

Approving onto the branch you have checked out there fails: git does not let a push move
the branch under your working copy. The work stays pending until one approve succeeds.
`upstream: "/home/you/myproject"` in `project.yml` saves naming it each time.

## When something is blocked

**A task reaches nothing that is not declared.** The task's resolver answers NXDOMAIN for
every undeclared name, so no connection is even tried. Inside the container you see:

```
Could not resolve host: repo.maven.apache.org
```

**If your build needs that host, declare it** in `project.yml` rather than allowing it by hand.
The declaration is reviewed and applies to everyone. See what is declared and widen it:

```
sokar shield sets                                # the curated host lists, e.g. maven, nodejs
sokar shield egress                              # what this project may reach, and who decided
sokar shield egress --add-set maven --dry-run    # the hosts it would open, writing nothing
sokar shield egress --add-set maven --add-domain nexus.corp.example
```

The change applies to the next task. Only ports 80 and 443 open; declaring a forge does not
open ssh. A project in `default` uses Sokar's settings: to reach more, make a project of your
own.

**The clearance prompt** is for the other case: a name that resolved to an address the
firewall does not allow yet. You get a desktop notification with Allow and Deny, once per
destination. `--clearance` on `task start` sets `prompt` (default), `allow`, `deny` or `off`.
**Without a desktop session use one of the last three**; `prompt` does not start without one.

Every drop is logged in `events.jsonl` in the task's state directory
(`/run/user/<uid>/sokar/<container>/`, cleared when your session ends), and every decision in
`~/.local/state/sokar/clearance/<container>.jsonl`, which outlives the task. A resumed task
does not ask again what you already answered. The full picture is in
[what a task can reach](reach.md).

## Removing Sokar

**Clean up your account before removing the package:**

```
systemctl --user disable --now sokard
sokar setup --uninstall
```

The first stops the daemon. The second removes the hook descriptors and podman drop-in from
your home, naming each file. No package can remove them, so removing the package first leaves
them pointing at binaries that are gone. Then:

- Debian and Ubuntu: `sudo apt purge sokar 'sokar-agent-*'`
- Fedora and RHEL: `sudo dnf remove sokar 'sokar-agent-*'`

**What is left is yours**, and the packages do not remove it: the vault
(`~/.local/share/sokar/vault.bin`, not recoverable; `sokar vault clear` removes it before you
uninstall), the mirrors (`~/.local/share/sokar/mirrors/`), the followed projects
(`~/.local/share/sokar/projects/`) and agents you installed yourself
(`~/.local/share/sokar/agents/`). **Check the mirrors first:** an unreviewed push exists only
there, and `sokar gate pending` still answers while the package is installed.
