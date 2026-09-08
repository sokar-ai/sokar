# B14 — Talking Between Tasks, design

How [B14](B14-Talking-Between-Tasks.md) would be built. **Nothing here exists yet**: every class,
path, method and file named below is a proposal, and nothing in it has been measured. Where a fact
about the running system is quoted it is marked as such, and it comes from reading the code rather
than from an experiment run for this document.

The requirement argues *whether* and *what must be true*. This argues *how*, in enough detail that
the expensive decisions are visible before anything is written.

## Scope of the first version

**One machine. Two tasks. A person watching.**

Out of scope, deliberately, and each for a reason argued in the requirement: talking across
machines, signing by an agent, editing a held message, and any conversation with more than two
participants. Three participants is not a bigger version of two — it needs addressing, ordering and
a notion of "who is this for" — and none of that is worth designing before two tasks have talked
once.

## The shape

```
  task A container                    host                         client
  ┌────────────────┐        ┌────────────────────────┐        ┌──────────────┐
  │ agent          │        │ sokar talk serve (A)   │        │ frontend/CLI │
  │  /run/sokar/   │───────▶│  Talk1 on a unix socket│        └──────┬───────┘
  │    talk.sock   │        │           │            │               │
  └────────────────┘        │           ▼            │               │ varlink
                            │        TalkHub  ───────┼── journal     │ over the
  task B container          │           │            │   (hash chain)│ owner-only
  ┌────────────────┐        │           ▼            │               │ socket
  │ agent          │◀───────│ sokar talk serve (B)   │◀──── sokard ◀─┘
  └────────────────┘        └────────────────────────┘
```

Two host-side helpers, one per task, each holding a socket that is bind-mounted into exactly one
container. Both talk to the daemon, which owns the policy, the record and the conversation state.
Nothing crosses between the containers.

## What gets built, module by module

| Module | What is added |
|---|---|
| `talk/` (new) | The domain: `Conversation`, `Message`, `Author`, `TalkHub`, `TalkJournal`, `TalkService`, `TalkPolicy`, `TalkException`, `MessageState`, `Mode`. Mirrors `clearance/`, which is the closest thing that already exists. |
| `core/project` | `Talk` record on `Project`; `ProjectReader` learns one key; `SecurityClass` gains nothing. |
| `app/` | `TalkCommand` and its subcommands, `TalkServeCommand`, `TalkWiring`, `TalkEdit`, one entry in `TaskHelpers`, three methods on `SokarPaths`. |
| `runtime/` | Nothing. `ContainerSpec.volume` already does what is needed. |
| `daemon/` | New methods and types on `org.fuin.sokar.Tasks1`, and the interface file that is the contract. |
| `agents/api` | One field in the agent definition and its `describe` response. |

The new module is `talk` rather than a package inside an existing one because the helper is a
separate process with its own varlink interface, which is exactly why `clearance` is its own module.

## The socket, and the container end

**Host path:** `SokarPaths.containerState(container).resolve("talk.sock")`, beside the task's other
runtime files.

**Mount point:** `/run/sokar/talk.sock`, the third entry in a directory that already holds
`/run/sokar/vault.sock` and `/run/sokar/ssh-agent.sock` (`TaskWiring.VAULT_MOUNT`,
`TaskWiring.SSH_MOUNT`). Nothing new in the container's shape: one more file in a directory the
agent already has.

**Permissions:** the socket file is world-writable inside a `0700` directory, because a rootless
container's agent user is a subordinate uid that cannot open a `0600` file the host user owns, and
the directory is what carries the access control. This is the existing rule, not a new one.

**SELinux:** the socket must be labeled at creation through `SocketContext.openUnixSocket()`, for
the reason already recorded: the kernel assigns the label at `socket()`, not at `bind()`, so
wrapping the bind leaves it unlabeled while everything else looks correct, and the denial is
`dontaudit`ed and presents as an authentication failure with an empty log.

**Phase `BEFORE`.** A bind-mounted socket is bound to the file that existed when the container
started, so the helper must be up before `podman start` or the container holds a deleted inode and
every message vanishes while the same call works from the host. It goes into `TaskHelpers` with
`phase = BEFORE` and `name = "talk"`, alongside `vault`, `gate` and `watcher`, so that `task
resume` replays the command it was started with.

**One helper per task, named `talk`.** Every helper of a given name writes the same pid file, so a
second one started by a resume would orphan the first and the poststop hook would reap neither.
The existing rule applies unchanged: a running task answers `already up; nothing to resume`.

## The wire, on that socket

**`org.fuin.sokar.Talk1`, varlink, on the mounted socket.** Not a new protocol: the product already
frames varlink, `org.fuin.sokar.Clearance1` is the precedent for a helper serving its own interface,
and the helper is the `sokar` binary re-invoked — so `SokarBinary.path()` is how it is started, and
never `ProcessHandle.current()`, which inside `sokard` answers with the wrong binary and produces a
task whose helpers are silently missing.

```
interface org.fuin.sokar.Talk1

# Says something into whichever conversation this task is in.
#
# There is no author parameter and there never will be. Who is speaking is decided by which
# socket the bytes arrived on - this socket is mounted into exactly one container - and anything
# a caller asserts about its own identity is discarded rather than validated.
method Say(conversation: string, text: string) -> (accepted: bool, state: string)

# Everything addressed to this task, as it is delivered.
#
# Streaming only. Answering once would deliver the backlog and then nothing ever again, which is
# the mistake Prompts already refuses with StreamRequired.
method Listen() -> (
  conversation: string,
  seq: int,
  # The peer's task name, so an agent can tell two conversations apart. Never a claim by the
  # sender: the daemon fills it in.
  from: string,
  # "task" or "operator". An agent must be able to tell a person's words from a machine's.
  author: string,
  at: string,
  text: string
)
```

`Say` answers `state` rather than only a boolean because *held* and *refused* are different things
to the caller: an agent told nothing, or told "no", will retry — the clearance path learned that a
decision the watcher had forgotten turned into a retry loop within seconds.

## Identity, and why there is no key

The helper is started with the container name on its command line, serves one socket, and that
socket is mounted into one container. So authorship is a property of the transport and needs no
credential. This is the vault proxy's own resolution, and it is the whole argument in the
requirement's *Whether an agent should sign what it says*: a key an agent can reach to sign with is
a key it can copy, and it would be the first credential inside a task container.

**The command line carries no secret.** The helper is given a container name and paths, which is
not sensitive; `/proc/<pid>/cmdline` is world-readable and that rule holds here as everywhere.

## Reaching the agent

The one architectural rule bites here: nothing outside `agents/` may name an agent, and no shipped
agent has an inbox.

**The agent definition gains one field**, reported through `describe`, saying how this agent can be
given a message:

| Value | What Sokar does |
|---|---|
| `mcp-unix` | Points the agent at `/run/sokar/talk.sock` as an MCP server offering a send tool and a receive tool. The target shape: a real inbox, no polling. |
| `file` | Writes each delivered message as a file under a directory in the workspace, and the agent's instructions tell it to read them. Works with anything, and is polling. |
| `none` | The agent cannot take part. `Open` refuses with `NO_INBOX`. |

Sokar branches on the *field*, never on the agent. Adding a fourth shape is a new value and a new
delivery strategy in `talk/`, not a case in a `switch` over names — and `AgentIsolationTest` fails
the build if that is got wrong.

**Refusing is the point of `none`.** A message delivered into a void, with the sender told it
arrived, is the worst outcome available here: it produces a conversation that reads as ignored
rather than as impossible.

## Who may talk to whom

**Declared in `project.yml`, by both sides.**

```yaml
project:
  name: "sokar"
  security_class: "guarded"
talk:
  peers:
    - "sokar-frontend"
```

`ProjectReader` learns `talk.peers`; `Project` gains a `Talk` record beside `Egress` and `Limits`.
Editing goes through a `TalkEdit` built like `EgressEdit`: replace the key where it stands, leave
every line it does not understand alone, and parse the result with `ProjectReader` before writing —
`project.yml` is the one file here a person writes by hand and reads in a diff, and a
load-and-dump throws away their comments, key order and quoting.

**`TalkPolicy` in `core` is the only place that decides.** The daemon and the CLI both ask it and
neither decides anything itself, for the reason `TaskInventory` and `TaskControl` exist: a refusal
that lived in one and not the other would be a conversation opened remotely that the machine's own
tooling says is impossible.

The rules, in the order they are checked:

1. **Both projects name the other.** One-sided is refused: a project file that could grant itself
   access to another project's task is an ACL written by the caller.
2. **Neither project is `offline`.** That class promises nothing resolves and nothing leaves, and a
   conversation is a way out; `Project`'s constructor already refuses an offline project that
   declares egress, and this is the same refusal.
3. **Both projects are the same class.** A `guarded` task talking to an `online` one reaches an
   upstream through its peer, and neither end did anything forbidden. Refused as
   `REFUSED_BY_CLASS`, the outcome `SetEgress` already answers with.
4. **Both agents can receive.** Otherwise `NO_INBOX`.

Refusals are **outcomes, not errors** — the same choice `SetEgress` and `WidenTask` made, so that
an interface can render a reason instead of an exception.

## The record

**`~/.local/state/sokar/talk/<conversation>.jsonl`**, one JSON object per line, `0600`, reached
through a new `SokarPaths.talkJournal(String conversation)`. Under the state directory rather than
the runtime one for the reason `clearanceJournal` is: `/run/user/<uid>` is cleared when the user's
last session ends and `task stop --remove` deletes it outright, and a record of what two agents said
to each other that disappears with the task is not a record.

```json
{"seq":1,"at":"2026-09-08T10:14:02Z","conversation":"7f3a…","from":"sokar-a1b2",
 "author":"task","state":"delivered","text":"…","prev":"0000…","hash":"9c1e…"}
```

**The chain.** `prev` is the previous line's `hash`; the first line's `prev` is 64 zeros. `hash` is
SHA-256 over the line's canonical serialization with `hash` itself removed — the same `Json.write`
the rest of the product uses, so the bytes hashed are the bytes written. `sokar talk verify
<conversation>` walks it and names the first line that does not verify.

What this buys and what it does not: an entry changed or removed after the fact stops verifying,
with nothing to distribute, rotate or revoke. It does not protect against the owner of the machine,
who can rewrite the whole chain — nothing can, and B13 says the same about the other door.

**Written before delivered.** The order is: policy, append, deliver. A message that cannot be
appended is not delivered, and the sender is told so. Reversing those two would make "a person sees
everything" an aspiration rather than a property.

## Moderation, holding and stopping

**Modes are the four `clearance` already uses**, with the same meanings, set when the conversation
is opened and changeable while it runs:

| Mode | Effect |
|---|---|
| `prompt` | Every message is appended as `held` and delivered only when a person releases it. |
| `allow` | Delivered immediately, still appended first. |
| `deny` | Appended as `refused`, never delivered, sender told. |
| `off` | Delivered with no per-message gate. Its own state, not the widest setting, and readable back — the same argument that put `clearance` on `Task`. |

**A conversation is `open`, `held` or `closed`.** Held delivers nothing and tells each sender it was
held. Closed is final: a further `Say` is refused rather than queued, because a queue nobody will
ever drain is a lie told to whoever is waiting for an answer.

**Fail closed.** If the daemon cannot be reached, or the journal cannot be written, or the
conversation's state cannot be read, nothing is delivered. The startup rule for the nft hook is the
same rule.

**The turn budget** is a count set when the conversation is opened, decremented per delivered
message, and its exhaustion **closes** the conversation and says so. A warning is not a control, and
two agents in a conversation is a loop with a bill attached and no natural end.

## The daemon's interface

Additions to `org.fuin.sokar.Tasks1`, which only ever grows — new methods, new `?` parameters, new
reply fields, no `Tasks2`. `InterfaceDescriptionTest` fails the build if any of this is registered
without appearing in the file, or appears without being registered.

```
type Conversation (
  # Pass this back unchanged. It is the daemon's to generate: a client that derived it would
  # address a conversation that does not exist, which is the mistake Prompt.key already avoids.
  id: string,
  tasks: []string,
  projects: []string,
  # prompt, allow, deny or off.
  mode: string,
  # open, held or closed.
  state: string,
  turns: int,
  budget: int,
  opened: string
)

type Message (
  conversation: string,
  seq: int,
  # Container name of the writing task, or "" when the author is the person.
  task: string,
  # "task" or "operator".
  author: string,
  at: string,
  text: string,
  # held, delivered or refused.
  state: string,
  # Its line in the record, so a client can quote the chain rather than rebuild it.
  hash: string
)

type TalkOutcome (
  OPENED, NOT_DECLARED, REFUSED_BY_CLASS, NO_INBOX, ALREADY_OPEN, NO_SUCH_TASK, CLOSED, HELD,
  OVER_BUDGET
)

# Every conversation on this machine.
method Conversations() -> (conversations: []Conversation)

# Every message in every conversation, as it happens - including conversations opened after the
# call. One subscription, no per-task discovery, exactly as Prompts works. Streaming only.
method Talk() -> Message

# Opens one between two running tasks.
method Open(a: string, b: string, mode: ?string, budget: ?int)
  -> (conversation: ?Conversation, outcome: TalkOutcome)

# The person writes into a conversation. The author is set by the daemon, never by the caller.
method Say(conversation: string, text: string) -> (message: Message, outcome: TalkOutcome)

# Releases or refuses one held message, mirroring Decide.
method Deliver(conversation: string, seq: int, allow: ?bool) -> (message: Message)

# Holds or releases the whole conversation, and changes its mode.
method Moderate(conversation: string, held: ?bool, mode: ?string) -> (conversation: Conversation)

# Ends it. A further Say is refused rather than queued.
method Close(conversation: string, reason: ?string) -> (conversation: Conversation)

error NoSuchConversation(conversation: string)
```

## The command line

`sokar talk` mirrors `sokar shield`: the same objects, the same refusals, rendered rather than
re-decided.

```
sokar talk open <taskA> <taskB> [--mode prompt|allow|deny|off] [--budget N]
sokar talk list
sokar talk log <conversation> [--follow]
sokar talk say <conversation>          # text on stdin, never in argv
sokar talk deliver <conversation> <seq> [--deny]
sokar talk hold <conversation> [--release]
sokar talk close <conversation> [--reason ...]
sokar talk verify <conversation>
sokar talk serve ...                   # the helper, not for people
```

**`say` reads the text from standard input**, the way `vault put` does. Not because a message is a
credential, but because a process list is world-readable and nobody can promise what an operator
will paste into a message to an agent.

## Lifecycle

| Event | What happens |
|---|---|
| `task run` | The helper starts in phase `BEFORE`, its socket is mounted, its command is recorded in `resume.json`. No conversation exists yet. |
| `talk open` | Policy is checked, the conversation is created, both journals' first lines are written. Refused as an outcome, never an exception. |
| A message | Policy, append, deliver — in that order, and a failure at any step stops the next. |
| `task stop` | The poststop hook reaps the helper by its pid file. The conversation is closed with a reason naming the task that went. |
| `task resume` | The recorded command is replayed, the helper comes back, the socket is fresh. **Whether the conversation comes back with it is an open question**, below. |
| A container that never started | `TaskRunner.reapOrphans` covers the helper, since no hook fires for a container that never ran. |
| `sokar panic` | Stops the helper with everything else, removes nothing, and the record stays. |

## Failure modes, and what each must do

| When | What must happen | Why it is listed |
|---|---|---|
| The journal cannot be written | Nothing is delivered; the sender is told. | Otherwise "a person sees everything" is untrue and nothing detects it. |
| The daemon is not reachable from the helper | Nothing is delivered; the sender is told. | Fail closed, as on the container start path. |
| The peer task has stopped | The conversation closes with a reason; the survivor is told. | A half-open conversation is the normal case, not an edge one. |
| The agent declares no inbox | `Open` refuses. | A message dropped silently reads as an agent ignoring it. |
| The budget runs out | The conversation closes and says so. | A warning nobody is watching is not a control. |
| A second `talk serve` is started for one task | Refused; the running one keeps the pid file. | Two helpers of one name orphan each other, measured for other helpers. |
| The socket is replaced after container start | Cannot happen: phase `BEFORE`. | A bind mount holds the inode that existed at start. |

## What must be proven to fail

Per the rule that a test nobody has watched fail is a test nobody has checked, and that the *right*
thing must fail:

- A one-sided `talk.peers` declaration **must** be refused — assert on whether a message reaches the
  peer's journal, not on the wording of the refusal.
- An `offline` project **must** be refused, and a `guarded`-to-`online` pair **must** be refused.
  Mutate the class in the fixture and watch each break.
- A held conversation **must** deliver nothing: assert the peer's `Listen` stream is silent, not
  that the state field says `held`.
- The chain **must** stop verifying when a line is edited, and when a line is removed. Both, because
  removal is the case a naive per-line hash misses.
- The record **must** be written before delivery: make the journal unwritable and assert the peer
  received nothing.
- No key file appears anywhere under the container's mounted directory. Cheap, and it is the
  property the requirement's signing section rests on.
- `AgentIsolationTest` already fails the build if any of the delivery code names an agent. Break it
  once on purpose while writing the delivery strategies.

Anything needing podman — the mount, the socket from inside the container, an agent actually
receiving — belongs in `buildtools/e2e-tier1.sh`, not in surefire.

## Deliberately not designed here

- **Cross-machine.** Argued in the requirement: the daemon binds no network interface in any
  configuration, so a daemon that accepts a peer is a different product. If it happens, the client
  carries frames and each daemon keeps its own policy and its own record.
- **A daemon signature.** Only relevant with an intermediary, which only cross-machine has.
- **Editing a held message.** Rewriting what an agent said destroys the only thing the record is
  for; refusing may not be enough for somebody watching two agents talk each other into something
  wrong. Undecided in the requirement, and it changes the journal's schema, so it is worth settling
  before the first line is written rather than after.
- **More than two participants.**
- **Who pays for a turn.** A message causes work in the receiving task against another project's
  provider and credential, and nothing in the product models that today.

## Open questions this design leaves

- **What a conversation means across a resume.** A resumed task gets a fresh namespace and a
  ruleset rebuilt from the project, and the clearance path learned that a live-only change is gone
  the moment the task comes back. A conversation is more than a live change and less than a project
  setting. Reopening it silently would resume a dialogue neither agent remembers being in; closing
  it makes `resume` lose something a person may have been watching.
- **Whether `mcp-unix` works at all from inside a rootless container**, against a socket whose
  access control is the directory around it, for an agent that was never told it is in a container.
  This is the load-bearing unmeasured assumption of the whole delivery half, and it should be tested
  against one real agent before the rest is built.
- **Whether the conversation id should be per pair or per opening.** Per opening means two tasks
  that talk twice have two records, which is right for the chain and awkward for a person looking
  for "the conversation between these two".
- **Whether `Listen` should replay a backlog.** An agent that connects late has missed messages that
  were delivered while it was not reading. Replaying risks acting twice on the same instruction;
  not replaying loses them silently. Neither is obviously right, and it cannot be left to the agent.
