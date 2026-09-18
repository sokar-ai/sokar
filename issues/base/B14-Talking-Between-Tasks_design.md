# B14 — Talking Between Tasks, design

How [B14](B14-Talking-Between-Tasks.md) would be built. **Nothing here exists yet**: every class,
path, method, verb and file named below is a proposal, and nothing in it has been measured. Where a
fact about the running system is quoted it comes from reading the code and the documentation; where a
fact about a third-party format is quoted it comes from that format's specification, read on the date
given.

**In short:**

- A task has **a mailbox**, mounted at `/run/sokar/mail`, shaped like a mail client's. Two verbs:
  write into `outbox/tmp/` and rename into `outbox/new/`; read `inbox/new/` and rename into
  `inbox/cur/`.
- The host is shaped like **a mail server**: queues whose directory names are the states, the filter
  between them, and one adapter per transport.
- **Transports are packages.** The first carries a message between mailboxes on one machine by moving
  a file; git between machines is a later extension.
- **A message is a narrowed A2A message with a detached signature over its exact bytes**, made on the
  host with the key the daemon's user already has.
- **The message sluice decides what a message may contain**, before anything is queued, and carries
  no model.

```
 task container                     host
 ┌───────────────────────────┐      ┌──────────────────────────────────────────────────────┐
 │ agent                     │      │  incoming/ ─▶ message sluice ─▶ queue/<transport>/   │
 │  reads  /run/sokar/mail/  │      │      ▲            │ refuses            │             │
 │         inbox/new,cur     │◀─────┤      │            ▼                    ▼             │
 │  writes /run/sokar/mail/  │      │  deliver/     rejected/        transport adapter     │
 │         outbox/tmp→new    ├─────▶│                                  (own user,          │
 └───────────────────────────┘      │  inbound/  ◀── adapter  ───────   own credential)    │
     no network, no credential      └──────────────────────────────────────────────────────┘
```

## Why the mail shape

A mail client and a mail server already split this problem, and the reasons transfer:

| Mail | Here |
|---|---|
| The client never speaks SMTP to the world; it writes to a spool | The agent never speaks a transport; it writes to a directory |
| Maildir's `tmp` → `new` rename | A message is complete when it is renamed, so no reader sees half of one |
| The queue's directory name is the state | Same: a state change is a rename, so a crash cannot leave it half done |
| A bounce is an ordinary message in the inbox | A refusal is an ordinary message in the inbox |
| The index is rebuildable; the messages are the truth | The rule the sluice states for its own index |

mbox is the one thing not borrowed: a single appended file needs locking, and a crash mid-write
corrupts it. One file per message is what makes every operation a rename.

## Inside the container

**Maildir-shaped minimum** — the rename, without flags and without folders. It stays
filename-compatible with Maildir, so flags can be added later without moving anything.

```
/run/sokar/mail/                  bind-mounted, one per task
├─ inbox/  tmp/ new/ cur/         the host writes into tmp/ and renames into new/
├─ outbox/ tmp/ new/              the agent writes into tmp/ and renames into new/
├─ sent/                          the exact bytes that were sent, written back by the host
└─ agent-card.json                the peers this task may address, and what it may do
```

`sent/` matters more here than in a mail client: the mailbox outlives the container, so it is how a
restarted agent reads its own half of the conversation.

**The agent never sees an address.** A recipient is a **peer name**; what `reviewer` resolves to is
on the host and cannot be reached from inside. **The agent definition gains one field**, reported
through `describe`: `messaging: maildir` or `messaging: none`. Sokar branches on the field, never on
the agent, and `AgentIsolationTest` fails the build if that is got wrong.

## On the host

```
<task state>/mail/                durable, per task, survives every restart
├─ incoming/                      taken from outbox/new, unchanged
├─ filter/  accepted/ feedback/ rejected/ error/ .index/
├─ queue/<transport>/ active/ deferred/
├─ hold/                          waiting for a person, or unattributable on arrival
├─ sent/                          handed over, with the signature and the delivery record
├─ inbound/                       what a transport delivered, before it reaches the container
└─ record/                        the hash-chained log
```

Every hop is a rename, so a crash leaves the message in the earlier state: at worst something is
answered twice, never swallowed.

**The mailbox is created with the task and destroyed with it**, and nothing in between touches it —
stop, start and a machine restart leave the conversation where it was. It is in the task's durable
state, never under `$XDG_RUNTIME_DIR`, which the system clears at boot: that is what makes a mailbox
different from the sockets a task cannot come back to after a reboot today.

**Removal refuses while something is in flight.** A message in `outbox/new/`, or queued and deferred,
is the same shape as commits that never reached the gate: `--rescue` hands them over first and waits,
`--force` removes regardless.

## The message

**A2A 1.0, narrowed.** Verified against `specification/a2a.proto` at tag `v1.0.1`, read 2026-09-16: a
part carries exactly one of `text`, `raw`, `url` or `data` in a `oneof`, plus `metadata`, `filename`
and `media_type`; a message carries `message_id`, `context_id`, `task_id`, `role`, `parts`,
`metadata`, `extensions` and `reference_task_ids`; the roles are `ROLE_UNSPECIFIED`, `ROLE_USER` and
`ROLE_AGENT`. There is no `kind` discriminator — it was removed in 1.0 — so a file carrying one is
from 0.3.x and is refused rather than converted. ProtoJSON is the JSON mapping, so a file spells them
`mediaType`, `messageId`, `contextId`.

What the narrowed schema allows, and nothing else:

| A2A field | Allowed |
|---|---|
| `messageId` | Required, unique for the sender |
| `role` | `agent` when a task writes, `user` when a person does |
| `contextId` | Required; the conversation this belongs to |
| `taskId`, `referenceTaskIds` | Absent |
| `extensions` | Exactly the Sokar messaging extension's URI |
| `metadata` | `kind`, `to`, `from`, `thread` — and nothing else |
| `parts` | Exactly one text part within the peer's text limit, and at most one data part whose schema is fixed by `kind`. **No file, raw or url part.** |

| `kind` | Data part |
|---|---|
| `question` | none |
| `answer` | `in_reply_to`: the id of the message answered |
| `review-request`, `handover` | `repository`, `ref`, `commit` — work is named by commit and travels through the gate, never inside a message |
| `status` | `state`: `started`, `blocked`, `done` or `abandoned` |

Every object is closed: a property the schema does not name is a refusal, not something ignored.

## The signature

**A detached SSHSIG over the message's exact bytes**, made on the host when it takes a message out of
`outbox/new/` (`ssh-keygen -Y sign`), verified with `-Y verify` against an allowed-signers list.

- **The file is opaque from end to end.** Nothing between the two mailboxes re-serializes it, so the
  bytes signed are the bytes verified — which is why the sluice is required to move a message rather
  than rewrite it.
- **One verification path for every transport**, whatever carried the two files.
- **The key is the daemon user's**, held in that user's state directory at `0600` and never mounted
  anywhere. Sokar runs as a systemd *user* unit, so two people on one machine have two identities and
  neither can read the other's key; the boundary is exactly Unix-user strength, as B13
  ([index](README.md)) already says for the guard.
- **It is not the user's login key.** One lets a machine connect, the other says what it vouches for:
  a login key is often passphrase-protected, and revoking one should not invalidate the other.
- **It says the installation vouched, not that the agent typed it.** The task never holds a key;
  which task and project spoke is inside the signed bytes.

**Measured on 2026-09-18, both directions.** The format is OpenSSH's, not a shape of our own, and
that is only worth claiming if OpenSSH agrees:

    $ ssh-keygen -Y verify -f allowed_signers -I sokar@interop -n sokar-message -s sig < message.json
    Good "sokar-message" signature for sokar@interop with ED25519 key SHA256:HCbWdFByflN0Qa...

One byte changed in the message and the same command answers *"Signature verification failed:
incorrect signature"*. The other direction holds too: a signature made by `ssh-keygen -Y sign` with a
key Sokar never saw verifies here, while the wrong namespace, a tampered message and a different key
each answer false. So a peer can check a message with nothing but `ssh-keygen` and an
`allowed_signers` file, and a person can sign one without Sokar at all.

**Measured again on 2026-09-18, this time end to end rather than in a harness.** A message written
into a task's outbox, signed at intake, filtered, queued, carried by the local transport and
delivered into the peer's inbox was then checked from the outside with nothing but the key line
`sokar talk key` prints:

    $ ssh-keygen -Y verify -f allowed_signers -I sender -n sokar-message -s <sig> < <message>
    Good "sokar-message" signature for sender with ED25519 key SHA256:7Hdo/EiXE13Reg...

So what the pipeline produces - not only what a test produces - is verifiable by somebody with no
Sokar. The key line is `<principal> ssh-ed25519 <key>` and nothing after it: OpenSSH accepts a
trailing comment, measured, but a line reading `sender ssh-ed25519 AAAA... sender` makes a person
decide which of the two names matters, and neither does.

Transport-native signatures are a second, weaker signal about the hop and never the basis of trust:
DKIM says a message left a domain unmodified, not who wrote it.

## Peers, groups and trust

```yaml
peers:
  reviewer:  { address: "local:sokar-utils4j-review", trust: vouched }
  ops:       { address: "mail:ops@example.org",       trust: external }
groups:
  release:   [ reviewer, ops ]
```

**A group is a host-side peer list.** Sending to it fans out to its members over whatever transport
each uses, and its control state — held, closed, mode — is local. That keeps groups working on
transports with no shared object.

**Trust is a property of the peer**, not of what carried the message:

| Arrival | What the host does |
|---|---|
| Signed by a key listed for a **vouched** peer | Attribution checked; content not re-checked - that machine already did |
| Signed by a key listed for an **external** peer | Attribution checked, and the content put through the sluice on the way in |
| Unsigned, or signed by a key not listed for that peer | **Never delivered**: held, and the operator told |

**Inside the group, keys come from a signed directory.** One `allowed_signers` file mapping a
principal to its key, changed only by an operator-signed commit and distributed with the group's
other configuration. Each host pins one thing out of band: the operator key that signs it. Once there
is a git transport (`sokar-message-sluice` 003), that repository is what distributes it; until then it
is a local file, because a single machine has nothing to distribute to.

**A certificate authority is left open deliberately.** `cert-authority` is a line in the same
`allowed_signers` file, so switching later is configuration rather than redesign. It would then need
certificate expiry, renewal, a revocation list that reaches every host, and a CA key whose loss forges
every identity in the group.

**Outside the group, a person confirms once.** The first message signed by an unknown key waits in
`hold/` with its fingerprint shown; one confirmation writes it into that peer's entry. A key that
changes is held again with both fingerprints, because a rotation and an impersonation are identical to
software.

**Fail closed.** An unreachable directory means verifying against the last fetched copy and holding
what cannot be verified — never accepting because the list could not be read.

## What the host promises

- **One promise about sending: accepted for delivery.** The receipt says the message was handed to its
  transport and nothing stronger. Anything a transport genuinely knows beyond that arrives as an extra
  labelled fact.
- **No order is promised**, and a message whose id was already delivered to that mailbox is dropped
  and logged, so a retry cannot make an agent act twice on one instruction.
- **Limits are per task, per peer, in both directions.** Beyond them a sender is refused with a reason
  rather than queued. Size limits come from the transport's `describe`.

## The adapter contract

Packages install into a directory Sokar scans, and each describes itself rather than being registered:

```
/usr/libexec/sokar/transports/sokar-message-transport-local
```

| Verb | What it does |
|---|---|
| `describe` | Its scheme, whether it can poll, what it can confirm, its size limit, the credentials it needs, **which hosts it reaches** and **what it attests** |
| `check` | Validates configuration and credential without sending anything, for `sokar doctor` |
| `send <file> <sig> --to <rest>` | Takes one message from its queue. `<rest>` is the peer's address after the colon, **never a path the host invented**. Exit 0 handed over, 75 temporary (stays in `deferred/`), anything else refuses it back to the sender |
| `poll --into <inbound>` | Fetches what arrived into that directory, atomically. A transport that needs no polling says so |
| `receipt` | Optional: what became of a message it sent |

**What `--to` carries, and why it is not a path.** The host passes the peer's address with the
scheme removed and nothing else: `local:/some/inbound` gives `/some/inbound`, and `spool:bob` gives
`bob`. What that string means is the transport's business - a path for one, an account name for
another, a branch for a third - and a host that turned `bob` into
`/var/spool/sokar/drop/bob` would be encoding a layout it does not own and cannot keep in step.
The same applies on the way in: `poll` is told **where to put things**, not where to find them,
because where a transport keeps its own arrivals is the transport's layout too. A transport that
wants a `--from` for its own tests may have one, as long as it works without it: the host does not
pass it.

**The wire, settled with the sluice's agent on 2026-09-18.** `describe` reads nothing and prints one
JSON object - `scheme`, `poll`, `confirms` (`handover`, `receipt` or `read`), `max_bytes`,
`credentials`, `hosts`, `attests`. `check` prints its reason on stderr and exits 0 usable or 2 not.

### What a transport attests

`attests` is a list of facts a transport proves about a message it hands over, from something it
saw and the host cannot see afterwards. Empty for every transport that merely carries bytes - the
local one answers `[]` - and it is the mechanism by which **one rule of this requirement can be
lifted for one transport without being weakened anywhere else**.

| Fact | What the transport must do | What the host then requires |
|---|---|---|
| `owner` | Write `<message>.owner` beside the pair in `inbound/`, one line `<uid> <name>`, read from the file's `st_uid` **before** it was moved | The file must be there; the name must equal the Unix user in the peer's address; the message's signature must still verify against a key listed for that peer |

**Why a file and not a line of output.** The owner has to be read while the message is still where
the sender left it: a spool directory and a home directory are usually different filesystems, so
the move into `inbound/` copies rather than renames and the original owner is lost. And a file
survives a crash between the transport finishing and the host reading, where a line on standard
output does not.

**Why it cannot be forged by a sender.** The `.owner` is written by the *recipient's* transport,
running as the recipient, about a file the sender wrote. A sender who copies somebody else's validly
signed message into a drop still owns the file they wrote, and the attestation says so.

**Fail closed, both ways.** A transport that claims `owner` and hands over a message without one has
its message **held**. A transport that claims nothing has an `.owner` file ignored, so it cannot
gain trust by volunteering one.

### Across Unix users on one machine

**The default is unchanged: a mailbox owned by another Unix user is refused**, and the local
transport refuses it always. Two users are two Sokar installations with two signing identities
sharing only a kernel, and a file moved between them is carriage between hosts rather than a local
delivery.

**Each account publishes its message key into a directory of its own** - `/var/spool/sokar/keys/<user>/key.pub`,
made by root with that account's drop - and a key is believed only when **both the directory and the
file** belong to the account they are named after. A single shared directory was tried first and is
wrong: measured on 2026-09-18, a group member could create `keys/<other>.pub` before that account
ever published, and the sticky bit then stopped the rightful owner removing it - silencing them
until root intervened, with nothing forged and nothing repairable. Found by the filter's agent.

**Where an operator has deliberately allowed it for a machine**, a transport that attests `owner`
may carry between them - addressed `spool:<unix-user>`, so that the address itself names who the
host must find the message to be owned by. Nothing is allowed by installing a package: the drop
directories are made by a step where the machine is set up, and removing them refuses every such
send again. Designed with the filter's agent as their issue 010, agreed on the channel on
2026-09-18.
`send <message> <signature> --to <address>` prints its receipt as one JSON object.
`poll --into <dir>` writes pairs there and prints how many. **Nothing is ever read from standard
input**: messages are files, and a transport that read stdin would tempt somebody to stream a message
through it. A signature file is named `<message file name>.sig`.

**An adapter writes in exactly one place, and the host does the rest.** It delivers into the
recipient's `inbound/tmp/` and renames into `inbound/`; it never removes anything from its own queue.
On exit 0 the host moves the queued pair to `sent/` and writes the receipt there; on 75 it moves it to
`deferred/`. A crash between the adapter's rename and the host's move is therefore safe: the retry
finds the same name in `inbound/` with the same bytes and answers 0 without writing a second copy,
and answers permanently if the bytes differ. For `local:`, `--to` is the absolute path of the
recipient's `mail/inbound/` - resolved by the host, derived by nobody.

**Which queue a message goes to is the host's decision, never the filter's.** The filter writes every
accepted message into `filter/accepted/`, and the host dispatches from there into
`queue/<transport>/active/` after resolving `metadata.to` against the peer table. Resolving a peer to
a transport is policy, and policy stays where the peer table is - the same reason a container never
sees an address. One filter pass can therefore accept messages for three peers on three transports.

**Where it runs**: on the host, as its own unprivileged user, credential from the vault through the
same proxy a task uses — never in a task container, never in the same process as the sluice. Its
declared hosts are what the egress configuration permits it to reach.

**What it may not do**: change a message's bytes, decide whether a message may be sent, read another
transport's queue, write into the container's mount, or **create a directory**. The host owns the
layout - it makes the mailbox when it makes the task - and both the filter and the adapters refuse or
defer when something is missing. An adapter that created `inbound/tmp/` because it was absent would
deliver into a mailbox whose owner never made it, which is either a removed task or a broken layout,
and both deserve an answer rather than a directory.

**The first one is local** (`sokar-message-sluice` 002): it moves a file into the recipient's
`inbound/` on the same machine. No network, no credential, no history — and on a developer's machine
that is the whole of what is needed. **The git transport** (`sokar-message-sluice` 003) is designed
and not built: it is what adds distribution across machines, a shared ordered record, and a way to
distribute the key directory.

## The content check is its own tool

`sokar-message-sluice`, in a repository of its own, where its requirement and design are issue 001.
Sokar decides *who* may say something to *whom*; the sluice decides *what* a message may contain: the
narrowed schema, encoded payloads (caught by asking whether the text obeys the statistics of English),
credentials and personal data (a catalogue of pattern, context and checksum), and a payload spread over
several messages.

It is a content filter in the delivery path, not a mail server: no queue, no timer, no retry, no
address, and no knowledge that any transport exists. It runs **before anything is queued**, so nothing
sits in a transport's queue unchecked, and its refusal is delivered as a bounce into the sender's own
inbox.

**Its corpus is the task's own sent messages**, which live and die with the mailbox. The accepted gap:
a payload spread over two tasks the same operator started is seen by neither.

**It never rewrites a message** — the signature is over the exact bytes.

## The record

**A hash-chained log per host**, in `record/`: one line per state change — arrived, filtered, queued,
sent, deferred, refused, delivered — each line's hash including the previous line's, beside the signed
messages. Tamper-evident locally, identical for every transport, verifiable without a network.

**Anchorable later, anchored to nothing today.** If the record ever has to convince somebody who does
not trust the operator, the head hash can be published where the operator does not control it —
OpenTimestamps, or a transparency log. That is a publishing step, not a redesign, and no content ever
leaves: only a root hash. A blockchain is the expensive way to buy the same property, and it brings one
that is actively wrong here: nothing published can be withdrawn.

## At rest

`0700` directories owned by the account that runs Sokar — the same protection the workspace and the
task logs already have. **Refused originals live as long as the task**, with no timer: one lifetime for
everything a task owns, and `task remove` takes them with the rest.

**The exposure, stated:** `rejected/` is the one directory whose contents are secret *because* the
filter worked, so a long-lived task accumulates every refused secret for its life, behind file
permissions and nothing else — the same protection the workspace beside it has. **What would change the
answer:** tasks living for months, or a `rejected/` too large for anybody to read.

## The daemon's interface

Additions to `org.fuin.sokar.Tasks1`, which only ever grows. `InterfaceDescriptionTest` fails the build
if any of this is registered without appearing in the file, or appears without being registered.

```
type Peer (
  name: string,
  # local, git, mail, ... - whatever the adapter's scheme is.
  transport: string,
  # vouched or external.
  trust: string
)

type Message (
  # The message's own id. Pass it back unchanged.
  id: string,
  task: string,
  peer: string,
  # question, answer, review-request, status or handover.
  kind: string,
  # sent, delivered, held, refused or unattributed.
  state: string,
  at: string,
  text: string
)

type TalkOutcome (
  ACCEPTED, HELD, NOT_DECLARED, REFUSED_BY_CLASS, NO_MESSAGING, REFUSED_BY_RULE, CLOSED,
  OVER_BUDGET, RECEIVER_FULL, UNATTRIBUTED, RULE_BROKEN, NO_SUCH_PEER, PEER_GONE,
  TRANSPORT_UNREACHABLE
)

# Every peer the projects on this machine may address.
method Peers() -> (peers: []Peer)

# Every message in every mailbox on this machine, as it moves. Streaming only, as Prompts works.
method Talk() -> Message

# The person writes into a conversation, signed with their key. The author is set here.
method Say(peer: string, kind: string, text: string) -> (message: ?Message, outcome: TalkOutcome)

# Releases or refuses one held message, or confirms the key of an unknown sender.
method Release(id: string, allow: ?bool, trustKey: ?bool) -> (message: Message)

# Holds or releases a whole peer or group, or changes its mode.
method Moderate(name: string, held: ?bool, mode: ?string) -> (peer: Peer)

error NoSuchPeer(name: string)
```

## The command line

`sokar talk` mirrors `sokar shield`: the same objects, the same refusals, rendered rather than
re-decided.

```
sokar talk peers
sokar talk log [<peer>] [--follow]
sokar talk say <peer> [--kind question]    # text on stdin, never in argv
sokar talk held
sokar talk release <id> [--refuse] [--trust-key]
sokar talk hold <peer> [--release]
sokar talk verify [<task>]
```

**`say` reads the text from standard input**, the way `vault put` does: a process list is
world-readable, and nobody can promise what an operator will paste into a message to an agent.

## Lifecycle

| Event | What happens |
|---|---|
| `task start` | The mailbox is created if it does not exist and bind-mounted; what is waiting in `inbound/` is delivered |
| A message | Policy, the sluice, the host's signature, the transport queue, the adapter. A failure at any step stops the next |
| `task stop` | Nothing to reap. The mailbox stays as it is |
| `task resume` | The same mailbox is mounted again; `inbox/cur/` and `sent/` are still there |
| A machine restart | The mailbox is in durable state, so it survives; the queues resume where they were |
| `sokar panic` | Tasks stop with everything else; nothing is handed to a transport; the record stays |
| `task remove` | Refused while something is in flight; otherwise the mailbox goes with the task |

## Failure modes, and what each must do

| When | What must happen | Why it is listed |
|---|---|---|
| The sluice cannot start or cannot run | Nothing is queued. | Fail closed: an unchecked message is what this exists to prevent |
| A transport cannot reach its destination | The message stays in `deferred/` and the sender is told on its receipt | A message waiting silently reads as ignored |
| The recipient's `inbound/` exists but `inbound/tmp/` does not | Temporary failure: the message stays in `deferred/` until the host repairs the mailbox | The layout is the host's; a delivery that repaired it would hide a malformed mailbox and deliver into it |
| A transport's destination does not exist | Refused back to the sender, not retried. The adapter reports only that the destination is gone; the host says which it was - `NO_SUCH_PEER` when the name is not in the peer table, `PEER_GONE` when it is and the mailbox is not | Retrying forever is how a queue dies, and only the side holding the table can tell a typo from a removed task |
| A peer's mailbox belongs to another Unix user | Refused, enforced rather than documented | Sokar runs as a user unit: two people on one machine are two installations with two signing identities, sharing only a kernel. A file move between them is not a local delivery |
| An adapter alters a message's bytes | Verification fails at the far side | The signature is the only thing that can catch it |
| An arrival cannot be attributed | Held, never delivered, operator told | A task must not act on something nobody can attribute |
| A message arrives twice | Delivered once | Acting twice on one handover is the expensive failure |
| A task is removed with mail in flight | Refused, unless `--rescue` or `--force` | The same rule as unpushed commits |

## What must be proven to fail

- A message the sluice refuses **must not** reach any transport queue, and the refusal the agent is
  shown **must not** contain what the rule matched.
- An accepted message **must** reach the peer byte for byte: hash it in `outbox/new/` and again at the
  far end.
- A message with a file, raw or url part, an unknown property at any depth, an unknown `kind`, or a
  text part one byte over the limit **must** each be refused.
- An unsigned message, and one signed by a key not listed for its peer, **must** each be held rather
  than delivered — asserted on what reaches the task, not on a state field.
- A key change for a known peer **must** hold the message and ask again.
- A duplicate id **must** be delivered once; a crash between queueing and the receipt **must not**
  produce two.
- A removal with something in flight **must** refuse; with `--rescue` it **must** hand over first.
- A task's mailbox **must** survive `stop`, `start` and a machine restart with `inbox/cur/` and `sent/`
  intact, asserted on a real restart rather than a simulated one.
- **No key file appears anywhere a task container can read**, and no adapter runs inside one.
- A partially written message **must never** be visible in `new/`: write a large one while a reader
  lists the directory in a loop.

Anything needing podman, a second user or a real restart belongs in the acceptance suite, not in
surefire.

## Alternatives considered

| Option | Why not |
|---|---|
| **A git repository as the channel itself**, as this requirement was first designed | It is a transport, and a good one between machines: it becomes `sokar-message-sluice` 003 rather than the shape of the whole feature. As the only channel it put a clone, a push and a second repository inside every container, and made the record depend on the route |
| **A socket helper per task**, with a varlink interface and a journal | Sound on one machine, and it had to invent an inbox for agents that have none, a wire, a journal and its verifier. A directory needs none of that, and every agent already knows how to write a file |
| **A model reviewer on every machine** | A hosted model is a place every conversation leaks to, and one that reads every project's messages becomes a bridge between them. The sluice is rules; a model, if ever added, may hold and never release |
| **[Llama Guard](https://huggingface.co/meta-llama/Llama-Guard-4-12B)** | Classifies content against a list of harms; neither exfiltration nor prompt injection is on it |
| **A blockchain, or blockchain-addressed mail** | Buys ordering and timestamps that mutually distrusting parties believe; the parties here are the operator's own machines. It brings a funded key, an RPC endpoint on the host's egress, block latency, and publication that cannot be withdrawn |
| [NATS](https://nats.io/about/), [Matrix](https://spec.matrix.org/latest/), [Prosody](https://prosody.im/) | Brokers and chat servers take transport and storage off the pile and leave the policy, the hold and the record to be built on top — beside a daemon with its own authentication database. They are candidates for an adapter, not for the shape |
| [Rekor](https://github.com/sigstore/rekor), [immudb](https://immudb.io/) | A transparency log proves more than a hash-chained local log and runs as another service. This is what "anchorable later" keeps the door open for |
