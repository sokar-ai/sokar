# B15 — Handing Artifacts Between Tasks

**Status:** later.

**What must be true.** What a task builds can reach another task of the project wherever that task
runs, carried by the project's message transport beside its messages, addressed by content,
recorded on the host, and never reachable from inside a task. A
project hands artifacts only once its project file switches it on, and a task can be started with it
switched off; a project without a message transport hands none. These are intermediate results between tasks
that are working, never the work's result, which is built elsewhere, on the build server.

## Why

Neither a repository manager nor Git LFS: what is left is smaller than both, because most of it is
already built and called the gate, and the rest is carried by the message transport a project
already has.

**How it would be built** is
[B15-Handing-Artifacts-Between-Tasks_design.md](B15-Handing-Artifacts-Between-Tasks_design.md).
Nothing in it exists or has been measured, and it is written for one machine with podman's store as
the storage: it has to be redone around the message transport below before it is built.

One task produces something another needs: a built binary, a dataset, a trained model, a coverage
report, a screenshot of a failure. Today there is no path at all. A task's workspace is inside its
container and stays there on purpose, and the only things bind-mounted into a container are the
vault and ssh sockets. The conditions are [B14](B14-Talking-Between-Tasks.md)'s: it must be
controlled, visible to a person, recorded, restrictable and stoppable.

## Most of this is answered, and the answer is the gate

Before designing anything: a task already has a way to hand its work over. It pushes a branch to the
project's mirror on this machine, the work waits under `refs/sokar/incoming/<task>`, a person reads
it, and `sokar gate approve` is what forwards it. That is versioned, content-addressed,
deduplicated, diffable, reviewed before it moves, host-local, and it needs no credential inside the
container.

**If the work product is source, this requirement has nothing to add.** Anything that is text, that
a person should look at, and that belongs to the repository, is a branch — and building a second
path to the same place would be building a weaker one.

Where that stops working is specific and worth stating as a threshold rather than a feeling: git
carries every version of every blob forever, in every clone. A few megabytes is nothing. A hundred
megabytes of build output, regenerated on each run, permanently enlarges a repository that a person
will clone for years, and no amount of packing takes it back out.

## What an artifact is that a branch is not

Big, opaque, regenerable — and the property that matters most:

**An artifact is content another agent will execute.** This is worse than a message, and the
difference is not one of degree. A message is text that a model may or may not act on; a wheel, a
jar, a container image or a `node_modules` tree is something the next build runs without anybody
deciding anything. One compromised or merely careless task, one artifact, and the second task is
running the first one's code. That is a supply chain between two things that
were isolated from each other on purpose.

**It carries what a message carries, with a far bigger payload.** A conversation leaks at
conversation bandwidth. One put is the entire workspace in a single operation, and it goes wherever
the project's conversation goes.

**And nobody reviews a binary.** This has to be said plainly, because a design that implies
otherwise sells something it cannot deliver. What a person can approve is the *provenance* — which
task made it, in which project, from which commit, how large it is, and what it claims to be — never
the bytes. Every guarantee below is built on recording provenance well, and none of it is a
substitute for someone having read a diff.

## Why not a repository manager

Artifactory, Nexus, a central file store: **no**, neither as something a task publishes to nor as a
second storage behind the host.

**A task publishing to one** puts the storage's credential in the path, needs a new declared host in
every participating project's egress set, and is exactly the side door
[B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) is about - built in on purpose and reviewed by
nobody.

**A second storage behind the host** would avoid those, and would still be a second thing to run,
authenticate and reach from every machine of the project, beside the message transport that already
reaches all of them with accounts whose secrets stay on the host. One way between a project's
machines is enough.

**And a result is not this requirement's business.** What a consumer outside Sokar - CI, a
colleague, a deployment - takes is built on the build server from the approved work, and published
from there to whatever repository manager the organization runs. What a task hands another task here
is an intermediate result, used while both are working.

## Why not Git LFS

This one deserves a fact rather than a preference, because the name is misleading.

**LFS does not put the bytes in git.** It puts a small pointer file in git and the bytes on an LFS
server, reached over HTTPS with its own authentication. So "versioned in Git LFS" is the repository
manager above in a git costume: a second service to run, a second credential to keep out of the
container, a second host in every egress set.

**And here it costs something specific: the mirror stops being complete.** Today the mirror on the
host holds everything an agent produced, which is what makes reviewing it possible with no network
at all — and is the whole basis of the `offline` class. With LFS the mirror holds pointers, a
review has to fetch, and an `offline` project cannot use it in any form.

**Its failure is also silent.** A checkout without the client gives a hundred-odd bytes of text
where a binary was expected, and nothing reports an error. A guard that presents as a corrupt file
is a guard people learn to work around.

## What is left

**The project's message transport carries artifacts beside its messages, never inside one.** A
message stays what it is - one text part, no file part, within the transport's `max_bytes`, read by
the filter - and an artifact is not text the filter can read. But a transport that keeps a project's
conversation already has what handing a file needs: accounts per task whose secrets stay on the
host, a place every machine of the project reaches, and a way to announce something with the host's
signature beside it. A conversation with no server declared is kept on this machine, and an
`offline` project's reaches nothing beyond it, so one machine is not a special case.

**The artifact verbs are the message transport's contract grown by a few** - put, fetch, remove,
list - which a transport offers or does not, announcing in `describe` its own limit on an artifact's
size. Sokar knows the verbs and nothing about the system behind them: which messaging system that is,
how it stores bytes and how large it lets them be is the transport package's business, and the half
that speaks to one system lives in that transport's repository. A message may announce an artifact by
its digest without carrying it. **A transport never answers across projects**: content addressing
across projects is a covert channel by construction - one project could probe for another project's artifact by guessing its digest, and a hit is itself the answer.

What stays Sokar's, whatever the transport:

- **Taking an artifact out of a task**, through a directory of that task's own that only its own
  helper reads, never a directory two tasks share. A writable directory shared between containers is
  a channel nobody designed: no record of who wrote what, no size limit, no way to interrupt.
- **The checks at the moment of taking**: a regular file only, its size counted against the limit
  per artifact as the bytes are copied, the threshold, the digest. How much a project keeps is the
  messaging system's business, since it decides what it keeps and for how long.
- **The signature.** An artifact is signed on the host that took it, over its digest, with this
  machine's signing key, and a receiving host checks it against the keys listed for the peer before
  anything reaches a task - the guarantee messages already have.
- **Whether it happens at all**: off unless the project file switches it on, and a task can be
  started with it off where the project has it on, never the other way round. One switch covers
  handing on and receiving.
- **The policy**: an artifact stays in its project and travels only in the project's own
  conversation, never in a direct chat ([B122](B122-No-Direct-Chat-Between-Tasks.md)), so the
  operator, who is in that conversation, can see what was handed. The project declares what its
  tasks accept, and refusals are outcomes rather than exceptions.
- **The record**, written before the other side can see anything: every artifact a task put or
  received - name, digest, size, media type and the commit it came from - listed for a person who
  asks. The gate shows the diff and nothing else, since an intermediate result is not the work.
  Nothing about an artifact is committed into the work: a pointer file in git would name bytes a
  clone elsewhere may not reach, and the record already ties each artifact to its task and commit.
- **Delivery into a task**, read-only and never executable, into a place of that task's own.

## Notes

**The relationship to [B14](B14-Talking-Between-Tasks.md).** The same conversation, the same
transports and the same split between Sokar and a transport, with a different payload; unlike a
message, an artifact never crosses into another project. The record is this requirement's own.

**This is the more useful of the two and the more dangerous.** Handing a built binary between tasks
is a thing people will want immediately; it is also the one that turns two isolated containers into
a supply chain. That ordering — more useful, more dangerous — is the argument for doing it second
rather than first, not for doing it quietly.

**Sokar has no scanner and should not grow one.** Nothing here inspects an artifact for what it
does. The guarantees are provenance, non-execution by default, declaration by the consumer, and a
record — not safety of the content. Saying so is better than a check that catches the careless case
and teaches people to trust it for the deliberate one.

## Acceptance

- An artifact leaves a task through a channel Sokar owns. **No directory is shared between two
  tasks, and no storage is mounted into one or reachable from it.** Seen to fail: a test that
  inspects a task's mounts goes red when a storage, or a directory another task writes, is among
  them.
- **Artifacts go only through the project's message transport, and Sokar names no messaging
  system.** No code outside a transport knows one or how it stores bytes, and a second transport
  adds nothing to `sokar`. Seen to fail: a stand-in message transport offering the artifact verbs,
  written for the test, needs a change in `sokar` to carry an artifact between two tasks.
- A project whose message transport does not offer the artifact verbs, or that has none, is refused a
  hand-over, naming why. Seen to fail: such a hand-over waits, or is refused without the reason.
- An artifact is signed on the host that took it and checked against the peer's listed keys before it
  reaches a task. Seen to fail: a stand-in transport that hands back bytes with no signature, or with
  another key's, has them delivered.
- No transport credential enters a task: it is kept in the vault and handed to the transport on the
  host. Seen to fail: a search of a task's environment, files and process arguments finds it.
- Identity is the content — `sha256` — so a second put of the same bytes is the same artifact, and
  the digest is what every record and message names. Seen to fail: two puts of the same
  bytes yield two artifacts.
- Deduplication is within a project. Two projects cannot learn anything about each other's
  artifacts, including by asking for a digest. Seen to fail: a request from one
  project for another's digest answers differently from a request for a digest that exists nowhere.
- Nothing about an artifact is committed into the work, and the gate shows only the diff. A person
  who asks is shown, from the record, every artifact a task put or received - name, digest, size,
  media type and the commit the workspace was at. Seen to fail: an artifact a task put or received is
  missing from that listing, or a file naming it appears in the work.
- An artifact from a `guarded` task goes to the transport when it is taken, as the task's messages
  do - signed and recorded first - without waiting for the task's work
  to be approved. It is not filtered, and that is said where a person reads what `guarded` keeps on
  the machine. Seen to fail: a `guarded` task's artifact waits for approval, or the statement is
  missing.
- An `offline` project's artifacts stay on this machine, as its messages do. Seen to fail: an
  `offline` project's artifact reaches anything its conversation may not.
- Provenance is recorded at the moment of the put — the task, the project, and the commit the
  workspace was at — because it cannot be reconstructed afterwards. Seen to fail: a put whose record
  lacks the task, the project or the commit.
- An artifact is delivered **read-only and never executable**, whatever mode it was written with,
  and the project **declares what its tasks accept** rather than discovering what exists. Seen to
  fail: an artifact put with mode 0755 arrives writable or executable, or one the project did not
  declare arrives at all.
- Handing files is off unless the project file switches it on. A task can be started with it off in
  a project that has it on, and then neither hands on nor receives; nothing switches it on for a
  task whose project has it off. Seen to fail: a project whose file says nothing hands or receives an
  artifact, a task started with it off does either, or a task start switches it on against its
  project.
- An artifact never leaves its project: it travels only in the project's own conversation, never to
  another project and never in a direct chat, and an attempt is refused, naming why. Seen to fail: a
  hand-over to another project's task, or one sent into a direct chat, arrives.
- Every put, every hand-over and every removal is in a plain record on the host that outlives both
  tasks, not chained, and written **before** the artifact is visible to the other side. Seen to
  fail: the record is gone after both tasks are removed, or the other side sees the artifact before
  the entry exists.
- A file larger than the project's threshold is refused at the gate when it arrives as a git blob,
  naming the threshold, which has a default a project file can change. Seen to fail: a push carrying
  a blob over the threshold waits for review, or the refusal does not name the number.
- How long an artifact is kept is the transport's business: Sokar removes nothing on a timer and
  cleans nothing up. A project file may set a lifetime per artifact, which Sokar passes with every
  put; a transport that announces in `describe` that it keeps one applies it, and for one that does
  not, checking the project file says the lifetime has no effect there. Seen to fail: Sokar removes
  an artifact nobody withdrew, a set lifetime is not passed with a put, or a transport that cannot
  keep one is not named when the project file is checked.
- An artifact has a size limit, set in the project file and never above what the transport announces
  in `describe`. Exceeding it is a refusal naming the number, not a warning, enforced against the
  bytes as they are copied rather than against what a file claims to be. There is no quota per
  project. Seen to fail: an artifact over the limit is handed on, or the refusal does not name the
  number.
- Nothing that is not a regular file is taken. A symbolic link in a drop directory is refused, not
  followed. Seen to fail: a symbolic link in a drop directory is stored or followed.
- A person can see what exists, where each came from, and can withdraw one. Withdrawing removes it
  from the transport and records it, so no task receives it afterwards; a task that already has it
  keeps it, and the withdrawal names those tasks. It is never called a revocation. Seen to fail: a
  task receives a withdrawn artifact, or a withdrawal of one that was handed over does not name the
  tasks that received it.
- Nothing here widens what a container may reach: no port, no firewall element, no resolver entry,
  no new host in any egress set. Seen to fail: a task's ports, firewall elements, resolver entries
  or egress set differ with this in use.

## To be checked

- **The artifact verbs**: their arguments, what `describe` says about them, and their exit codes -
  settled by writing one transport that crosses machines against them.
- **What a message transport can carry**, measured per transport rather than assumed:
  the largest artifact its system accepts, what keeps the bytes and for how long, and who can read
  them there. Each transport answers this for itself in `describe` and in its own documentation.
