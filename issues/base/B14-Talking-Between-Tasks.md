# B14 — Talking Between Tasks

**Status:** open, and the first question is whether to build it at all. Nothing waits on it.

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
  and delivered as content from another task. It cannot guarantee that the receiving model does not
  obey it.
- **A bridge between security classes.** An `offline` task that can ask an `online` one has reached
  the network without doing anything forbidden. The reachable class of a conversation is the highest
  class in it.

## The shape

- **Transport: a git repository served by the gate.** One branch per group, one file per message.
  Agents already commit, push and pull; the commit graph is the record; history outlives every task
  that wrote to it; and machines reach each other through a forge, so the daemon still binds no
  network interface.
- **Authorship: stated and signed by the gate, on the host.** The gate knows which task pushed and
  writes the message into a commit of its own. The agent never signs: a key it can reach is a key it
  can copy, and it would be the first credential inside a task container. A signature is needed at
  all because the record leaves the machine, where nobody can see which task a push came from.
- **Format: an A2A message narrowed to a closed schema.** A fixed set of kinds, one text part within
  a size limit, structured data fixed by the kind, no files, and work named by commit rather than
  carried. It bounds how much a message and a group can carry without a model deciding anything; it
  does not bound what the text means.
- **The check runs upstream of every reader, where no machine can skip it**: as the forge's own
  pre-receive hook where the forge runs one, and otherwise on a self-hosted filter in front of the
  forge — plain git over ssh with Sokar's binary as the hook, the only writer the forge accepts, on a
  host of its own or on the one machine there is. Which of the two is one address in the gate's
  configuration.
- **Rules decide; a classifier can only hold.** Signature, group membership and class are decided
  here. **What a message contains is decided by a tool of its own, the message sluice**, which is not
  a model: the narrowed schema, encoded payloads, credentials, personal data, and a payload spread
  over several messages. It lives in its own repository, reads a directory and writes a directory,
  and refuses the same way every time. A prompt-injection classifier runs after all of it, in a
  container of its own, and can only hold a message for a person. It is not containment: a model that
  means to get past it can.
- **A branch separates writing, not reading.** A task reads only its own group because the gate's
  copy for it holds nothing else. Every machine that fetches the repository can read every group, so
  anything that must be kept from the operator's other machines needs a repository of its own.

## Acceptance

- A message is refused unless **every** project in its group declares the group in `project.yml`,
  edited in place as text and parsed before it is written.
- An `offline` project takes part in nothing, and a group spanning two security classes is refused.
- Messages travel only as commits on a group's branch of the talk repository, reached only through
  the gate, **whatever the project's class**.
- An agent's own commit never leaves the machine. The gate writes the message into a commit that
  names the task the push came from, and signs it with a key held on the host.
- **No key is placed inside a task container for any of this.**
- A message is an A2A message narrowed to the closed schema, and anything else — including a property
  the schema does not name — is refused by the gate and again by the check.
- The check refuses a commit on a group branch that is unsigned, signed by a key not allowed for that
  group, not a fast-forward, or anything but one message file.
- A force push to, and the deletion of, a group branch is refused.
- Every message is checked before any reader can fetch it: by the forge's pre-receive hook, or by a
  self-hosted filter that is the only writer the forge accepts. The choice is one address in the
  gate's configuration on the host, never in a container.
- Only the rules that are not a model can accept a message. A classifier can hold one, and a
  classifier that fails, times out or answers out of bounds holds.
- **What a message contains is judged by the message sluice**: at the gate before anything is signed,
  and again where the check runs upstream. It carries no model, and its refusal names the rule, the
  part and the offset.
- Nothing reaches the talk repository that the sluice did not accept. The branch is pushed from the
  directory the sluice writes, never from the one a task pushed into.
- A sluice that cannot start, cannot be configured or cannot run holds everything: nothing leaves the
  machine.
- A refusal reaches the sending task as the sluice wrote it, with nothing a rule matched in clear
  text. A refusal that quoted the secret back would carry it out itself.
- The classifier runs in a container with no network, a read-only root filesystem and no credential,
  never in the process that forwards to the forge.
- The filter runs as a user of its own, not as a Sokar task, and holds the forge credential where no
  task's account can read it.
- A task can fetch its own group's branch and nothing else: the gate's copy for that task contains no
  object of any other group.
- `sokar talk verify` walks a group's branch and names the first commit whose signature does not
  verify against the keys allowed for that group at that time.
- One subscription over the daemon's socket carries every group on the machine, including groups
  joined later.
- A person can write into a group, signed with their own key, and is distinguishable from an agent in
  the stream, in the record and in what the receiving agent is shown.
- A group can be held, released and closed while it runs; a held message reaches no reader, and the
  sender is told it was held.
- The moderation modes are `clearance`'s four — `prompt`, `allow`, `deny`, `off` — with the same
  meanings, readable back.
- **In a `guarded` project nothing a person has not read leaves the machine by default**, so its
  groups are `prompt`. `allow` or `off` needs the project to opt in with one setting in `project.yml`,
  which says that unread messages may leave, and which is the same setting B13 ([index](README.md))
  asks before unreviewed work goes to a review branch on a forge.
- A group has a turn budget. Exhausting it closes the group and says so.
- A message reaches the agent as content from another task, never through the channel that carries
  the operator's instruction.
- Nothing here widens what a container may reach: no port, no firewall element, no resolver entry, no
  directory shared between two tasks. The filter and the forge are reached by the gate on the host.
- An agent that cannot take part says so, and nothing is left for it unread.

## To be checked

- **Whether a task may be in two groups.** It can carry what it read in one into the other.
- **Whether a held message may be edited**, or only released, refused or answered. A signed message
  cannot be edited without becoming the person's.
- **Who is charged for a turn.** A message causes work in the receiving task against that project's
  provider and credential.
- **Whether a hosted forge may store the conversations at all.** Behind a filter it still holds every
  one of them in plaintext; a filter that forwards nowhere is a valid configuration.
- **Where the sluice runs: at the gate, upstream, or at both.** At the gate a message is refused
  before it leaves the machine, but the machine being trusted to check is the one that may be
  compromised. Upstream every machine is checked by the same instance, but by then the message has
  travelled. Both is the safe answer and costs the same catalogue kept in two places.
- **What the chunking check can see from where it runs.** It catches a payload spread over messages
  by reading what it already let through, so a gate sees one machine and the instance upstream sees
  all of them. The two placements do not catch the same thing.
