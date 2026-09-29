# B38 — How Far Something That Got Through Can Get

**Status:** open; the ranked review, the reach document and the clearance prompt's two readings are built
(2026-09-29). What remains is marking the origin of what Sokar delivers, which waits for the deliveries. It is a frame rather than a
feature: most of it is writing down and bounding what is already here, its one buildable half is
the review, and its largest item - the provider channel - may have no answer at all. Nothing waits
on it. [B37](B37-The-Build-That-Runs-Somewhere-Else.md) and
[B39](B39-Handing-A-File-To-A-Running-Task.md) deliver content into tasks whether this is settled
or not, and the honest reason to keep it is that each of them would otherwise re-argue it from
scratch in its own file - which is exactly how it got written. Proposed on 2026-09-10, out of
[B37](B37-The-Build-That-Runs-Somewhere-Else.md), which tried to argue that delivering a build log
creates an injection surface and could not: a task already reads text nobody here wrote, every
time. The general question is this file's. It is upstream of B37, of
[B14](B14-Talking-Between-Tasks.md) and [B15](B15-Handing-Artifacts-Between-Tasks.md), which each
deliver more such text, and it owns the half of
[B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) that is about what a reviewer can actually
see. The record it depends on is [B26](B26-What-This-Machine-Has-Been-Doing.md).

**Where the other half lives.** The ranked review as a screen is the interface's work, handed to sokar-frontend on 2026-09-13 as F32. It is **blocked by this file's buildable half**: the ranking has to exist before anything can show it.

## Two questions, and they are the same question

**What a person at the gate can actually see in a long, boring diff**, and **how far something
gets when they miss it.** They look like separate concerns and are one: the review is the last
control, so everything it fails to catch is the blast radius, and everything the blast radius
already bounds is something the review does not have to catch. Answering either alone produces
either a reviewer drowning in patches that mostly do not matter, or a containment story with no
statement of what it does not contain.

## Prevention is not on the table, and pretending otherwise is the failure

A task reads text an outsider wrote whenever it does anything useful: the repository it was
pointed at, a diff it was asked to review, an issue quoted into its prompt, a dependency's release
notes, and - once B37, B14 and B15 exist - a build log, a message from another task, an artifact.
There is no filter that separates instructions from content in a text meant to be read by
something that follows instructions. Any requirement here that promised detection would be the
fifth of a shape this project keeps writing down and refusing, after the agent roster, hardware
access, key routing and instruction files: the confident answer that is sometimes wrong is worse
than the honest *"this system does not do that"*.

**So the subject is not whether the agent gets convinced. It is what a convinced agent is worth.**

## What is already true, stated once as one property

Each of these exists and is enforced; none of them has ever been written down together as *the*
answer to this question, which is why it keeps getting argued from scratch in individual files.

- **An undeclared name does not resolve** through the task's resolver, and a declared one opens
  ports 80 and 443 only. An empty `egress` section is deny, not a default that widens with a
  release. A query sent past the resolver still gets an answer, though the connection does not,
  which is B57 ([index](README.md)).
- **The real credential is never in the container.** The vault holds it; the container gets a
  phantom token that is worthless anywhere else and dies with the task. The ssh signing key is not
  in there either - the task gets an agent socket.
- **Work does not leave without a person.** In `guarded`, `GitGate.approve` is the only method
  that forwards anything and nothing the agent can reach calls it. In `offline` nothing leaves at
  all.
- **A reach for something undeclared raises a clearance prompt.** This is the closest thing on the
  machine to a hijack alarm: an agent asking for a host nobody declared is the observable shape of
  an agent following somebody else's instructions, and it is already recorded per container and
  never asked twice.
- **The broker is a policy point and not a pipe.** It drops `authorization`, `x-api-key`,
  `private-token` and `proxy-authorization` before forwarding, examines the first 8 KB of an answer
  for `access_token`, `refresh_token` and `id_token` as JSON fields rather than as prose, and
  refuses a `refresh_token` or `client_credentials` request outright, because a container asking
  for a token of its own is asking the one question the broker exists to make unnecessary.
- **The workspace stays inside the container**, so agent-controlled `.git/hooks` and `.git/config`
  never sit where a host-side `git status` would run them.
- **What the agent installed is counted before removal** with `podman diff`, and dies with the
  container.

## The blast radius, honestly

What the list above does not bound. This is the part that has to exist in writing, because each
line is something a person would otherwise assume was covered.

**The provider channel is always open, and a firewall bounds whom a task talks to, not what it
says.** Every task has, by construction, a working route to a model provider carrying request
bodies up to 32 MB. Anything the agent knows can be written into a prompt. No egress set closes
this, because closing it is closing the task. **This is the largest single item here and the one
with no answer proposed.**

**Every declared host is a second such channel.** A project that declares a forge so the work can
clone from it has an outbound path to that forge on 443; data fits in a URL. Declaring what the
work needs is correct and is also the shape of the hole.

**A credential that cannot be read can still be spent.** The phantom token is worthless outside
the task, and inside it, it commands whatever the real credential commands: tokens, money, and
whatever else that account can do at the provider. Non-exfiltration is not non-use.

**The work itself is the intended output, and it is what a convinced agent writes into.** A
plausible change in the branch the task is allowed to write is not blocked by anything above; it
is caught, if at all, by a person reading a patch. Commit signing does not help here - the task
signs whatever the agent wrote, so the signature proves origin and not intent.

**Two guards are accident-catchers and say so.** `gate protect`'s hook is measured to fire on a
first, ordinary and force push, and four things go round it: `--no-verify`, a `core.hooksPath`
pointing elsewhere, a fresh clone, and a client built on a git library. `gate check`'s author test
is spoofable. Both are correct as written and neither is a control; a summary of this requirement
that counted them as containment would be wrong.

**And the convenient path around the gate is a person.** Fetching the agent's branch into your own
checkout and pushing it is two ordinary git commands, it is today the comfortable way to look at
the work, and the gate then reports that work as still pending for ever.

**What does not extend:** there is no path from one task to another today - separate containers,
no shared workspace - and B14 would create the first one, which is why it is refused across
security classes before it is built. Nothing reaches the host filesystem outside what Sokar itself
writes.

## The reviewer gets a raw patch, and that is where it actually fails

`gate review` runs `git show --patch`, or `git diff` against a ref, and prints it; `gate log` runs
`git log --oneline`. That is the whole of what a person is given before they decide whether work
leaves the machine.

Nothing in it ranks anything. A one-line change to a CI workflow file, a new dependency, a
modified build script and a 4,000-line reformat all arrive as the same undifferentiated text, in
path order, and the reformat is at the top because of where it sits in the tree. **The failure mode
is not that the reviewer is careless; it is that the interface gives them no way to be careful
cheaply**, and a control that is expensive to exercise is one that gets exercised as a formality.

What would help, with what each is honestly worth:

- **Separating what was asked for from what else happened.** A task is started with an instruction;
  the files it touched can be compared against the ones that instruction implicates. Weak as a
  detector, strong as an ordering.
- **Ranking by consequence, not by suspicion.** Some changes are dangerous by kind whoever wrote
  them: CI workflow definitions, build scripts, git hooks committed into the tree, dependency
  manifests and lockfiles, anything under a directory that executes on checkout. This is a fixed
  list of shapes, it needs no judgment about intent, and it is the same list whether the cause was
  an injection, a bug, or an agent being agreeable.
- **Saying what is *not* worth reading.** Pure reformatting and generated files are most of the
  volume in a boring diff and almost never the finding. Shrinking what must be read is worth more
  than highlighting within it.
- **Marking what Sokar itself delivered.** Content that arrives through a helper of ours - a build
  log, a message from another task, an artifact - can be handed over labeled as data of known
  origin rather than as instructions, and the same origin can be shown to the reviewer. A
  repository arrives by `git clone` and Sokar never sees a byte, so its provenance is unmarkable;
  the difference is worth stating, because it is exactly the list of things this project is about
  to start delivering. **How** it is labeled is not Sokar's to decide: fencing conventions differ
  per agent, so that is a field in the agent's definition, not a branch here.

None of these is detection, and the file must not drift into claiming it is. They change what a
person reads first and how much they must read at all.

## What must be true

**How far a convinced agent can get is written down, bounded where it can be bounded, named where
it cannot, and the person who is the last control is shown what matters before what is merely
large.**

## Acceptance

- A single document states what a task can reach, hold, spend and produce, and what bounds each -
  including the items nothing bounds - and a change that widens any of them fails a check unless
  that document changes with it.
- The provider channel is stated as an open exfiltration path in the operator-facing
  documentation, not only here. An operator who believes a task cannot send data out is holding a
  belief this system does not support.
- A review names, before the patch text, what the push changes that is dangerous by kind, from a
  fixed list, and says so even when the change is one line.
- A review separates what the task was asked to do from what else it touched, and a reviewer can
  read the second without reading the first.
- Volume that is almost never a finding - reformatting, generated files - is identified as such
  and is not what the reviewer meets first.
- Content Sokar delivers into a task carries its origin, in a form the agent's own definition
  declares, and the same origin is visible to a person reviewing what came of it.
- A clearance prompt is presented as what it is: possibly an ordinary missing declaration, possibly
  an agent acting on somebody else's instructions. The operator is told both readings once, where
  they answer it.
- Everything a task reached, pushed and installed is answerable after the task is gone, so the
  question *"how far did it get"* has a source that is not memory.
- Nothing in this requirement claims to detect an injection, and every guard says whether it is a
  control or an accident-catcher.

## Decided 2026-09-29, by the operator

- **The list of what is dangerous by kind is fixed and ships with Sokar.** A list inside the repository
  could be edited by the very change it is meant to catch; a list per project outside it is one more file
  nobody maintains.
- **The instruction is shown, never compared.** The review puts what the task was asked at the top, says
  "none" when it was started without one and "not known" when its task is gone, and matches nothing
  against it. A signal present half the time, and a guess when it is, would be leaned on wrongly.
- **The provider channel is documented as a decision**, in the operator-facing documentation and in the
  reach document, with the record of what a task sent. No alert: a false alarm on a working task is worse
  than nothing.
- **One requirement, in this order:** the ranked review first, then the reach document with the check that
  holds it, then the operator documentation.

## The ranked review, as built

- `ReviewRanking` (gate) ranks every file a push changes as DANGEROUS, ORDINARY, GENERATED or
  REFORMATTING, in that order, and has git print the patch in the same order (`-O`).
- **Dangerous by kind** is a fixed list, each entry with its reason:
  - CI definitions (`.github/workflows/`, `.gitlab-ci.yml`, `Jenkinsfile` and their kin);
  - build scripts (`pom.xml`, Gradle, `Makefile`, `Dockerfile`, `build.rs` and more);
  - dependency manifests and lockfiles (`package.json`, `go.sum`, `Cargo.lock`, `requirements*.txt` and
    more);
  - what runs on checkout or when the repository is opened (`.gitattributes`, `.gitmodules`, `.husky/`,
    `.vscode/`, `.devcontainer/`, `.envrc`);
  - a file made executable, and a symbolic link.
- **Almost never the finding** is a change of whitespace alone (nothing left under `-w
  --ignore-blank-lines`), and a file that says it was generated (`@generated`, `DO NOT EDIT`, `Code
  generated by`, or a `.min.js`/`.min.css` name). These are moved to the end, never hidden, and a dangerous
  file is never filed as either.
- Renames count as a removal and an addition, so a file moved into a workflow directory is ranked by where
  it arrives.
- `sokar gate review` prints what the task was asked, then the three groups with each file's counts and
  reason, then the log, then the ordered patch.
- The daemon's `Review` reply carries the same as `files: []ReviewFile` and `asked: ?string`, and its
  `diff` is in the same order - what the interface's F32 was waiting for.
- Proven by `GitGateTest` against a real push, and by `gate-review.feature` on the ubuntu VM: a one-line
  workflow beside an ordinary change is named first.

## The writing half, as built

- **`doc/reach.md`** states what a task can reach, hold, spend and produce, what bounds each, and what
  nothing bounds - the provider channel first, stated as an open path on purpose. Every guard is labelled
  a control or an accident-catcher. `doc/security-classes.md` points to it.
- **`ReachDocumentTest`** reads the page's numbers off the code: the request body limit, the ports a
  declared host is opened on, the credential headers dropped, how much of an answer is examined, the
  grants refused. A bound that changes in the code fails the build until the page says the same. It also
  refuses a table row that does not say whether it is a control or an accident-catcher. Watched to fail
  on a changed number.
- **The clearance prompt** now says both readings, where the operator answers it: an ordinary missing
  declaration, or an agent acting on instructions from something it read - allow only an expected host.

## Still open

- **Content Sokar delivers carries its origin**, in a form the agent's definition declares. Nothing
  delivers content into a task yet; this belongs with the first delivery (B37, B39, or B14's messages)
  and is decided there, against this file.
- **"How far did it get" after the task is gone.** What it reached for is journaled and kept; what it
  pushed stays in the gate. What it installed is counted by `podman diff` before removal, but the count is
  not yet kept anywhere a person can read afterwards.

## To be checked

- **Whether the provider channel can be bounded at all without breaking the product.** Rate, size,
  or an alert on a request that is large and unlike the ones before it, against the fact that a
  long context is normal and a false alarm on a working task is worse than nothing. It may be that
  the honest answer is documentation and the record, and that would be a decision rather than a
  gap.
- **Whether "what was asked for" is knowable.** It needs the task's instruction, which Sokar has
  when a task is started with one and does not have when a person types into an attached session.
  A signal that is present half the time is one nobody can rely on.
- **Whether the dangerous-by-kind list belongs to Sokar or to a project.** A fixed list is
  predictable and wrong for somebody; a per-project list is right and is one more thing to
  maintain, in a file a hijacked agent can also edit - which decides it, if the file is inside the
  repository.
- **Whether this is one requirement or the introduction to several.** The review half is buildable
  now; the bounding half is largely writing; the provider channel may be neither.
- **What an interface does with it.** A ranked review is a screen more than it is a terminal
  command, and the frontend set is where that judgment lives.
- **Whether the record can answer "how far did it get" today.** B26 says host-side logs live on
  tmpfs and a reboot deletes them, which would mean the answer to the question this file is named
  after has, in the ordinary case, nowhere to look.
