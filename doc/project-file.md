# The project file

Everything `project.yml` can say, and what each key is for.

**You do not need one to begin.** A task started in a checkout with no project named runs in the
built-in project `default`, which uses Sokar's own settings. Write a project file when you want other
settings.

**It lives in the project's own git repository, committed.** A machine gets it with
`sokar project follow <name> <url>`; Sokar never writes one. That command names anything wrong with
the file, such as an egress set this machine does not have.

**Only `project.name`, `project.security_class` and `image.base_image` are required.** Everything else
has a default that suits most projects.

**A project is not a repository.** It is a named unit of work over one or more repositories: its own,
which holds this file, and any it lists under `repositories`. `sokar project list` shows them. A project that
lists repositories works only in those: its own repository holds the file, its planning and its issues, and no
task is started there. A project that lists none works in its own.

```yaml
project:

  # Required. Lower-case letters, digits and hyphens, at most 63 characters.
  # A task's container is named 'sokar-<name>-<task>'.
  name: "myproject"

  # Optional. For people; shown where the project is listed.
  description: "The thing that talks to the payment provider"

  # Required: offline, guarded or online. See security.md.
  #   offline  no network at all; declaring egress is refused.
  #   guarded  reaches what egress names, pushes to a gate, holds no upstream credential.
  #   online   the agent's remote IS the upstream; needs 'upstream'.
  security_class: "guarded"

  # Where approved work for the project's OWN repository goes. Required for online.
  # In guarded the agent never sees it: the gate forwards what a person approved.
  upstream: "git@github.com:you/myproject.git"

  # Optional. The public keys of the people whose signed commits change this file. A commit
  # signed by a key a machine has pinned that adds or removes one here makes the machine pin or
  # unpin it. A key never counts for the commit that adds it.
  # signers:
  #   - "ssh-ed25519 AAAA... you@example.org"

# Optional. The repositories this project's agents change.
# A task works on exactly one, chosen with 'sokar task start --repository <name>' or by the
# checkout it is started in; there is no default. Each has its own mirror, gate and review branch.
repositories:

  backend:
    # Optional. Without an upstream, the work stays on this machine.
    upstream: "git@github.com:you/backend.git"
    description: "The thing that talks to the payment provider"
    # Optional. The issue prefixes this repository owns, each one or more capital letters and named
    # by no other repository. A task on it is told them in SOKAR_ISSUE_PREFIXES ("B,P").
    issues: ["B", "P"]

  frontend:
    upstream: "git@github.com:you/frontend.git"

    # Optional. ADDED to the project's egress, never replacing it. To make a repository reach
    # less, move the set out of the project's block into the repositories that need it.
    egress:
      sets: [nodejs]
      domains: ["registry.internal.example"]

    # Optional. REPLACES the project's limits key by key. A key not written here keeps the
    # project's value, not the default. 'none' opts out of a limit.
    limits:
      memory: "16g"

image:

  # Required. What the task image is built from. apt, apk and dnf are handled; a base without
  # them must ship curl, git, ssh and tmux itself.
  base_image: "ubuntu:24.04"

  # Optional. Extra Containerfile lines, run as root at build time. Keep it small: any change
  # rebuilds the image.
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends jq \
        && rm -rf /var/lib/apt/lists/*

  # Optional, instead of 'snippet': the same lines in a file of their own.
  # snippet_file: "Containerfile.fragment"

  # Optional. Where apt fetches from at build time. This is the default.
  # Use http: Ubuntu mirrors serve no TLS, and apt checks the signed Release file instead.
  # Name one fast mirror rather than a list - apt spreads requests over all of them, so a slow
  # one slows every build. Only the URIs line is replaced; suites and signing keys stay.
  package_sources:
    - "http://azure.archive.ubuntu.com/ubuntu/"

# Optional. What the task may reach. A name not allowed here does not resolve at all.
egress:

  # Curated sets by name. 'sokar shield sets' lists what this machine has.
  sets: [os-packages-debian, git-hosting, maven]

  # Any other host by name. Ports 80 and 443 only.
  domains: ["nexus.corp.example"]

  # Names that must never resolve, whatever allows them; also wins under an allowed parent.
  # It refuses a name, not an address: a host sharing an allowed address stays reachable there.
  refused: ["telemetry.nexus.corp.example"]

# Optional. What one task may consume. These are the defaults.
limits:
  memory: "8g"     # "none" opts out
  pids: 2048       # process count; stops a fork bomb
  cpus: "2.0"      # unset means no CPU limit

# Optional. Whom the project's tasks may write to. Tasks of one project reach each other by task
# name without being listed, and a project with a conversation has 'people', the people in it,
# without being listed either.
mail:
  peers:
    reviewer:
      # Required. <transport>:<address>; '<transport>:' is the project's conversation there.
      address: "matrix:"
      # Optional: vouched (one of your own machines) or external (default, checked again here).
      trust: external
      # Optional. Messages per day with this peer, each way.
      per_day: 200
      # Optional. How closely a message to this peer is watched, over its class's rule below.
      # mode: prompt
  # Optional. How closely a message is watched, set once for every task of the project: prompt (it
  # waits for a person), allow (it goes once the filter accepts it), deny (it never goes), off
  # (like allow; the filter still checks). A person can still hold a peer's messages.
  # rules:
  #   project: allow   # another task of this project - the default
  #   room: allow      # the project's conversation and the people in it - the default
  #   others: deny     # every other peer - the default
  # Whatever the mode, only what the filter flags waits for a person. A mode outside the four is
  # refused when the file is read, naming the key. The mode is read at every message, so a changed
  # file holds for the next message of every task.
  # Optional, default blocking. 'reporting' has the filter only report what it finds in a task's
  # outgoing message, never refuse it; a person holding it is shown what it would have refused.
  # outgoing_filter: blocking
  # Optional. Each transport's own settings, by scheme. A transport named here gives the project its
  # conversation, through which its tasks reach each other; a peer is needed only for someone else.
  # transports:
  #   matrix: {}

# Credentials every task of this project holds beyond its agent's own: vault entry name and the
# destination it is for. Only NAMES are here; a task gets a token worthless anywhere else.
# Anyone who can start a task here can use them. A run may add more with --credential but not
# remove these. An offline project declares none.
credentials:
  search: brave-search
```

## How the file reaches a machine: following

A machine **follows** a project's repository: `sokar project follow <project> <git-url> --signed-by "<key>"`.
It pulls; nothing pushes to it. One project failing to fetch does not stop the others. `--signed-by` can
be given once for each key you sign with: every one is pinned, and a commit signed by any of them is
accepted. Which projects a
machine follows is decided by a person, one command each, and per account: two people on one machine
follow their own.

- **`follow` is synchronous.** When it returns 0, a task can be started for the project at once. It
  takes a local path or `file://` as well as a forge URL, so a repository on the same machine is
  enough, and a project followed `--unverified` starts tasks like any other.
- **Only what is signed by the key given at the follow is applied**, and a signed commit that does not
  descend from the one in force waits for `project follow ... --accept-rewrite`: a rebase by somebody
  with the key looks the same from here as an older signed file served again to put back a rule that
  was taken away. Say it only when you know why the history moved.
- **A machine that cannot fetch keeps what it verified last**, says it is behind, and stops no task. It
  never falls back to an unverified copy.
- **An agent can propose configuration, never put it in force.** A project's repository holds its
  `project.yml`, and an agent on that repository may edit it, but its commit is not signed by a pinned
  key: it travels through the gate to a person, who signs it or does not.
- **What following changes:** what the project declares about itself, its egress sets, which agents it
  wants, and how closely its messages are watched. **What it never changes:** the vault and anything in
  it; what a person decided (a held peer); running tasks - a commit starts nothing and stops nothing.
- **A task runs against the file the machine verified**, never a `project.yml` somewhere else, and the
  commit it was verified at is kept with the task. A followed project with nothing in force starts no
  task. A task holds the file it started with: a later commit applies to the next task, never to one
  already running.
- **The repository wins for what it covers**: a local edit to a followed file is replaced, and the
  machine says so.

### When a machine does not apply a commit

`sokar project following` lists each followed project with the commit in force and how its last try
ended. A commit that was turned away is named on a line of its own, `refused <commit> signed by
<fingerprint>`, beside the commit still in force: the project goes on running what it had, and what
was rejected is a separate question from what is running. Only a key's fingerprint is shown, never
anything from the vault.

| Outcome | What happened | What to do |
|---|---|---|
| `not_signed` | The commit carries no signature. | Usually a forgotten signature: sign the commit and push again. |
| `unknown_key` | A good signature, by a key this machine was not given. | Compare the fingerprint with the key you meant to pin. Either the key moved, or somebody is putting a project file past the machine. |
| `no_anchor` | No key is pinned for this project. | Follow again with `--signed-by`, or with `--unverified`. |
| `unreadable` | The commit or the repository could not be read. | Read `detail`. |
| `rewritten` | Signed, but not a descendant of the commit in force. | Only if you know somebody with the key rewrote the history: follow again with `--accept-rewrite`. |
| `unreachable` | The repository could not be reached. | Look at the network or the URL. It may answer on the next try by itself. |
| `vault_locked` | This account's vault is shut, so the credential a private repository needs is out of reach. | `sokar vault unlock`. Nothing is wrong with the network or the URL. Reported only where a vault exists. |
| `no_credential` | The vault is open and holds no key for a repository that needs one. | Store the credential the detail names. |
| `unusable` | The commit verifies, and its `project.yml` does not read as a project, or names an egress set this machine does not have. | Fix the file and commit again. |

`unreachable`, `unreadable` and `failed` (anything else, said in the detail) may clear on the next try
by themselves; every other refusal waits for a person, and nothing changes until somebody acts. `sokar
doctor` reports the same outcomes for every followed project that is not up to date, and names the
commit each project is in force at; a machine with no key pinned does not break, it quietly stops
following, so that is named too.

### Following without the key at hand

A follow with no key is refused and names the fingerprint of the key that signed the commit. Compare
it with the fingerprint you were told, and repeat the follow with it: `--signed-by SHA256:...`. The key
is then read out of the commit that was turned away and pinned, but only when its fingerprint is the
one you typed; a commit signed by any other key is refused and nothing is pinned. The anchor still
comes from you: the repository supplies the bytes, you supply which key they must be.

- **`--signed-by` adds to the pinned keys** rather than replacing them, since a machine follows
  several projects and they need not share a key.
- **`--unverified` and `--signed-by` together are refused.** They are two different instructions,
  not a stricter setting.
- **`--unverified` skips the signature check, not the reading.** A file that does not read as a
  project is still refused.
- **Unverified is per project.** It is shown in `project following`, as
  `unverified - whoever can push there decides what tasks here may reach`, and in `sokar doctor`,
  which reports it as degraded rather than failing, since somebody asked for it. A project followed
  without a key changes nothing about what is reported for one followed with a key.

### Changing the key you sign with

The key named at the follow hands on through the file itself. **Commit the new key under
`project.signers` beside the old one, signed with the old key:** every machine following the project
checks it against the old key and pins the new one. **Then commit a list with only the new key, signed
with the new key:** the old one is unpinned. A commit that changes the list counts only when it is
signed by a key in force before it, so a key never vouches for the commit that adds it. Every commit
that changes the list between the one in force and the one fetched is checked in order, along the
first parent, so the hand-on and the commit signed only by the new key may arrive in one fetch. Keys
are pinned only with a commit that is applied, never before. A file that names no `signers` keeps the
keys pinned at the follow. `project follow` says which keys it pinned and unpinned, by fingerprint.

## A key Sokar does not know

**A key that is clearly a mistake is refused.** That is a key under the wrong section, or one within
two letters of a real key. The error says where it belongs:

    project.yml: 'mail.upstream' is not a setting; it belongs under 'project:'

**A key Sokar no longer reads is refused by name**, with what decides instead, so a file that carries it does not look
as if it still did something: `unread_work_may_leave` is gone, and how closely a message is watched is set by
`mail.rules` and a peer's `mode`.

**Any other unknown key is accepted with a warning** from `task start`, so a file written for a newer
Sokar still runs on an older one. Sokar does not check the names under `credentials` and
`repositories`, or anything under `mail.transports.<scheme>` (the transport checks that).

## machine-signers, beside this file

The public keys a project's machines sign messages with live in `machine-signers`, next to
`project.yml`. **Secrets never travel; public keys travel through the project's repository**, which
every machine verifies: each commit must be signed by the pinned key and descend from the one in force,
so a removed signer cannot be restored by replaying an older commit.

- `sokar project enroll PROJECT` puts this machine's key up for review in the project's gate
  (`--remove` takes it back). The review marks a signer list as dangerous.
- `sokar gate approve enroll-<machine> --signed` merges it, signed with the approver's own git signing
  key. The machine never holds that key; without one configured it refuses.
- **The trust anchor is a person's step:** the signing key is given once, out of band, to
  `project follow --signed-by`. It is never read from the repository it verifies.

On each machine the same two kinds of key are kept apart by name, in `~/.config/sokar/`:
`machine-signers` holds the message keys of other machines it accepts messages from, and
`project-signers` the keys pinned with `--signed-by`, whose commits a followed project's configuration
is taken from. A person's key is never a machine's, and the other way round.

In `project-signers` the name before each key is a label, not a check: any key in the file verifies,
whatever name it is written under. The file therefore means *these keys may sign configuration*. Write
the signer's email there all the same; it is what `git log --show-signature` prints.

## Deploy keys

**One deploy key per machine per repository.** The private half stays in the machine's vault; the
public half is registered at the forge as `sokar <machine> <project>/<repository>`. The key for the
project's own repository is **read-only**; only work repositories get write access. Unfollowing a
project forgets its keys here and names each one to remove at the forge.

## What is not in here

- **Agents and providers.** They are chosen per run: `sokar task start --agent claude --provider openrouter`.
- **Secrets.** Everyone who can read the repository reads this file. Credentials live in the vault;
  `credentials:` only names vault entries. See [authentication](credentials.md).
- **Paths.** Where the workspace, state and gate mirror live is Sokar's choice; `sokar doctor` shows it.
