# B14 — Talking Between Tasks, design

How [B14](B14-Talking-Between-Tasks.md) would be built. **Nothing here exists yet**: every class,
path, method, ref and file named below is a proposal, and nothing in it has been measured. Where a
fact about the running system is quoted it comes from reading the code and the documentation; where a
fact about a third-party product is quoted it comes from that product's documentation, read on
2026-09-13, and is marked as such.

**In short:**

- Tasks talk through **a git repository served by the gate**, one branch per group.
- **The check runs where nobody can skip it**: on the forge, if the forge runs pre-receive hooks;
  otherwise — GitHub, for one — on **a self-hosted filter in front of the forge**, which is plain git
  over ssh with Sokar's own binary as the hook. The filter may run on the same machine as the tasks
  when there is only one. Which of the two is used is one address in the gate's configuration.
- **A message is an A2A message narrowed by a strict schema**, refused otherwise.
- **The classifier runs in a container of its own**, with no network and nothing to take.

## Scope of the first version

**One talk repository. One branch per group. Any number of machines of the same operator. A person
watching.**

Out of scope, deliberately: a key inside a task container for any purpose, editing a held message,
and separating groups from machines that share the repository — a branch separates writing, not
reading, so anything that must be kept from the operator's other machines gets its own repository.

## The shape

```
 machine                                   filter (self-hosted)                        forge
 ┌──────────────┐   ┌───────────────────┐  ┌──────────────────────────────────────┐  ┌──────────────┐
 │ task         │   │ gate (host)       │  │ sshd, forced command                 │  │ talk repo    │
 │  talk/ clone │──▶│ token → task      │─▶│ bare repo                            │─▶│ groups/*     │
 │  push, pull  │◀──│ policy, schema    │◀─│ pre-receive: sokar talk check        │  │ written only │
 └──────────────┘   │ own commit        │  │   └─ classifier container, no network│  │ by the filter│
                    │ SSH signature     │  │ post-receive: forward                │  └──────────────┘
                    └───────────────────┘  └──────────────────────────────────────┘
```

On a forge that runs pre-receive hooks the middle box is the forge itself, and nothing is forwarded.
A task never reaches the filter or the forge, and never sees another group.

## The talk repository

**One repository, one protected branch per group, `groups/<name>`.** Linear history, no merges, no
force pushes, no deletions.

**One file per message**, at `messages/<task>/<seq>.json`, where `<task>` is the container name and
`<seq>` is assigned by the gate. Every writer writes into its own directory, so two messages never
conflict; a rejected fast-forward is resolved by rebasing onto the new tip, never by a merge. **The
order of messages is the order of commits on the branch**, not the timestamps inside them.

**`group.yml` at the root of the branch is the group's control state**: its member projects, the
machine signing keys allowed to write for them, its mode, whether it is held or closed, its turn
budget and its text limit. Only a commit signed by an operator key may change it, and the check
refuses any other change to it. The operator keys themselves are configured where the check runs and
in every gate, never in the repository, so the repository cannot vouch for itself.

**Every commit the gate makes carries trailers**:

```
Sokar-Task: sokar-a1b2
Sokar-Project: sokar
Sokar-Author: task
```

`Sokar-Author` is `task` or `operator`. The trailers are the gate's statement; the signature is what
makes the statement checkable by somebody who was not on that machine.

## The message format

**An [A2A](https://a2a-protocol.org/latest/specification/) message, narrowed.** A2A is used as a file
format only, not as a protocol: an agent already speaking A2A needs no adapter later, it costs nothing
at runtime, and it commits to no server. A2A carries its own mechanism for this — a message lists the
URIs of the extensions present in it — so the narrowing is declared as a Sokar extension and every
message stays valid A2A.

What the narrowed schema allows, and nothing else:

| A2A field | Allowed |
|---|---|
| `messageId` | Required. Unique within the group. |
| `role` | `agent` when a task writes, `user` when a person does. Must agree with `Sokar-Author`. |
| `contextId` | Required, and equal to the group's name. |
| `taskId`, `referenceTaskIds` | Absent. |
| `extensions` | Exactly the Sokar talk extension's URI. |
| `metadata` | Exactly one key, `kind`: `question`, `answer`, `review-request`, `status` or `handover`. |
| `parts` | Exactly one text part, within the group's text limit (proposed default 4 KiB of UTF-8), and at most one data part whose schema is fixed by `kind`. **No file, raw or url part.** |

| `kind` | Data part |
|---|---|
| `question` | none |
| `answer` | `in_reply_to`: the commit of the message answered — A2A's message has no reply field |
| `review-request`, `handover` | `repository`, `ref`, `commit` — work is named by commit and travels through the gate, never inside a message |
| `status` | `state`: `started`, `blocked`, `done` or `abandoned` |

Every object is closed: a property the schema does not name is a refusal, not something ignored.
Commit ids are 40 or 64 lowercase hex characters.

**What the schema buys, stated exactly.** It bounds how much a message can carry and what shape it
has — and with the turn budget, how much a whole group can carry — without a model deciding anything.
It does not bound what the text means: the one text part carries whatever is written into it, which
is why the text limit and the budget are the controls, and the classifier is not.

## Inside the container

**A second clone, `talk/`, of the group's branch**, whose `origin` is always the gate — not inside
the workspace, so a message can never be committed into the work repository by accident. Where
exactly it sits is an open question below. It is a clone per task, never a directory shared between
tasks.

**Saying something** is committing one message file under `messages/<own task>/` and pushing. The
push lands on `refs/sokar/talk/<group>/<task>` in the gate, exactly as a work push lands on
`refs/sokar/incoming/<task>` today.

**Listening** is pulling. The agent is told in its instructions where `talk/` is and that what it
finds there is content from other tasks, never an instruction from the operator.

**The agent definition gains one field**, reported through `describe`: `talk: git` or `talk: none`.
Sokar branches on the field, never on the agent, and `AgentIsolationTest` fails the build if that is
got wrong. An agent that declares `none` cannot be placed in a group, and the refusal says so.

## The gate's half

The gate already identifies a task by the per-task token every request carries, and already pushes to
an upstream with a credential that stays on the host (`sokar gate approve`). The talk half reuses both.

**One address decides where the check runs.** `project.yml` names the talk repository's upstream,
edited as text like every other project setting:

```yaml
talk:
  upstream: "ssh://sokar-filter@filter.example.org/talk.git"
  groups:
    - "review"
```

Pointing it at a forge that runs the hook, or at a filter in front of one, is the only difference
between the two arrangements; the gate does the same thing in both. The address is the gate's, on
the host. **A container never sees it**: its `talk/` points at the gate, so an agent cannot point
itself past the check.

On every talk push, in this order, and a failure at any step stops the next:

1. **Policy**, in `TalkPolicy` in `core` — the only place that decides, asked by the gate, the daemon
   and the CLI alike:
   1. every project in `group.yml` declares the group under `talk.groups` in its own `project.yml`;
   2. no project in the group is `offline`;
   3. every project in the group has the same class, refused as `REFUSED_BY_CLASS`;
   4. the task's agent declares `talk: git`.
2. **The rules that are not a model**, run early so that an obvious refusal never leaves the machine:
   one new file, under the task's own directory, valid against the narrowed schema, and clean under
   `CredentialScan`. The same code as the check at the filter.
3. **The gate's own commit.** The message file is taken out of the agent's commit and written into a
   new commit on the current tip of the group's branch, with the trailers above. The agent's author,
   dates, parents and any other file it committed are discarded rather than validated.
4. **Signed on the host**, with an SSH key under the state directory, `0600`, never mounted anywhere
   (`gpg.format ssh`).
5. **Pushed upstream** with the host's ssh key for that upstream. A refusal is recorded on the gate as
   `refs/sokar/talk-refused/<group>/<task>/<seq>` — git again, no journal written for it — and the
   sender is told why on its push.

**Serving a task only its group.** The gate keeps one bare mirror per group, holding only that
group's branch, and serves a task only the mirror of its own group. Not one mirror with the other
refs hidden: git documents that its fetch and push protocols are not designed to stop one side from
obtaining objects the other did not mean to share, and `gitnamespaces(7)` says so in its security
section. A task can only get what is not there to be got.

**Across a resume** nothing needs replaying: the mirror and the branch outlive the task, and the
resumed task's `talk/` is cloned again from the gate.

## The check

**`sokar talk check`, the same native binary wherever it runs**, as the `pre-receive` hook of the
repository the gates push to:

1. **Signature**, verified against the keys `group.yml` allows for that group, and operator keys for
   a change to `group.yml`. `valid-after` and `valid-before` on each key (per `ssh-keygen(1)`) keep
   old commits verifiable after a key is retired.
2. **Shape**: a fast-forward, exactly one new message file in the directory named by `Sokar-Task`, or
   a change to `group.yml` alone.
3. **The narrowed schema**, and `role` against `Sokar-Author`.
4. **State**: the group is not closed, not held, and within its budget — read from `group.yml` at the
   tip.
5. **`CredentialScan`**, the same code the vault proxy runs.
6. **The classifier**, last, in its own container. It can answer *hold*; it cannot turn a refusal into
   an acceptance.

A pre-receive hook can only accept or reject, so **holding is a second push**: a *hold* rejects with
the reason `held`, the gate pushes the same commit to `refs/held/<group>/<id>`, which the check
accepts after steps 1 to 3 only, and a person releasing it makes a new commit carrying the message and
`Sokar-Released-By` and `Sokar-Held: <commit>` trailers, signed with the person's key. The held commit
stays where it was, so the record shows both what the task said and who let it through.

## Where the check runs

### On a forge that runs pre-receive hooks

The check is installed as the hook of the talk repository, and the forge is the only writer to its own
branches. Per each product's documentation as read on 2026-09-13: on GitHub, pre-receive hooks exist
only in GitHub Enterprise Server; on GitLab, server hooks exist only on self-managed instances; Forgejo
runs hooks an administrator places on the filesystem, and a maintainer-approved proposal from December
2024 would remove its editor for them without removing that. The forge's host needs podman for the
classifier.

### On a self-hosted filter in front of a forge that does not

**Plain git over ssh**, set up by `sokar talk filter init`:

- **A dedicated system user**, `sokar-filter`, owning a bare repository, the hooks, the operator keys
  and the forge credential. On a single machine it still runs as its own user rather than as the
  operator's, so the forge credential is not readable by the account tasks run under.
- **sshd with a forced command per machine** in that user's `authorized_keys` — `restrict` and a
  `command=` that runs the `sokar` binary, which allows only `git-receive-pack` and `git-upload-pack`
  on the talk repository and nothing else. No shell. This transport key is not the machine's signing
  key: one lets a machine connect, the other says what it vouches for.
- **`pre-receive` is `sokar talk check`.**
- **`post-receive` forwards** what was accepted to the forge. A hook after the fact cannot fail the
  push, so a forge that cannot be reached leaves the message accepted in the filter and forwarded on
  the next attempt; gates fetch from the filter, so no reader waits on the forge.
- **The filter is the only writer the forge accepts.** On GitHub that is a branch ruleset on
  `groups/*` restricting updates to the filter's account, blocking force pushes and restricting
  deletions — rules GitHub runs itself, per its documentation as read on 2026-09-13. A machine with a
  credential of its own for the forge would otherwise go around the filter. As the only writer, the
  filter also takes pushes one at a time, so the branch stays linear without races.
- **It binds a network port when other machines push to it.** That is sshd on the filter's host, not
  the daemon: `sokard` still binds no network interface in any configuration.
- **On one machine** it is the same setup on `localhost`. A compromised host can skip a check that runs
  on it; that is the limit B13 ([index](README.md)) already names for the owner of a machine.

**It is not an agent and not a task.** Nothing in it acts on the text it reads: sshd accepts a push,
git runs a binary, the binary exits with accept, refuse or hold. It is not run as a Sokar task either —
a task is the side being checked, cannot accept a connection, and must never hold a forge credential.

## The classifier, and its container

**Meta's [Llama Prompt Guard 2](https://huggingface.co/meta-llama/Llama-Prompt-Guard-2-86M)**, per its
model card: a classifier for prompt injection and jailbreak attempts, in an 86M multilingual and a 22M
variant, with a 512-token window — a longer text part is split and each piece classified. It produces
a score, not text: it writes nothing, uses no tool and follows no instruction, so a message can at most
move the score. It flags text that tries to override instructions; it does not recognise a workspace
leaving in base64, and a model that means to get past it can paraphrase. That is why it can only hold.

**It runs in a container anyway**, because a tokenizer and a model runtime are native code parsing
text an attacker wrote, and because a generative reviewer put in its place later would be an agent.
The check is split by what each part holds:

| Part | Holds | Runs as |
|---|---|---|
| `pre-receive`: signature, shape, schema, state, `CredentialScan` | the operator keys, no secret | the filter user |
| **The classifier** | the model file, read-only | **a rootless container per push**: `--network none`, read-only root filesystem, `--cap-drop all`, `no-new-privileges`, memory, CPU and pid limits, no mount but the model, the text on standard input, the score on standard output, the image pinned by digest |
| `post-receive`: forwarding | the forge credential | the filter user, never in the same process as the classifier |

- **The hook reads one number.** A score in bounds is compared against the threshold; anything else —
  no output, more output, a value out of range — is *hold*.
- **Fail closed.** A container that does not start, times out or dies means *hold*, never *accept*.
- **Per push, to start with.** A fresh container costs a few hundred milliseconds and carries nothing
  from one message to the next. A long-running container behind a unix socket is the fallback if that
  latency matters, restarted regularly.
- **A generative reviewer, if ever**, gets the same container, no tools, a fixed prompt, and an answer
  parsed strictly from a fixed set of words.

Running Prompt Guard from Java through ONNX Runtime inside that container is unmeasured, and its
licence terms are unread.

## Modes, holding, closing, budget

**Modes are the four `clearance` already uses**, set in `group.yml`:

| Mode | Effect at the check |
|---|---|
| `prompt` | Every message is held until a person releases it. |
| `allow` | Accepted if every rule passes; the classifier may still hold. |
| `deny` | Every message is refused. |
| `off` | Accepted if the rules that are not a model pass; the classifier does not run. Its own state, readable back, not the widest setting. |

**A `guarded` project's groups are `prompt` unless the project opts in.** The class promises that a
person reads what leaves, and `allow` or `off` lets a message leave that nobody read. The opt-in is
one setting, proposed as `gate.unreviewed_may_leave: true` in `project.yml`, shared with B13's review
branch on a forge; `TalkPolicy` refuses a `group.yml` mode of `allow` or `off` for a group with a
`guarded` member that has not set it. An `online` project already lets unreviewed work leave, and its
groups may use any mode.

**A group is `open`, `held` or `closed`**, also in `group.yml`. Held accepts nothing onto the branch
and tells each sender it was held. Closed is final: a further push is refused rather than queued.

**The turn budget** is a count in `group.yml`; the check counts message commits since the commit that
set it and refuses the one that would exceed it with `OVER_BUDGET`, and the group is then closed by an
operator-signed commit that says why.

**Fail closed.** If the upstream cannot be reached, nothing is delivered and the sender is told; if the
check cannot run, the push is refused.

## The record, and verifying it

**The branch is the record.** Every commit hashes its parent, so an entry changed or removed after the
fact stops verifying; the check refuses the force push that would be needed to hide it. What was
refused on a machine is on that machine's gate under `refs/sokar/talk-refused/`, and what was held is
under `refs/held/` where the check runs.

**`sokar talk verify <group>`** walks the branch, verifies every commit's signature against the keys
`group.yml` allowed at that commit, checks every `group.yml` change against the operator keys, checks
the history is linear, and names the first commit that fails.

## The daemon's interface

Additions to `org.fuin.sokar.Tasks1`, which only ever grows. `InterfaceDescriptionTest` fails the
build if any of this is registered without appearing in the file, or appears without being
registered.

```
type Group (
  name: string,
  projects: []string,
  # prompt, allow, deny or off.
  mode: string,
  # open, held or closed.
  state: string,
  turns: int,
  budget: int
)

type Message (
  group: string,
  # The commit on the group's branch. Pass it back unchanged.
  commit: string,
  # Container name of the writing task, or "" when the author is the person.
  task: string,
  project: string,
  # "task" or "operator".
  author: string,
  # question, answer, review-request, status or handover.
  kind: string,
  at: string,
  text: string,
  # held, delivered or refused.
  state: string
)

type TalkOutcome (
  ACCEPTED, HELD, NOT_DECLARED, REFUSED_BY_CLASS, NO_TALK, REFUSED_BY_RULE, CLOSED, OVER_BUDGET,
  UPSTREAM_UNREACHABLE
)

# Every group the projects on this machine belong to.
method Groups() -> (groups: []Group)

# Every message in every group, as the gate fetches it - including groups joined after the call.
# Streaming only, exactly as Prompts works.
method Talk() -> Message

# The person writes into a group, signed with their key. The author is set here, never by the caller.
method Say(group: string, kind: string, text: string) -> (message: ?Message, outcome: TalkOutcome)

# Releases or refuses one held message.
method Release(group: string, commit: string, allow: ?bool) -> (message: Message)

# Changes the mode, or holds or releases the whole group: an operator-signed commit to group.yml.
method Moderate(group: string, held: ?bool, mode: ?string) -> (group: Group)

# Ends it. A further message is refused rather than queued.
method Close(group: string, reason: ?string) -> (group: Group)

error NoSuchGroup(group: string)
```

## The command line

`sokar talk` mirrors `sokar shield`: the same objects, the same refusals, rendered rather than
re-decided.

```
sokar talk groups
sokar talk log <group> [--follow]
sokar talk say <group> [--kind question]   # text on stdin, never in argv
sokar talk held <group>
sokar talk release <group> <commit> [--refuse]
sokar talk hold <group> [--release]
sokar talk close <group> [--reason ...]
sokar talk verify <group>
sokar talk filter init ...                 # sets up a filter; prints the ssh line for each machine
sokar talk check ...                       # the hook, not for people
```

**`say` reads the text from standard input**, the way `vault put` does: a process list is
world-readable and nobody can promise what an operator will paste into a message to an agent.

## Lifecycle

| Event | What happens |
|---|---|
| `task start` | If the project declares a group, the gate's mirror for it is fetched and the task's `talk/` is cloned from the gate. No talk helper, no extra socket. |
| A message | Gate policy, gate rules, gate commit, host signature, check upstream, branch. A failure at any step stops the next. |
| Another machine fetches | Its gate updates its group mirror and feeds the daemon's `Talk` stream; its tasks see the message on their next pull. |
| `task stop` | Nothing to reap. The branch keeps everything the task said. |
| `task resume` | `talk/` is cloned again from the gate; nothing is replayed. |
| `sokar panic` | Stops the tasks with everything else; the gate pushes nothing more; the record stays. |
| A machine key is retired | Its `valid-before` is set in `group.yml` by an operator-signed commit; what it signed before stays verifiable. |
| The forge is unreachable from the filter | Accepted messages stay in the filter and are forwarded later; readers are unaffected. |

## Failure modes, and what each must do

| When | What must happen | Why it is listed |
|---|---|---|
| The upstream cannot be reached | Nothing is delivered; the sender is told on its push. | A message waiting silently reads as ignored. |
| The check cannot run | The push is refused. | Fail closed. |
| The classifier's container fails, times out or answers out of bounds | The message is held. | A classifier that cannot answer must not become one that says yes. |
| An agent commits a file outside its own directory | The gate refuses before signing. | Otherwise one task could write in another's name. |
| An agent commits more than the message | Only the message file reaches the gate's commit. | The agent's commit is an assertion, not a record. |
| A message carries a file part, an unknown property or an unknown kind | Refused by the gate, and again by the check. | A closed schema that ignores what it does not know is an open one. |
| The group is held or closed | Refused with that state as the reason. | An agent told nothing retries. |
| The budget runs out | Refused, and the group is closed with a reason. | A warning nobody is watching is not a control. |
| A task tries to fetch another group | There is nothing to fetch: its gate mirror holds one branch. | Hidden refs are not access control. |
| Something other than the filter writes to the forge | The forge refuses it. | Otherwise the filter is a suggestion. |

## What must be proven to fail

- A group not declared by every member project **must** be refused — assert that no commit reaches
  the branch, not on the wording of the refusal.
- An `offline` member, and a `guarded`-with-`online` group, **must** be refused. Mutate the class in
  the fixture and watch each break.
- A `group.yml` mode of `allow` or `off` for a group with a `guarded` member that has not opted in
  **must** be refused, and accepted once it has. Remove the setting in the fixture and watch it break.
- An unsigned commit, a commit signed by a key not in the group, and a `group.yml` change signed by a
  machine key **must** each be refused by the check.
- A commit whose `Sokar-Task` names another task's directory **must** be refused.
- A file part, an extra property at any depth, an unknown `kind`, a `role` that disagrees with
  `Sokar-Author`, and a text part one byte over the limit **must** each be refused — by the gate, and
  by the check with the gate's step bypassed in the fixture.
- `sokar talk verify` **must** fail when a commit is edited and when one is removed — both, because
  removal is the case a naive per-commit check misses.
- A task's `talk/` **must** contain no object of another group: clone it and search for a known blob
  of the other group, rather than listing refs.
- A held group **must** deliver nothing: assert a peer's pull brings nothing new, not that the state
  field says `held`.
- A classifier container that exits without output, prints two numbers, or sleeps past the timeout
  **must** each produce *hold*, and none of them *accept*.
- The classifier container **must** fail to open a network connection and to write outside its
  standard output. Assert on the attempt from inside, not on the flags passed.
- A push to the forge with a credential other than the filter's **must** be refused.
- No key file appears anywhere a task container or the classifier container can read.

Anything needing podman, sshd or a forge belongs in the acceptance suite, not in surefire.

## Alternatives considered

| Option | Why not |
|---|---|
| **A socket helper**: a `talk serve` helper per task with a socket mounted at `/run/sokar/talk.sock`, a `Talk1` varlink interface, a hash-chained journal under the state directory, and the daemon as hub | Sound on one machine and widened nothing. It had to invent what git and the gate already provide — a wire, an inbox in agents that have none (MCP over a unix socket from a rootless container, never measured), a journal and its verifier — and across machines it needed a client carrying frames between daemons, with two records and no shared clock. |
| **A model reviewer on every machine** | A check on the sending machine is skipped by that machine when it is compromised, protects nobody from what arrives, reads every project's messages and so becomes a bridge between them, and a hosted free model is itself a place every conversation leaks to. |
| **GitHub's rules with a required status check** instead of a filter | No custom code runs in GitHub's push path, so the check becomes a workflow on a pending branch followed by a fast-forward: a workflow run and tens of seconds per message, a rebase, a new signature and a new check whenever two messages race, and signature verification tied to one GitHub account per machine. The filter keeps GitHub's rules for what they do well — only the filter writes, nothing is force-pushed or deleted. |
| **[FINOS GitProxy](https://github.com/finos/git-proxy)** (Apache-2.0, FINOS graduated) | Almost exactly a filter: it intercepts a push, runs a chain of processors that can reject it, holds it for approval and forwards it, over HTTPS and SSH. It is TypeScript on Node with MongoDB or NeDB, and brings its own UI and user model — a second approval flow beside Sokar's, and the check called from a plugin. Worth taking if people outside Sokar have to approve messages in a browser. |
| **[Gerrit](https://gerrit-review.googlesource.com/Documentation/config-validation.html)** | Java, and a `CommitValidationListener` plugin rejects a push synchronously, with replication forwarding to GitHub. It is a whole code-review server with its own users and UI to run for a message filter. Worth taking if messages ever need real review with several reviewers. |
| **[Llama Guard](https://huggingface.co/meta-llama/Llama-Guard-4-12B)** | Classifies content against a list of harms — violence, weapons, self-harm. Neither exfiltration nor prompt injection is on it; a workspace in base64 is harmless by its categories. |
| **[NeMo Guardrails](https://github.com/NVIDIA/NeMo-Guardrails)** | A Python orchestration framework with its own rule language, not a detector: the detection comes from what it calls, which can be called directly. It would bring back the Python this project is removing from its build. |
| AI security proxies such as [AegisGate](https://github.com/ax128/AegisGate) | The same kind of check, placed in front of a model's API rather than in front of git. |
| [NATS](https://nats.io/about/) + JetStream, [Matrix](https://spec.matrix.org/latest/) via [continuwuity](https://continuwuity.org/introduction), [Prosody](https://prosody.im/) | Brokers and chat servers take transport and storage off the pile, and leave the policy, the hold and a verifiable record to be built on top — beside a daemon with its own authentication database. |
| [Rekor](https://github.com/sigstore/rekor), [immudb](https://immudb.io/) | A transparency log proves more than a signed linear branch, and runs as another service. Worth revisiting if the record has to satisfy somebody who trusts neither the operator nor the forge. |

## Open questions this design leaves

- **Whether GitHub may store the conversations at all.** Behind the filter it still holds every one of
  them in plaintext; a filter with no forward is a valid configuration.
- **Where `talk/` sits in the container**, so that it is outside the workspace, survives what the
  agent does to its working directory, and is not mistaken for part of the project.
- **Whether a task may be in two groups.** It can carry what it read in one into the other.
- **Membership changes are one-way.** A new member reads the whole history; a removed member keeps
  what it fetched. Excluding somebody from the past means a new branch.
- **Whether dialogue at fetch cadence is enough.** It is for handing work over and asking questions;
  it is poor for fast back-and-forth, which was the one thing that justified a channel besides the
  gate.
- **Whether the five kinds are the right five.** They are a proposal; a kind added later is a schema
  change every check and every gate must learn at once.
- **A2A's part fields.** The specification page, which showed version 1.0.0 on 2026-09-13, was read
  for the message's fields; the exact field names of a part were not, and the table above must be
  checked against them.
- **The classifier's licence, whether it runs from Java, and its threshold.** Unread, unmeasured, and
  undecided.
