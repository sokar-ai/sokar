# B20 — Creating A Project

**Status:** open. Nothing creates a project. A person writes `project.yml` by hand, and everything
downstream behaves as though the project simply appeared.

## What it is not

**Not a form.** An interface can already ask questions and show the result for review. What it
cannot do is know whether an answer is **acceptable** — whether that base image resolves, whether
that security class is spelled correctly, whether that upstream is reachable — without asking the
machine. Guessing on the client's side would put a project on disk that fails at first use, far
from where the wrong answer was given.

## Acceptance

- A project is validated before it is created, against the machine that will run it.
- Nothing half-created survives somebody walking away: either a project file exists and is
  readable by the project reader, or nothing does.
- The result is shown for review before it is written.
- Creating a project that already exists is refused by name.

## Notes

The validation half already exists in pieces — the project reader is what refuses a malformed file
today, and `SetEgress` already writes `project.yml` in place, comments and all. What is missing is
the composition, not the parts.

Nothing here writes an instruction into a project file. That was settled: **a project file
describes constraints, not instructions** — everything in it is a bound on what work here may do,
and a prompt is the opposite kind of thing. See [B21](B21-Instructions-For-An-Agent.md).

## To be checked

- **Whether creating a project should also prepare it.** They are different decisions with very
  different costs, and joining them would make creation take minutes for no stated reason. Probably
  separate, with [B19](B19-Preparing-An-Environment-On-Purpose.md) offered afterwards.

## Built, 2026-09-08: `CreateProject`

**The checking is what landed, because the form was never the missing part.** An interface could
always ask the questions; what it could not do is know whether an answer is acceptable. Each of
these is refused here rather than at the first task start, which is minutes later and somewhere
else:

- **A name that cannot become an image tag.** It also becomes a container name and an nftables set
  name, so it is stricter than a directory name - and `My Project` is a perfectly ordinary
  directory to be sitting in.
- **A misspelt security class.**
- **An egress set this machine does not have**, with the available ones named. Only this machine
  knows which sets it has, which is exactly why a client cannot check it: an unknown one fails at
  task start as a name resolving to nothing, and that reads as a broken build rather than a typo.
- **An `online` project with no upstream**, which is a project that can never send anything
  anywhere.

**A base image that is not on the machine is reported and not refused.** Pulling it reaches the
network, takes minutes and can hang, and none of that belongs in answering a question - so it is a
non-fatal problem and the project is still created.

**An existing file is refused, never overwritten.** It may be somebody's whole configuration, and
this is the one operation that would replace it with nothing to restore from.

**The refused file is still rendered.** Seeing what was rejected is most of understanding why, so
`content` is filled on every outcome including `INVALID`.

**Written whole or not at all**, to a temporary name and moved into place. That guard has no test
and cannot have one - the difference is visible only to a process that dies between the write and
the rename - and it is recorded as untested in the code rather than left to look covered.

## Still open here

- **Two things now write a project file.** `ProjectWizard` asks at a terminal when `task run`
  finds no project, and this writes one after checking. They render slightly different files - the
  wizard guesses egress sets from the base image - and neither validates what the other does. They
  should be one thing, with the wizard supplying answers to this rather than writing its own file.
  Not done, because a half-finished merge of the two would be worse than the duplication.

