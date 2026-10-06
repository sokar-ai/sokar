# B39 — Handing A File To A Running Task

**Status:** now.

**What must be true.** A file on this machine can be put in front of a running task, once, without
going through a repository, without landing in the work, and without the task gaining any way to
send one back.

## Why

Split out of [B37](B37-The-Build-That-Runs-Somewhere-Else.md), because a build log is one instance
of a need that is not about build servers at all: **something has to be able to reach a task while
it is running, and not everything goes through a git repository.** Distinct from
[B15](B15-Handing-Artifacts-Between-Tasks.md), which is task-to-task and about large opaque things
another task will execute; this is the operator, or a helper of Sokar's, handing in one file that
is going to be *read*.

## The need, without the build server

A task is running. Somebody - a person at the machine, or something on the host acting for them -
has a file the work needs:

- the log of the build that just failed ([B37](B37-The-Build-That-Runs-Somewhere-Else.md))
- a specification, a data extract, an export from a system the task cannot reach
- a screenshot, a crash dump, a profiler output
- the answer to a question the agent asked

None of these belongs in the repository, several of them must never be committed, and the branch is
the wrong shape for all of them: the gate is for work leaving, and this is content arriving.

**Today there is no path.** The workspace is inside the container on purpose, and the only things
bind-mounted into a container are the vault and ssh sockets. The one route that exists is
`sokar task attach` and a terminal, which means a person pasting - fine for a paragraph, useless for
a 4 MB log, and impossible for anything binary.

## Why this is the cheap one

Everything that makes [B14](B14-Talking-Between-Tasks.md) and
[B15](B15-Handing-Artifacts-Between-Tasks.md) hard is absent here, and it is worth being explicit,
because those two are slow for reasons that do not transfer:

- **No class escalation.** The bytes come from the operator or from Sokar itself, not from another
  project's task, so nothing crosses a security boundary that exists to keep two tasks apart. An
  `offline` project can accept a file without offline meaning anything less.
- **No egress and no credential.** It is host-local, in one direction, with nothing to authenticate
  to.
- **No new trust relationship.** Whoever can do this can already `task attach` and type. This adds
  bandwidth to a channel that exists, rather than opening one.
- **Nothing is executed.** B15's whole subject is that an artifact is content the next build runs
  without anybody deciding anything. A file handed in to be read is not that - and if somebody
  hands in a binary and the agent runs it, the person who handed it in decided that.

What is left is small, and the small parts are where it goes wrong.

## A directory of its own, holding nothing else

**Not `/workspace`.** A file dropped there becomes part of the work: an agent tidying up commits it,
and it leaves through the gate as though somebody had written it. A 40 MB log in a repository a
person will clone for years is B15's own threshold argument arriving by the back door, and a data
extract that was never supposed to be committed is worse than large.

**And not `/run/sokar/` either**, which was the first guess because that is where the container
already meets Sokar - the vault socket is mounted there. It is the wrong parent for exactly that
reason: everything in it today is a thing Sokar controls and a task consumes through a defined
interface, and this would be the first content in it that came from outside. Mixing them makes one
directory mean two things, and the one an agent is told to watch is then also the one holding the
socket its credential goes through.

**So a directory whose only purpose is this, under a root that belongs to Sokar** - `/sokar/files`.
A top-level `/files` was the alternative and is the most nameable thing there is; it is also
somebody else's name. A base image, a build, a framework's convention or a developer's habit can all
put a `/files` there, and a collision on this particular directory is not a tidy inconvenience - it
means an agent that was told to read everything in it reads something a build wrote, or an operator
hands in a file that lands among a hundred unrelated ones.

**Owning a root only works if nothing else claimed it, so an image that ships `/sokar` is refused
rather than shared with.** It is checked at preparation, where an image is dealt with deliberately
and a failure costs nothing (`sokar task prepare` is that step, in
[Running Sokar](../../doc/running.md#adding-your-own-tooling-to-a-task)), and
again before a task starts, because an image can arrive prepared by somebody else or be updated
under an environment prepared earlier.

**And it stops rather than warns**, which is the same call the hook-freshness check makes and for
the same reason: a mixed `/sokar` looks entirely normal. The property that makes *"read everything
in that directory"* a safe instruction is that everything in it arrived the same way, and an image
that brought its own content there makes that quietly false - the agent reads a build's leftovers as
though a person had handed them over, and nothing anywhere says so. The refusal names the image, the
path, and what to do about it, because a diagnostic that names no next action is not one.

Note it is `/sokar` that is refused and not `/sokar/files`: an empty directory is still a claim on
the root, and an image update can fill it later.

**That makes two Sokar-owned paths in a container, and the split is deliberate.** `/run/sokar/` is
Sokar's machinery - sockets, runtime state, the things the container talks *through* - and `/run` is
the right place for it precisely because it is volatile by convention. `/sokar/` is content: what
the task was given, addressable, stable for the whole run, and the only one of the two that ever
appears in an instruction a person writes.

Two properties follow from the directory being dedicated rather than convenient:

- **An agent can be told to watch the whole thing**, because everything in it arrived the same way
  and means the same thing. A directory that also held sockets, state or configuration could only
  be watched with a rule about which parts to ignore, and a rule like that is one somebody gets
  wrong later.
- **It is removable and countable.** What is in it is exactly what was handed in, so "what was put
  into this task" is a listing rather than a reconstruction.

**A handed-in file has to be removable while the task runs.** It is the reverse of the workspace
rule: work is kept because it is precious, and this is content the task borrowed. Something that
stays for the life of a container, is copied into a backup, or survives a resume nobody expected it
to survive is a surprise in the wrong direction.

**A file must appear complete or not at all.** An agent watching the directory will read whatever is
there the moment it looks, and a poll that lands in the middle of a copy yields half a log - which
reads as a truncated build rather than as a delivery still in progress. Write under a temporary name
and rename into place; a rename within one filesystem is atomic, and the temporary name must not
itself match what the agent was told to watch.

## Arriving is noticed because the instruction says so

A file in a directory nobody watches is not delivered, it is stored - and the answer is not a
notification mechanism.

**Sokar delivers; the task's instruction does the noticing.** An unattended task is started with an
instruction anyway, and that instruction is where the agent is told that files arrive in
`/sokar/files` and that it should look there. Nothing has to reach into a running agent, which is
the part that could not have been built cleanly: telling an agent something means knowing how that
agent is told, and that is a fact belonging in its definition rather than in a branch here.

**What Sokar owes in exchange is a path that can be named in advance.** An instruction written when
the task starts has to be true for the whole run: the same directory, the same name, whatever the
agent, whatever the base image, whether or not anything is ever handed in. A path that varies by
agent or appears only once something has been delivered cannot be written into a prompt, and would
push the problem straight back into the notification mechanism this avoids.

**Watching costs the agent something, and that is the honest limit.** Most agents have file tools
and no inotify, so "watch the directory" in practice means looking again between pieces of work.
Something handed in while the agent is deep in a long step is seen when that step ends, not when it
arrives. That is adequate for a build log and it is not a message bus - which is
[B14](B14-Talking-Between-Tasks.md), and stays there.

## The interactive case is not the lesser one

The unattended case is what forces the directory to be predictable; the interactive one is what
makes the feature ordinary. A person in an attached shell - in an `offline`, `guarded` or `online`
project alike, since nothing here touches the class - has something the agent needs and no way in:

**a graphic.** A mockup, a diagram, a screenshot of the thing that is wrong. It is not in git, often
for good reasons - it is not source, it is not reviewable as a diff, and committing it to hand it
over would put it in the repository's history for ever. Every other example is a variant of the same
shape: a spreadsheet, a PDF, a data extract, a font, a certificate chain to look at.

Here nobody needs to be told anything - the person hands the file in and says what to do with it in
the same session - so the requirement is only that it is possible at all, and that the file arrives
where the person can name it.

## A remote client has no filesystem

The trap is already recorded here and this requirement walks straight into it: every gate method and
`Start` take a path, which is fine typed on the machine that holds the file and impossible over a
forwarded socket. `sokar task give ./build.log` typed at the node can pass a path; the same command
from a client on another machine has to carry the **bytes** over varlink, which is a different
method with a different cost and a size limit somebody has to choose.

Getting this wrong produces a command that works for the person who built it and fails for everyone
else, which is the failure [B06](B06-Remote-Access.md) exists to prevent.

## In is cheap; out is the gate's question

The mirror image - taking a file *out* of a running task - looks like the same feature and is not.
Content leaving a task is what the gate is for, and a copy out is
[B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md)'s side door with a Sokar command on it.
Worse, it puts agent-controlled bytes on the host, which is the reason the workspace is not
bind-mounted in the first place. `podman cp` out already exists for a *stopped* task's recovery and
is printed as advice, which is a different situation: nothing is running, and a person is retrieving
work that would otherwise be destroyed.

**This requirement is inbound only**, and says so rather than leaving it to be added later by
symmetry.

## Every hand-in is written down

**This is the only way into a running task that leaves no trace of its own.** Everything else that
reached the work can be answered afterwards from something: the clone came from the mirror, what the
agent installed is counted by `podman diff` before removal, what it tried to reach is in the
clearance record. A file a person copies in influences the work as much as any of those and is
invisible the moment it is read - so if this does not write the record, nothing does.

**It is what makes the work explainable at the gate.** A reviewer meets a change built on a
specification that is nowhere in the repository, and *"where did this come from"* has no answer
unless the hand-in was recorded. That is the review half of
[B38](B38-How-Far-Something-That-Got-Through-Can-Get.md) needing one concrete fact, and this is
where it comes from.

What the record has to hold, and each for a reason:

- **Who**, because this is an act by a person and not by the machine - the distinction
  [B31](B31-An-Authorization-A-Person-Grants-Once.md) draws for individual authorization. A node
  several people can reach makes it load-bearing.
- **When**, and **which task**, so it can be lined up against what the work did next.
- **The name and the size**, which is what an operator recognizes it by.
- **A content hash.** The name is chosen by whoever hands it in and can be reused: a second file
  arriving as `spec.pdf` is a different input, and a record that cannot tell the two apart says
  something untrue about which one the work was built on. `sha256`, the identity
  [B15](B15-Handing-Artifacts-Between-Tasks.md) already uses.
- **Removals and replacements too**, because a file that can be taken away while the task runs
  makes *"what was in `/sokar/files`"* a history rather than a listing.

**Never the contents.** A handed-in file is frequently the sensitive thing that is deliberately not
in git - that is why it is being handed in - and a record that copies it turns a borrowed input into
a durable one. The record names it; it does not keep it. This is the same rule
[B37](B37-The-Build-That-Runs-Somewhere-Else.md) settled for a delivered build log.

**It goes in the state directory, and the reason is already measured here.** Everything else a task
writes is under the runtime directory, which the kernel clears at logout and `task stop --remove`
deletes outright; clearance decisions were moved out of it for exactly this, on the finding that *a
record of what an agent reached that disappears with the task is not a record*. A hand-in record is
the same kind of fact and takes the same place, which also means it is not new machinery.
[B26](B26-What-This-Machine-Has-Been-Doing.md) owns where these records eventually live.

**And it records what this command did, not what reached the container.** Somebody with access to
the runtime can `podman cp` into a task directly and nothing here will see it. That is the same
honesty `gate protect` states about its own hook - an accident-catcher and an account of ordinary
use, not a control against the owner of the machine - and a summary that implied otherwise would be
the lie this project keeps refusing.

## Decided

- **A dedicated directory, `/sokar/files`, mixed with nothing.** Under a root Sokar owns rather
  than a top-level `/files`, because the nameable path is also the colliding one: a base image or a
  build can create `/files`, and then "read everything in this directory" means something else.
  `/run/sokar/` keeps the machinery and `/sokar/` holds content, so the directory an instruction
  names is never the directory the credential socket lives in. Not the workspace, because the gate
  would carry its contents out; not `/run/sokar/`, because everything there is Sokar's own interface
  to the container and an agent told to watch that directory would be watching the vault socket's
  neighbourhood. Dedicating it is what makes "watch all of it" a safe instruction.
- **The unattended case is answered by the instruction the task starts with**, not by a mechanism
  that tells a running agent something. Sokar's obligation is a path that is predictable in advance;
  the rest is what the operator writes in the prompt.
- **An image that ships `/sokar` fails loudly at preparation, and again at task start.** Sharing
  the root would make "everything in this directory arrived the same way" untrue without anything
  saying so, which is the one property the instruction relies on. Checked twice because an image can
  be prepared elsewhere or updated under an environment prepared earlier, and refused rather than
  warned about because the result looks normal.
- **Inbound is for the interactive case as much as the unattended one, in all three security
  classes.** A graphic that is deliberately not in git is the ordinary example, and it is not a
  lesser case than the build log - it is the one people will meet first.

## Acceptance

- A hand-in is a copy into the running container, once; nothing is mounted for it. Seen to fail: the
  container's mounts after a hand-in differ from before it.
- An operator hands a file to a running task and the work inside it reads that file. Seen to fail:
  a test that hands in a file and reads it from inside the container goes red when it is missing.
- The file lands in a directory used for nothing else, outside the working copy, and nothing an
  agent does by habit commits it. Seen to fail: a test that hands in a file and then runs
  `git status` in `/workspace` goes red when the file shows up there.
- That directory is `/sokar/files` for every task whatever the agent and the image, exists before
  anything is handed in, and can therefore be named in an instruction written when the task starts.
  Seen to fail: a check that the directory exists in a freshly started task, before any hand-in,
  goes red for any agent or image where it does not.
- Nothing but handed-in content is in it, and Sokar's own machinery is somewhere else. Seen to fail:
  a listing of `/sokar/files` in a task that was handed nothing is not empty.
- An image that already contains `/sokar` is refused at preparation and again before a task starts,
  naming the image, the path and the next action - never accepted with a warning. Seen to fail: a
  test with such an image goes red if preparation or start succeeds, or if the refusal lacks the
  image, the path or the next action.
- A file being written is not readable under the name the agent watches; what the agent sees is
  complete or absent. Seen to fail: a reader polling the watched name during a large hand-in sees a
  partial file.
- A person in an attached session hands in a binary file - an image is the ordinary case - and the
  agent works with it, in every security class. Seen to fail: the handed-in bytes differ from the
  original in any of `offline`, `guarded` or `online`.
- It can be removed while the task runs, and what happens to it on resume, on stop and on purge is
  stated rather than discovered. Seen to fail: a removed file is still present in the task, or its
  fate on resume, stop or purge is not written where the operator reads it.
- The same command works from a client that does not share this machine's filesystem, or refuses in
  a way that names why - never silently reading a path that means something different there. Seen
  to fail: a remote client's hand-in reads a path on the node.
- A size limit exists, with a default a project file can raise, and a file over it is refused before
  anything is copied, with the limit named.
  Seen to fail: an oversized file leaves any bytes in the task, or the refusal does not name the
  limit.
- The task gains no outbound path from this: nothing added here lets a file leave a container. Seen
  to fail: any command or method added here that returns content from the container.
- `sokar task status` lists what was handed into the task - each file's name, size, when and by
  whom - read from the hand-in record. Seen to fail: a task handed a file shows nothing of it in
  its status.
- Every hand-in is recorded: who, when, which task, the name, the size and the content hash - and
  a removal or a replacement is recorded the same way. Seen to fail: a hand-in, removal or
  replacement with no record entry, or an entry missing one of those fields.
- That record survives the task, a reboot and `task stop --remove`, and it is readable without the
  task still existing. Seen to fail: the record is gone after `task stop --remove`.
- No record holds the file's contents. Seen to fail: a hand-in of a file with a known marker string
  leaves that string in the record.
- What the record cannot see is stated where an operator reads it: a copy made with the runtime's
  own tools is not in it. Seen to fail: that statement is missing where the record is shown.
- Content Sokar delivers is distinguishable, at the destination, from content the task produced
  itself. Seen to fail: a file the task wrote itself cannot be told apart from a handed-in one.
- A task in an `offline` project accepts a file like any other; nothing here depends on the
  security class. Seen to fail: a hand-in to an `offline` task is refused.
- Nothing is asked of an agent that never uses it. Seen to fail: an agent definition has to change
  for a task that is never handed anything.

## To be checked

- **How a running container is written to at all**, measured rather than assumed: `podman cp` into
  a running rootless container, with the file owned by the agent's subordinate uid and readable by
  it. The uid mapping is where the other socket-shaped things here have gone wrong.
- **How the presence of `/sokar` in an image is established cheaply.** Asking a throwaway container
  is certain and costs a container per check; reading the image's layers is cheaper and has to be
  right about whiteouts, where a path deleted in a later layer still exists in an earlier one.
