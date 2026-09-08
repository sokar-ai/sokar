# B19 — Preparing An Environment On Purpose

**Status:** open. Nothing prepares a project's environment except starting work in it.

`task run` builds what it needs on its way to running an agent. There is no way to say *"get this
ready, I am not starting anything yet"*, and no way to say **how much** to rebuild.

## The depths are the requirement, not the rebuild

A person deciding to rebuild is deciding what it will cost them. *"Rebuild"* with no answer to
*"how much of it"* is a button people press once, wait ten minutes, and then avoid. Three depths
are worth telling apart:

- **reuse what is there**, replacing nothing;
- **replace the agent tooling only**, keeping the base image and its packages;
- **discard everything and start again.**

## And half of it already exists, wrongly named

`Project.prepared` says whether a task image exists. What it cannot say is whether that image is
**stale** — built before the project file changed underneath it. Those are different answers to
different questions, and only the second makes *"this will not be what you expect"* sayable before
work starts rather than after.

Nothing records what an image was built from, so today staleness is not computable at all. The
cheap version is a label on the image carrying a digest of the project file's image-relevant
fields, compared on read — which turns one bool into three honest states.

## Acceptance

- An environment can be prepared without starting a task.
- The depth is chosen explicitly, and each depth says what it will and will not keep.
- Progress is reported while it runs, because a build takes minutes and silence is
  indistinguishable from a hang.
- A failure names the step that failed and stays readable after it has finished.
- `prepared` distinguishes *absent* from *stale*, or says that it cannot.

## Notes

The streaming half is not new machinery: `Start` already streams a build with `more`, one reply per
line, for exactly this reason.

## To be checked

- **Whether "replace the agent tooling only" is a real boundary** in the image, or whether the
  layer it would rebuild drags the package layer with it. If it does, there are two depths, not
  three, and promising three would be a lie about cost.

## Built, 2026-09-08: `sokar task prepare` and `Prepare`

**Three depths, and the middle one is real.** The image's layers are base, then the packages every
task needs, then the agent's - so an `ARG` placed at that seam is what makes "replace the agent's
tooling" possible: podman invalidates its cache from the line whose text changed, so passing a
value it has not seen rebuilds the agent's layers and keeps the packages above them.

There is **no podman flag for "rebuild from here"**, which is why this is a mechanism rather than a
switch, and why it was worth checking before promising three choices. A screen offering a cheap
middle option that turned out to cost a full rebuild is the failure this requirement opens with.

| depth | what it keeps |
|---|---|
| `CACHED` | whatever podman's cache still considers valid |
| `AGENT` | the base image and its packages; the agent's tooling is rebuilt |
| `EVERYTHING` | nothing - the base image's packages are downloaded again |

**`CACHED` is not "skip the build".** The build runs and the cache decides layer by layer, which is
exactly what every task start already does - so preparing and starting cannot disagree about what
the image should contain.

**An unknown depth is refused, not defaulted.** Quietly doing the cheapest thing when somebody
asked for the most expensive is the same failure as promising a cheap middle choice.

**An image with no agent is prepared rather than refused.** It is what somebody working inside the
container by hand needs, and refusing it would make this useless for exactly that case.

Progress is written line by line and the daemon streams it with `more`, for the same reason `Start`
does: a build takes minutes, and showing nothing for that long is indistinguishable from hanging.

## Still open here

- **Which agent an image was built for is not recorded.** The image contains the agent's layers, so
  an image is really per project *and* agent - but `preparedState` compares only the project file.
  A task started with a different agent rebuilds those layers, which is correct and fast, but
  `READY` overstates slightly. Adding the agent to the image's labels would let a listing say
  *"ready, built for claude"*.

