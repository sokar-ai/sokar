# B15 — Handing Artifacts Between Tasks

**Status:** open. The answer to the question as asked is *neither* — not a repository manager, not
Git LFS — and what is left is smaller than both, because most of it is already built and called the
gate.

**How it would be built** is
[B15-Handing-Artifacts-Between-Tasks_design.md](B15-Handing-Artifacts-Between-Tasks_design.md).
Nothing in it exists or has been measured.

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
running the first one's code. That is a supply chain, inside one machine, between two things that
were isolated from each other on purpose.

**It carries the class escalation of [B14](B14-Talking-Between-Tasks.md) with a far bigger payload.**
A conversation leaks at conversation bandwidth. One put is the entire workspace in a single
operation, and if the receiving project is `online`, it has left the machine.

**And nobody reviews a binary.** This has to be said plainly, because a design that implies
otherwise sells something it cannot deliver. What a person can approve is the *provenance* — which
task made it, in which project, from which commit, how large it is, and what it claims to be — never
the bytes. Every guarantee below is built on recording provenance well, and none of it is a
substitute for someone having read a diff.

## Why not a repository manager

Artifactory, Nexus, or anything of that shape: **no**, and the reasons are the product's own.

**It is off the machine.** `guarded` means unreviewed work does not leave this machine. An artifact
repository is an upstream wearing a different name, and publishing to it from inside a task is
exactly the side door [B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) is about — except
built in on purpose and reviewed by nobody.

**It puts a credential in the path.** The rule that the real credential never enters a task
container would need a second broker for a second protocol. That is buildable — the vault proxy is
precisely that shape — but it is a second one, with a second set of ways to get it wrong.

**It needs egress, permanently.** A new declared host in every participating project's egress set,
a `server=` line in each resolver and an element in each ruleset, so that two tasks on the same
machine can hand each other a file. The transfer is host-local in the ordinary case, and this makes
it a network operation.

**And it is an operator dependency.** Today the list is podman, nftables, dnsmasq and git. "And a
repository manager" is a different product with a different installation story.

**Where it is genuinely right**: when the consumer is outside Sokar — CI, a colleague, a deployment
— and the organization already runs one. Then it belongs *after* the gate, as the operator's own
publish of something already approved, in the same position `approve` occupies for a branch. Sokar
should not reimplement a repository manager. It should make sure nothing reaches one without
passing the approval first.

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

**A per-project store on this machine, addressed by content, written through the channel the
product already has, with the pointer in git.**

That last part keeps the good half of the LFS idea and drops the server. A small committed file
naming the artifact — name, digest, size, media type — is versioned, diffable, and reviewed at the
gate exactly like every other change, so *that an artifact changed* is visible in a diff even though
its content is not. The bytes sit in a store on the same machine, so nothing about review needs a
network and `offline` stays usable.

**Written through a socket and a per-task directory, never a directory two tasks share.** A
writable directory shared between containers is a channel nobody designed: no record of who wrote
what, no size limit, no way to interrupt, and it is the mistake B14 already names in the reference
implementation. Each task writes into its own, which only its own helper reads.

**Never mounted as a filesystem into a task.** A task that can list the store can enumerate what
every other task made. What arrives in a task arrives because policy put it there.

**Per project, and deduplicated only within a project.** Content addressing across the whole machine
would be a covert channel by construction: a project that never declared a peer could probe for
another project's artifact by guessing its digest, and a hit is itself the answer. Paying for the
duplicate disk is the cheaper side of that trade.

**Handed across projects only by a deliberate, recorded act**, with B14's policy shape: both
projects declare the pair, the class rules apply, refusals are outcomes rather than exceptions, and
the record is hash-chained and written before the other side can see anything.

## Acceptance

- An artifact leaves a task through a channel Sokar owns. **No directory is shared between two
  tasks, and the store is never mounted into one.**
- Identity is the content — `sha256` — so a second put of the same bytes is the same artifact, and
  the digest is what every record, pointer and message names.
- Deduplication is within a project. Two projects that never declared a pair cannot learn anything
  about each other's artifacts, including by asking for a digest.
- What is committed is a pointer: name, digest, size and media type. An artifact's history is git's
  history, and a change to one is visible at the gate in a diff.
- Everything needed to review an artifact is on the machine that produced it, with no network. The
  mirror is never left incomplete.
- Provenance is recorded at the moment of the put — the task, the project, and the commit the
  workspace was at — because it cannot be reconstructed afterwards.
- An artifact is delivered **read-only and never executable**, whatever mode it was written with,
  and the consuming project **declares what it accepts** rather than discovering what exists.
- Handing across projects is refused unless both projects declare the pair, and refused by class in
  the way `SetEgress` already refuses one. An `offline` project takes part in nothing.
- Every put, every hand-over and every removal is in a record that outlives both tasks, chained so
  that an entry changed or removed after the fact stops verifying, and written **before** the
  artifact is visible to the other side.
- A project has a quota. Exceeding it is a refusal naming the number, not a warning, and it is
  enforced against the bytes as they are written rather than against what a file claims to be.
- Nothing that is not a regular file is stored. A symbolic link in a drop directory is refused, not
  followed.
- A person can see what exists, where each came from, and can withdraw one — and withdrawing says
  what still points at it.
- Nothing here widens what a container may reach: no port, no firewall element, no resolver entry,
  no new host in any egress set.

## Notes

**The relationship to [B14](B14-Talking-Between-Tasks.md).** Same policy shape, same refusals, same
class rules. If B14 is built, this is largely its `TalkPolicy` reused with a different payload, and
the two should share it rather than growing two versions of one decision. The record is not
shared: B14 records in a hash-chained log on each host, and this journal stands on its own
unless this requirement moves to the same construction, which is worth deciding before either is
scheduled.

**This is the more useful of the two and the more dangerous.** Handing a built binary between tasks
is a thing people will want immediately; it is also the one that turns two isolated containers into
a supply chain. That ordering — more useful, more dangerous — is the argument for doing it second
rather than first, not for doing it quietly.

**Sokar has no scanner and should not grow one.** Nothing here inspects an artifact for what it
does. The guarantees are provenance, non-execution by default, declaration by the consumer, and a
record — not safety of the content. Saying so is better than a check that catches the careless case
and teaches people to trust it for the deliberate one.

## To be checked

- **Who writes the pointer.** If the agent writes and commits it, the record depends on the agent
  cooperating, and a run that forgets leaves bytes in the store that no commit refers to. If Sokar
  writes it, Sokar is writing into a workspace it deliberately does not touch. The store's own
  record has to be authoritative either way; what is undecided is whether the committed pointer is a
  convention or a guarantee.
- **What withdrawal means once the other side has it.** Bytes cannot be un-given. Removing the
  artifact from the store and recording the withdrawal is honest; calling it a revocation is not.
- **The threshold.** At what size something stops being a git blob and becomes an artifact. It
  should be a number that refuses, with the number in the refusal, rather than a rule of thumb —
  otherwise the answer is decided by whoever is in a hurry.
- **Retention.** A store nothing ever removes is a disk-full outage that presents as "podman cannot
  start a container", which is a miserable way to learn about it. Whether artifacts expire, and
  whether an expiry may remove something a commit still points at, is undecided.
- **Whether an artifact should be reachable at all after the task that made it is gone.** It
  outliving the container is the point; it outliving the *project* is a different question, and the
  clearance journal's answer — named by the container, so a decision belongs to one run — may not be
  the right one here.
- **Whether the same machinery should carry the `online` case.** Publishing an approved artifact to
  a repository manager after the gate is the operator's act, not a task's, and it may belong in
  `sokar gate approve` rather than here. Naming it now so it is not discovered as a surprise later.
