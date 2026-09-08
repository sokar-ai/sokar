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
