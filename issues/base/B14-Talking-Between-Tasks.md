# B14 — Talking Between Tasks

**Status:** decided. Nothing waits on it.

**How it would be built** is [B14-Talking-Between-Tasks_design.md](B14-Talking-Between-Tasks_design.md).

Two running tasks cannot say a word to each other: each is a container with its own network
namespace, a deny-by-default ruleset and a resolver that answers only for declared names. That
isolation is the product. The request is a channel through it that a person can see, join, hold and
cut.

## What a message channel is

- **An egress path.** A task that can send text to another task can send its workspace, one message
  at a time, past the gate — the same shape as B13 ([index](README.md)), except that this door would
  be built on purpose.
- **An ingress path, and the worse half.** An agent acts on what it reads, and nothing in a container
  makes a model treat a sentence as data. Sokar can guarantee that a message is recorded, attributed
  and delivered as content from another party. It cannot guarantee that the receiving model does not
  obey it.
- **A bridge between security classes.** An `offline` task that can ask an `online` one has reached
  the network without doing anything forbidden. The reachable class of a conversation is the highest
  class in it.

## The shape

- **A mailbox, and nothing else inside the container.** One directory at `/run/sokar/mail`, shaped
  like a mail client's: `inbox/{tmp,new,cur}`, `outbox/{tmp,new}`, `sent/`. Writing is creating a
  file in `tmp/` and renaming it into `new/`; reading is renaming into `cur/`. No git, no socket, no
  credential, no network, and nothing to learn beyond two verbs.
- **The mailbox is the task's and lives exactly as long as the task.** It is created with the task
  and destroyed only when the task is removed, so a stop, a start and a machine restart leave the
  conversation where it was. That puts it in the task's durable state, never under the runtime
  directory the system clears at boot.
- **Transports are packages on the host**, found and described the way agents are. The first carries
  messages between mailboxes on one machine by moving a file; carrying them between machines through
  a git repository is a later extension. Which one a peer uses is the host's business.
- **A peer is a name.** The container never sees an address of the outside world, and cannot learn
  one.
- **Every message is an A2A message narrowed to a closed schema, signed by the host** with a detached
  signature over its exact bytes. The task never holds a key: a key it can reach is a key it can
  copy.
- **What a message may contain is decided by the message sluice**, before anything is queued for a
  transport — the narrowed schema, encoded payloads, credentials, personal data, and a payload spread
  over several messages. It lives in its own repository and carries no model.
- **No model decides anything.** The rules accept or refuse; a person holds. A classifier may be
  added later, and even then it may only hold a message, never release one.
- **Trust is a property of the peer, not of the transport.** One of the operator's own machines is
  vouched for; anybody else is external and has their messages checked on the way in too; anything
  that cannot be attributed is held for a person.
- **The record is a hash-chained log on each host**, beside the signed messages. It is tamper-evident
  locally, and its head can be published later if it ever has to convince somebody who does not trust
  the operator.

## Acceptance

**The mailbox**

- A task has one mailbox, mounted at a fixed path, and it is the only way in or out for messages.
- The mailbox is created with the task and destroyed with it. Stopping, starting, resuming and
  restarting the machine leave everything in it untouched.
- A message is complete when it has been renamed into place: no reader ever sees a partial file, and
  nothing is deleted behind the agent's back.
- What was actually sent is written back into `sent/`, so a task that comes back reads its own half
  of the conversation.
- Removing a task that still holds an unsent or undelivered message is refused and says what is
  pending; `--rescue` hands those messages over first, `--force` removes regardless.

**What leaves a machine**

- **Nothing is queued for any transport that the sluice did not accept**, so what is checked is
  answerable by where a file is rather than by a flag.
- The bytes that leave are the bytes the agent wrote: nothing between the mailbox and the transport
  rewrites a message, because the signature is over those bytes.
- Every message carries a detached signature made on the host, and **no key is placed inside a task
  container for any of this**.
- A message is an A2A message narrowed to the closed schema, and anything else — including a property
  the schema does not name — is refused.
- A refusal reaches the sending agent as a message in its own inbox, in the format it already reads,
  naming the rule, the part and the offset, with nothing a rule matched in clear text.
- A task's limits are per peer and in both directions: beyond them a sender is refused with a reason
  rather than queued, because the work a message causes is paid for by the receiving project.
- **In a `guarded` project nothing a person has not read leaves the machine by default.** `allow` or
  `off` needs the project to opt in with the one setting that says unread work may leave, which is
  the same setting B13 ([index](README.md)) asks before unreviewed work goes to a review branch.

**What arrives**

- A message signed by a key listed for a **vouched** peer is delivered after its attribution is
  checked; that machine already checked its content.
- A message from an **external** peer is delivered only after the sluice has read it as well.
- A message that is unsigned, signed by a key not listed for its peer, or not a valid message
  **reaches no task**: it is held, the operator is told, and nothing is deleted to hide it.
- Inside a group, the keys that may speak for a peer come from a directory signed by an operator key;
  a peer outside it is confirmed once by a person, and a changed key is held and asked again.
- No order is promised, and a message whose id was already delivered to that mailbox is dropped, so
  a transport that retries cannot make an agent act twice on the same instruction.
- A message reaches the agent as content from another party, never through the channel that carries
  the operator's instruction.

**What a person controls**

- A group is a list of peers on the host; sending to it reaches every member over whatever transport
  each uses.
- A group can be held, released and closed while it runs; a held message reaches no reader and its
  sender is told.
- A held message is released or refused, never edited. What the record shows a task said is what the
  task said.
- The moderation modes are `clearance`'s four — `prompt`, `allow`, `deny`, `off` — with the same
  meanings, readable back.
- A person can write into a conversation, signed with their own key, and is distinguishable from an
  agent in the stream, in the record and in what the receiving agent is shown.
- One subscription over the daemon's socket carries every mailbox on the machine.
- `sokar talk verify` walks the record and names the first entry whose chain or signature does not
  check out.

**What it must not widen**

- Adding a transport adds nothing a container can reach: no port, no firewall element, no resolver
  entry, no directory shared between two tasks. The container reaches one mailbox and no network.
- A transport adapter runs on the host as its own user, with its credential from the vault and the
  hosts it may reach declared — never inside a task container, and never in the same process as the
  sluice.
- Refused originals are readable only by the account that runs Sokar, and live as long as the task
  whose mailbox they belong to.
