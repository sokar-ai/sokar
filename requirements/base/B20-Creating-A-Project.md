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
