# B14 — Talking Between Tasks, design

How [B14](B14-Talking-Between-Tasks.md) would be built. **Nothing here exists yet**: every class,
path, method, ref and file named below is a proposal, and nothing in it has been measured. Where a
fact about the running system is quoted it comes from reading the code and the documentation; where a
fact about a third-party product is quoted it comes from that product's documentation, read on
2026-09-13, and is marked as such.

**In short:**

- Tasks talk through **a git repository, one branch per group**, served to each task by its gate.
- **Every check runs on the machine that writes the message**, before the push. The repository that
  distributes runs nothing: it stores branches and hands them out.
- **What a message contains is checked by a tool of its own**, the message sluice — the narrowed
  schema, encoded payloads, credentials, personal data, and a payload spread over several messages.
  It is deterministic and carries no model.
- **The distributing repository is a bare repository on the same machine** when every agent is on one
  machine, and a remote — a hosted forge included — when they are not. One address, two arrangements.
- **A message arrives by polling**: one timer per machine, a minute by default.

## Scope of the first version

**One talk repository. One branch per group. One group per task. Any number of machines of the same
operator. A person watching.**

Out of scope, deliberately: a key inside a task container for any purpose, editing a held message,
a classifier or any other model in the deciding path, checking on the way in what the sending
machine already checked, and separating groups from machines that share the repository — a branch
separates writing, not reading, so anything that must be kept from the operator's other machines
gets its own repository.

## The shape

```
 machine A                                            distributing repository
 ┌────────────────────────────────────────────┐       ┌───────────────────────┐
 │ task            gate (host)                │       │ bare repo, no hooks   │
 │  talk/ clone ──▶ token → task              │       │ groups/<name>         │
 │  push, pull  ◀── policy                    │──push▶│                       │
 │                  message sluice  ─ refuse ─┤       │ local path when every │
 │                  own commit, SSH signature │       │ agent is on one       │
 │                  watch, or timer every 60s │◀fetch─│ machine; ssh remote   │
 │                                            │       │ or forge when not     │
 └────────────────────────────────────────────┘       └───────────────────────┘
                                                              ▲   │
                                                        push  │   │  fetch
                                                              │   ▼
                                                        machine B, the same way
```

Nothing but a gate ever reaches the distributing repository, and a task never reaches it at all.
Where every agent is on one machine, that repository is a bare repo on the same disk and no packet
leaves the host.

## The talk repository

**One repository, one branch per group, `groups/<name>`.** Linear history, no merges, no force
pushes, no deletions. A gate never force-pushes and never deletes; where the remote can refuse both
itself — a forge's branch rules, which it runs itself — that is configured as well, because a rule
kept only by the clients is kept only by the clients.

**One file per message**, at `messages/<task>/<seq>.json`, where `<task>` is the container name and
`<seq>` is assigned by the gate. Every writer writes into its own directory, so two messages never
conflict; a rejected fast-forward is resolved by rebasing onto the new tip, never by a merge. **The
order of messages is the order of commits on the branch**, not the timestamps inside them.

**`group.yml` at the root of the branch is the group's control state**: its member projects, the
machine signing keys allowed to write for them, its mode, whether it is held or closed, its turn
budget and its text limit. Only a commit signed by an operator key may change it. The operator keys
themselves are configured in every gate, never in the repository, so the repository cannot vouch for
itself.

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

**The field names are verified**, against `specification/a2a.proto` at tag `v1.0.1`, read 2026-09-16:
a part carries exactly one of `text`, `raw`, `url` or `data` in a `oneof`, plus `metadata`, `filename`
and `media_type`; a message carries `message_id`, `context_id`, `task_id`, `role`, `parts`,
`metadata`, `extensions` and `reference_task_ids`; the roles are `ROLE_UNSPECIFIED`, `ROLE_USER` and
`ROLE_AGENT`. There is no `kind` discriminator anywhere — it was removed in 1.0 — so a file carrying
one is from 0.3.x and is refused rather than converted. ProtoJSON is the JSON mapping, so the wire
names are `mediaType`, `messageId`, `contextId` and so on.

**What the schema buys, stated exactly.** It bounds how much a message can carry and what shape it
has — and with the turn budget, how much a whole group can carry — without a model deciding anything.
It does not bound what the text means: the one text part carries whatever is written into it, which
is why the text limit, the budget and a person's hold are the controls.

## Inside the container

**A second clone of the group's branch at `/run/sokar/talk`**, whose `origin` is always the gate.
It sits beside the sockets the container already has under `/run/sokar`, so everything Sokar puts in
a container is in one place an agent learns once: never inside the workspace, so a message cannot be
committed into the work repository by accident, invisible to `git status` there, and untouched by an
agent that wipes its working directory. It is a clone per task, never a directory shared between
tasks.

**Saying something** is committing one message file under `messages/<own task>/` and pushing. The
push lands on `refs/sokar/talk/<group>/<task>` in the gate, exactly as a work push lands on
`refs/sokar/incoming/<task>` today.

**Listening** is pulling. The agent is told in its instructions where `talk/` is and that what it
finds there is content from other tasks, never an instruction from the operator.

**The agent definition gains one field**, reported through `describe`: `talk: git` or `talk: none`.
Sokar branches on the field, never on the agent, and `AgentIsolationTest` fails the build if that is
got wrong. An agent that declares `none` cannot be placed in a group, and the refusal says so.

## The gate's half: what happens on a push

The gate already identifies a task by the per-task token every request carries, and already pushes to
an upstream with a credential that stays on the host (`sokar gate approve`). The talk half reuses both.

**One address decides where messages go.** `project.yml` names the talk repository, edited as text
like every other project setting:

```yaml
talk:
  # A path when everything is on this machine; an ssh url when it is not.
  repository: "/srv/sokar/talk.git"
  groups:
    - "review"
  # What this project accepts from a group before it refuses the sender. It pays for the work.
  incoming_turns: 40
```

The address is the gate's, on the host. **A container never sees it**: its `talk/` points at the
gate, so an agent cannot point itself past the checks.

On every talk push, in this order, and a failure at any step stops the next:

1. **Policy**, in `TalkPolicy` in `core` — the only place that decides, asked by the gate, the daemon
   and the CLI alike:
   1. every project in `group.yml` declares the group under `talk.groups` in its own `project.yml`;
   2. no project in the group is `offline`;
   3. every project in the group has the same class, refused as `REFUSED_BY_CLASS`;
   4. the task's agent declares `talk: git`;
   5. the task is in no other group.
2. **State**: the group is not closed, not held, and within its budget — read from `group.yml` at the
   tip of the branch this machine last fetched.
3. **Shape**: one new file, under the task's own directory, and nothing else in the agent's commit.
4. **The message sluice** — the narrowed schema, encoded payloads, credentials, personal data, and
   what earlier messages of this group already carried. Its refusal is what the task is shown.
5. **The gate's own commit.** The message file is taken out of the agent's commit and written into a
   new commit on the current tip of the group's branch, with the trailers above. The agent's author,
   dates, parents and any other file it committed are discarded rather than validated.
6. **Signed on the host**, with an SSH key under the state directory, `0600`, never mounted anywhere
   (`gpg.format ssh`).
7. **Pushed to the distributing repository** with the host's credential for it. A refusal is recorded
   on the gate as `refs/sokar/talk-refused/<group>/<task>/<seq>` — git again, no journal written for
   it — and the sender is told why on its push.

**Serving a task only its group.** The gate keeps one bare mirror per group, holding only that
group's branch, and serves a task only the mirror of its own group. Not one mirror with the other
refs hidden: git documents that its fetch and push protocols are not designed to stop one side from
obtaining objects the other did not mean to share, and `gitnamespaces(7)` says so in its security
section. A task can only get what is not there to be got.

**Across a resume** nothing needs replaying: the mirror and the branch outlive the task, and the
resumed task's `talk/` is cloned again from the gate.

## The content check is its own tool

**`sokar-message-sluice`, in a repository of its own**, where its requirement and its design are
issue 001. Sokar decides *who* may say something to *whom*; the sluice decides *what* a message may
contain.

**It is not an agent and carries no model.** Fixed rules over files, the same verdict every time for
the same input, and every refusal traceable to one rule with a part index and an offset. A checker
that asked a model would be open to the same manipulation as the task it checks, through exactly the
text it is reading.

What it decides, beyond the narrowed schema: **encoded payloads** (base64 and its relatives, hex,
quoted-printable, PEM, ciphertext) caught by asking whether the text obeys the statistics of English
rather than by a list of encodings; **credentials and personal data** by a catalogue of pattern,
surrounding context and checksum; and **a payload spread over several messages**, by correlating
against everything this machine already let through for that group.

**Its file contract, and how the gate drives it:**

| The sluice's directory | What it is here |
|---|---|
| `inbox/` | where the gate writes the message it took out of the task's push, before it signs anything |
| `approved/` | **the gate's clone of the group's branch.** The gate commits, signs and pushes what lands there — the push is the delivery, and nothing is ever removed, so it is also the corpus the chunking check reads |
| `feedback/` | the answer: a receipt, or a refusal naming every reason with nothing a rule matched in clear text. The gate returns it to the task on its push |
| `rejected/`, `error/` | the originals, which hold the secret in clear text. They stay on the host, are never pushed, and **must lie outside the clone's work tree** — the sluice refuses to start otherwise |

**It runs on the writing machine only**, and its corpus is **the group's whole branch**: the
`approved/` directory *is* the gate's clone, so after a poll it holds every member's messages, not
only this machine's. A payload split across machines is then refused by whichever machine sends the
piece that crosses the threshold. Two gaps stay, and are not closed by anything here: a sender can
beat the corpus by less than one fetch interval, and a piece refused or held on another machine was
never on the branch to count.

**What it does not catch**, stated here rather than discovered later: a secret described in words
instead of written out, steganography in word choice, and anything that is not English prose. No
model would catch them reliably either — which is why a group's mode, its text limit and its turn
budget are the controls that bound what a conversation can carry.

## No classifier in the first version

A prompt-injection classifier — a small model scoring the text before the push — was designed in and
is **not built**. It could only ever hold a message, never release one, so nothing it would catch is
left unguarded by leaving it out: the sluice's rules decide, the group's mode, text limit and turn
budget bound what a conversation can carry, and a person holds what a `guarded` project sends.

What it would have cost is the reason: a container per push, a model runtime parsing attacker text,
an unread licence, an unmeasured threshold and an unproven path from Java — four unknowns in the
critical path of a feature that works without them.

**If it returns**, it returns as its own requirement, with the same shape this design gave it: a
rootless container per push, `--network none`, read-only root filesystem, `--cap-drop all`,
`no-new-privileges`, resource limits, no mount but the model, the text on standard input and one
number on standard output — and *hold* for anything that is not a score in bounds, including a
container that does not start, times out or dies. A generative reviewer in its place would be an
agent, and would need the same container and a stricter answer format.

## Polling, and what a fetching gate decides

**The daemon binds no network interface**, in this arrangement as in every other, so nothing can be
pushed to it. A message arrives because the gate goes and looks.

- **One timer per machine**, `talk.poll_interval` in the daemon's configuration, default 60 s. One
  fetch updates every group mirror on the machine, whether or not a task is running, so a message is
  waiting when a task starts rather than arriving only because one did.
- **On one machine there is no interval.** Where the distributing repository is a path on this host —
  the default arrangement — the gate watches its refs and updates the mirrors as a push lands, so a
  reply costs what git costs rather than a tick. The timer stays as the fallback for a remote, which
  cannot be watched over ssh.
- **A fetch never runs the content checks.** The machine that wrote a message checked it; running the
  catalogue again here would double the cost and still not protect against a machine that skipped it.
- **What it does decide is whether a message can be attributed**: the signature verifies against a key
  `group.yml` allows for that group at that commit, the trailers agree with the signature, and the
  file is a valid narrowed message. A message failing any of those is **not delivered to any task**,
  is recorded as `UNATTRIBUTED` with the reason, and shows up in `sokar talk` and in the stream for
  the operator. It stays on the branch: not delivering is not hiding.
- **And whether it was allowed to exist.** The gate reads `group.yml` as it stood at the commit each
  new message builds on and asks whether the group was open, not held and within budget then. One
  that should not have been written is not delivered either, and is reported as `RULE_BROKEN` naming
  the machine whose key signed it. Prevention stays with the writing machine; this is how the others
  find out without waiting for somebody to run `verify`.
- **Delivery to a task is its own pull.** The task's `talk/` fetches from the gate's mirror, so a
  task sees what the gate accepted and nothing else.

## Modes, holding, closing, budget

**Modes are the four `clearance` already uses**, set in `group.yml`:

| Mode | Effect on the writing machine |
|---|---|
| `prompt` | Every message is held until a person releases it. |
| `allow` | Pushed if every rule passes. |
| `deny` | Every message is refused. |
| `off` | Pushed if every rule passes, **and** no model check ever runs for this group — including one added later. Today it behaves exactly as `allow`; it is kept because it is a state a group declares about itself, readable back, and not the widest setting. |

**Each machine enforces this for its own tasks.** There is no hook upstream, so the control state is
a rule the operator's machines keep, not a wall — the same limit B13 ([index](README.md)) already
names for the owner of a machine. What a machine cannot do is hide having broken it: the commits are
signed, the branch is the record, and **every other gate checks the state a message was written under
as it fetches**, so a message from a held or closed group reaches no reader anywhere and the operator
is told which machine wrote it.

**A held message is released or refused, never edited.** A release is a new commit by the person,
carrying the message unchanged with `Sokar-Released-By` and `Sokar-Held: <commit>` trailers, signed
with the person's key; the held commit stays on `refs/sokar/held/<group>/<id>` on that machine. So the
record shows both what the task said and who let it through, and never a sentence no task wrote.

**A `guarded` project's groups are `prompt` unless the project opts in.** The class promises that a
person reads what leaves, and `allow` or `off` lets a message leave that nobody read. The opt-in is
one setting, proposed as `gate.unreviewed_may_leave: true` in `project.yml`, shared with B13's review
branch on a forge; `TalkPolicy` refuses a `group.yml` mode of `allow` or `off` for a group with a
`guarded` member that has not set it. An `online` project already lets unreviewed work leave, and its
groups may use any mode.

**A group is `open`, `held` or `closed`**, also in `group.yml`. Held accepts nothing onto the branch
and tells each sender it was held. Closed is final: a further push is refused rather than queued.

**Two budgets, because they protect different people.** The group's turn budget is a count in
`group.yml`; the check counts message commits since the commit that set it and refuses the one that
would exceed it with `OVER_BUDGET`, and the group is then closed by an operator-signed commit that
says why. The receiver's `incoming_turns` is the other: a project declares what it accepts from a
group, and beyond it a message is refused to its sender with `RECEIVER_FULL` rather than queued —
the work a message causes is paid for by the receiving project's provider and credential.

## The record, and verifying it

**The branch is the record.** Every commit hashes its parent, so an entry changed or removed after the
fact stops verifying, and a force push that would hide it is refused by every gate and, where it can
be, by the remote. What was refused on a machine is on that machine's gate under
`refs/sokar/talk-refused/`, and what is held is under `refs/sokar/held/`.

**`sokar talk verify <group>`** walks the branch, verifies every commit's signature against the keys
`group.yml` allowed at that commit, checks every `group.yml` change against the operator keys, checks
the history is linear, and names the first commit that fails. It is also how a machine that ignored
the group's state is found, after the fact.

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
  budget: int,
  # When this machine last fetched the branch.
  fetched: string
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
  # held, delivered, refused or unattributed.
  state: string
)

type TalkOutcome (
  ACCEPTED, HELD, NOT_DECLARED, REFUSED_BY_CLASS, NO_TALK, REFUSED_BY_RULE, CLOSED, OVER_BUDGET,
  RECEIVER_FULL, ALREADY_IN_A_GROUP, UNATTRIBUTED, RULE_BROKEN, UPSTREAM_UNREACHABLE
)

# Every group the projects on this machine belong to.
method Groups() -> (groups: []Group)

# Every message in every group, as the gate fetches it - including groups joined after the call,
# and including what was not delivered, so nothing is lost by refusing to deliver it.
# Streaming only, exactly as Prompts works.
method Talk() -> Message

# The person writes into a group, signed with their key. The author is set here, never by the caller.
method Say(group: string, kind: string, text: string) -> (message: ?Message, outcome: TalkOutcome)

# Releases or refuses one held message. It is never edited.
method Release(group: string, commit: string, allow: ?bool) -> (message: Message)

# Changes the mode, or holds or releases the whole group: an operator-signed commit to group.yml.
method Moderate(group: string, held: ?bool, mode: ?string) -> (group: Group)

# Fetches now instead of waiting for the timer.
method Fetch(group: ?string) -> (groups: []Group)

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
sokar talk fetch [<group>]                 # now, rather than at the next tick
sokar talk close <group> [--reason ...]
sokar talk verify <group>
```

**`say` reads the text from standard input**, the way `vault put` does: a process list is
world-readable and nobody can promise what an operator will paste into a message to an agent.

## Lifecycle

| Event | What happens |
|---|---|
| `task start` | If the project declares a group, the gate's mirror for it is fetched and the task's `talk/` is cloned from the gate. No talk helper, no extra socket. |
| A message | Policy, state, shape, sluice, the gate's commit, the host signature, the push. A failure at any step stops the next. |
| Every tick | The gate fetches every group branch, verifies what is new, and feeds the daemon's `Talk` stream. Tasks see it on their next pull. |
| `task stop` | Nothing to reap. The branch keeps everything the task said. |
| `task resume` | `talk/` is cloned again from the gate; nothing is replayed. |
| `sokar panic` | Stops the tasks with everything else; the gate pushes nothing more; the record stays. |
| A machine key is retired | Its `valid-before` is set in `group.yml` by an operator-signed commit; what it signed before stays verifiable. |
| The distributing repository is unreachable | Nothing is delivered and nothing is fetched; the sender is told on its push, and the next tick tries again. |

## Failure modes, and what each must do

| When | What must happen | Why it is listed |
|---|---|---|
| The distributing repository cannot be reached | Nothing is delivered; the sender is told on its push. | A message waiting silently reads as ignored. |
| The sluice cannot start or cannot run | The push is refused. | Fail closed: an unchecked message is the thing this exists to prevent. |
| An agent commits a file outside its own directory | The gate refuses before signing. | Otherwise one task could write in another's name. |
| An agent commits more than the message | Only the message file reaches the gate's commit. | The agent's commit is an assertion, not a record. |
| A message carries a file part, an unknown property or an unknown kind | Refused by the gate. | A closed schema that ignores what it does not know is an open one. |
| A fetched commit is unsigned, signed by a key not in the group, or malformed | Not delivered to any task, recorded as `UNATTRIBUTED`, shown to the operator. | A task must not act on something nobody can attribute. |
| The group is held or closed | Refused with that state as the reason. | An agent told nothing retries. |
| Either budget runs out | Refused, and the group's budget closes the group with a reason. | A warning nobody is watching is not a control. |
| A task tries to fetch another group | There is nothing to fetch: its gate mirror holds one branch. | Hidden refs are not access control. |
| A machine ignores the group's state | Its messages are not delivered by any other gate, and the operator is told which key signed them. Prevention was never possible off that machine. | Detection that waits for somebody to look is not detection. |

## What must be proven to fail

- A group not declared by every member project **must** be refused — assert that no commit reaches
  the branch, not on the wording of the refusal.
- An `offline` member, and a `guarded`-with-`online` group, **must** be refused. Mutate the class in
  the fixture and watch each break.
- A task already in one group **must** be refused a second.
- A `group.yml` mode of `allow` or `off` for a group with a `guarded` member that has not opted in
  **must** be refused, and accepted once it has. Remove the setting in the fixture and watch it break.
- A message the sluice refuses **must not** reach the branch, and the refusal the task is shown
  **must not** contain what the rule matched.
- A sluice that cannot be started **must** stop the push, not pass it.
- An unsigned commit, a commit signed by a key not in the group, and a `group.yml` change signed by a
  machine key **must** each be refused — on the way out by the gate that would write it, and on the
  way in by every gate that fetches it, which is what `UNATTRIBUTED` is for.
- A commit whose `Sokar-Task` names another task's directory **must** be refused.
- A file part, an extra property at any depth, an unknown `kind`, a `role` that disagrees with
  `Sokar-Author`, and a text part one byte over the limit **must** each be refused.
- `sokar talk verify` **must** fail when a commit is edited and when one is removed — both, because
  removal is the case a naive per-commit check misses.
- A task's `talk/` **must** contain no object of another group: clone it and search for a known blob
  of the other group, rather than listing refs.
- A held group **must** deliver nothing: assert a peer's pull brings nothing new, not that the state
  field says `held`.
- A message pushed while its group was held, closed or over budget **must not** be delivered by a
  peer's gate, even though that peer runs no content check — assert on what reaches the peer's task,
  not on what the record says.
- A payload split across **two machines** into pieces that are each unremarkable **must** be refused
  on the machine that sends the piece crossing the threshold, with both senders' messages named.
- With the distributing repository unreachable, a push **must** fail loudly and the next tick **must**
  deliver what was missed.
- No key file appears anywhere a task container can read.

Anything needing podman, ssh or a remote belongs in the acceptance suite, not in surefire.

## Alternatives considered

| Option | Why not |
|---|---|
| **A self-hosted filter between every machine and the forge**, plain git over ssh with the check as its `pre-receive` hook | A host of its own, a system user, a forced ssh command, a forwarding hook and a forge credential nobody else may hold — to run the same code that already runs where the message is written. It bought one thing the local check does not: a machine could not skip it. That is worth a great deal against somebody else's machines and almost nothing against the operator's own, which is the case here. |
| **A model reviewer on every machine** | A hosted free model is a place every conversation leaks to, and a model that reads every project's messages becomes a bridge between them. The sluice is rules; a model, if one is ever added, may hold a message and never release one. |
| **A socket helper**: a `talk serve` helper per task with a socket mounted at `/run/sokar/talk.sock`, a `Talk1` varlink interface, a hash-chained journal under the state directory, and the daemon as hub | Sound on one machine and widened nothing. It had to invent what git and the gate already provide — a wire, an inbox in agents that have none, a journal and its verifier — and across machines it needed a client carrying frames between daemons, with two records and no shared clock. |
| **[FINOS GitProxy](https://github.com/finos/git-proxy)** (Apache-2.0, FINOS graduated) | A push interceptor with approval and forwarding, in TypeScript on Node with MongoDB or NeDB, bringing its own UI and user model. It is the centralized shape that was dropped; worth revisiting only if people outside Sokar have to approve messages in a browser. |
| **[Gerrit](https://gerrit-review.googlesource.com/Documentation/config-validation.html)** | A whole code-review server with its own users and UI, to run for a message filter. Worth taking if messages ever need real review with several reviewers. |
| **[Llama Guard](https://huggingface.co/meta-llama/Llama-Guard-4-12B)** | Classifies content against a list of harms — violence, weapons, self-harm. Neither exfiltration nor prompt injection is on it; a workspace in base64 is harmless by its categories. |
| **[NeMo Guardrails](https://github.com/NVIDIA/NeMo-Guardrails)** | A Python orchestration framework with its own rule language, not a detector: the detection comes from what it calls, which can be called directly. It would bring back the Python this project is removing from its build. |
| [NATS](https://nats.io/about/) + JetStream, [Matrix](https://spec.matrix.org/latest/) via [continuwuity](https://continuwuity.org/introduction), [Prosody](https://prosody.im/) | Brokers and chat servers take transport and storage off the pile, and leave the policy, the hold and a verifiable record to be built on top — beside a daemon with its own authentication database. They would also put a service where a bare repository is enough. |
| [Rekor](https://github.com/sigstore/rekor), [immudb](https://immudb.io/) | A transparency log proves more than a signed linear branch, and runs as another service. Worth revisiting if the record has to satisfy somebody who trusts neither the operator nor the forge. |
