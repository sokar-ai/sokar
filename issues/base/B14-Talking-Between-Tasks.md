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
  and delivered as content from another task. It cannot guarantee that the receiving model does not
  obey it.
- **A bridge between security classes.** An `offline` task that can ask an `online` one has reached
  the network without doing anything forbidden. The reachable class of a conversation is the highest
  class in it.

## The shape

- **Transport: a git repository, one branch per group, one file per message.** Agents already commit,
  push and pull; the commit graph is the record; history outlives every task that wrote to it. The
  repository that distributes is a **bare repository on the same machine** where every agent is on
  one machine, and a remote — including a hosted forge — where they are not. It is one address either
  way, and only the gate on the host ever reaches it.
- **Every check runs on the machine that writes the message**, before anything is pushed. Nothing
  runs upstream: the repository that distributes is storage, not a checkpoint.
- **What a message may contain is decided by the message sluice**, a tool of its own that carries no
  model: the narrowed schema, encoded payloads, credentials, personal data, and a payload spread over
  several messages of the same group.
- **Authorship: stated and signed by the gate, on the host.** The gate knows which task pushed and
  writes the message into a commit of its own. The agent never signs: a key it can reach is a key it
  can copy, and it would be the first credential inside a task container.
- **Format: an A2A message narrowed to a closed schema.** A fixed set of kinds, one text part within
  a size limit, structured data fixed by the kind, no files, and work named by commit rather than
  carried. It bounds how much a message and a group can carry without a model deciding anything; it
  does not bound what the text means.
- **A message arrives by polling.** The daemon binds no network interface, so nothing can be pushed
  to it: one timer per machine fetches every group's branch.
- **Nothing is re-checked on the way in.** A machine vouches for what it wrote. What a fetching gate
  still decides is whether a message can be attributed at all.
- **A branch separates writing, not reading.** A task reads only its own group because the gate's
  copy for it holds nothing else. Every machine that fetches the repository can read every group, so
  anything that must be kept from the operator's other machines needs a repository of its own.

## Acceptance

**Who may talk**

- A message is refused unless **every** project in its group declares the group in `project.yml`,
  edited in place as text and parsed before it is written.
- An `offline` project takes part in nothing, and a group spanning two security classes is refused.
- **A task belongs to exactly one group.** A project that needs two conversations runs two tasks, and
  what passes between them passes through a person.
- An agent that cannot take part says so, and nothing is left for it unread.

**What leaves a machine**

- Messages travel only as commits on a group's branch, reached only through the gate, **whatever the
  project's class**.
- An agent's own commit never leaves the machine. The gate writes the message into a commit that
  names the task the push came from, and signs it with a key held on the host.
- **No key is placed inside a task container for any of this.**
- A message is an A2A message narrowed to the closed schema, and anything else — including a property
  the schema does not name — is refused.
- **Nothing reaches the branch that the message sluice did not accept.** It runs on the writing
  machine, before the gate signs anything, and a sluice that cannot start, cannot be configured or
  cannot run means nothing leaves.
- A refusal reaches the sending task as the sluice wrote it — the rule, the part, the offset — with
  nothing a rule matched in clear text.
- Only the rules that are not a model can accept a message. A classifier can hold one, and a
  classifier that fails, times out or answers out of bounds holds.
- A gate never force-pushes a group branch and never deletes one; where the distributing repository
  can refuse both itself, it is configured to.

**What arrives**

- One timer per machine fetches every group's branch, at a configurable interval defaulting to a
  minute, whether or not a task is running.
- A fetched message is **not** checked for what it contains: the machine that wrote it did that.
- A fetched message that is unsigned, signed by a key the group does not list, or not a valid message
  **is not delivered to any task**, and is reported to the operator with the reason. It stays on the
  branch, so nothing is hidden by not delivering it.
- A message reaches the agent as content from another task, never through the channel that carries
  the operator's instruction.
- A task can fetch its own group's branch and nothing else: the gate's copy for that task contains no
  object of any other group.

**What a person controls**

- A group can be held, released and closed while it runs; a held message reaches no reader, and the
  sender is told it was held. Each machine enforces this for its own tasks, from the group's control
  state at the branch tip.
- A held message is **released or refused, never edited**. What the record shows a task said is what
  the task said.
- The moderation modes are `clearance`'s four — `prompt`, `allow`, `deny`, `off` — with the same
  meanings, readable back.
- **In a `guarded` project nothing a person has not read leaves the machine by default**, so its
  groups are `prompt`. `allow` or `off` needs the project to opt in with one setting in `project.yml`,
  which says that unread messages may leave, and which is the same setting B13 ([index](README.md))
  asks before unreviewed work goes to a review branch on a forge.
- A person can write into a group, signed with their own key, and is distinguishable from an agent in
  the stream, in the record and in what the receiving agent is shown.
- One subscription over the daemon's socket carries every group on the machine, including groups
  joined later.
- `sokar talk verify` walks a group's branch and names the first commit whose signature does not
  verify against the keys allowed for that group at that time.

**What it costs the receiver**

- A group has a turn budget. Exhausting it closes the group and says so.
- **A project declares how many incoming turns it accepts**, per group, in its own `project.yml`.
  Beyond that, a message is refused to its sender rather than queued: work in a receiving task is
  paid for by that project's provider and credential, and joining a group is not handing the budget
  over.

**What it must not widen**

- Nothing here widens what a container may reach: no port, no firewall element, no resolver entry, no
  directory shared between two tasks. The distributing repository is reached by the gate on the host.

## To be checked

- **What the correlation across messages can see.** The sluice catches a payload spread over several
  messages by reading what that machine already let through. A sender spreading its pieces across two
  machines is seen by neither.
- **Whether dialogue at poll cadence is enough.** It is for handing work over and asking questions; it
  is poor for fast back-and-forth, which was the one thing that justified a channel beside the gate.
- **How a machine that ignores the group's control state is noticed.** Held, closed and over budget
  are kept by each machine for its own tasks, so the record is where a breach shows up, and only
  afterwards.
- **The classifier: whether it runs at all, under what licence, and at what threshold.** It can only
  hold a message, it runs on the writing machine before the push, and none of the three is settled.
