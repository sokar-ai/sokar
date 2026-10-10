# B120 — A Wake Waits For Rest That Holds

**Status:** now.

**What must be true.** Sokar types its wake line into a task's session only when the agent's declared `at_rest` has
held on two looks at the screen a short while apart, with no sign of work between them, never into a turn that is
still running.

## Why

`AgentWake.wake` reads the screen once and types at once when `at_rest` matches. An agent's work line comes and goes
between its tool calls, so one look can fall into such a gap and take a working agent for one at rest. Seen on
`sokar-claude-code`'s run `37343923956`, job "Acceptance - fedora" (`sokar` `0.4.1~snapshot.250`, Claude Code 2.1.267):
the wake line stood right under the typed prompt, before the agent's first tool call - the instant between Enter and
its working line matched `at_rest`, which asks for `bypass permissions on` and the absence of `esc to interrupt` - and
the agent then reported "empty messages that keep interrupting my tool calls" and its tool use was interrupted. The
same scenarios were green on other legs with the same code, which is what a race looks like. The acceptance kit's
own ready check already asks that a marker stay on screen for a while before it counts.

**One definition of rest:** B118 reads `at rest` for status and list from the same `at_rest`; it takes rest by this
rule too, so a status never says *at rest* for the instant the wake would not type into.

## The line must also leave the input box

Seen on 2026-10-10 in an agent repository's acceptance run: Claude Code v2.1.267 draws a notice about an unknown model
when it starts, and the Enter typed after a line is taken by it. The line stays standing in the input box, and the
agent never works. `Podman.type` types the wake line and then Enter in the same way, so a wake can be lost the same way:
typed, never submitted, and nothing says so.

So after typing, Sokar looks at the screen again. If its wake line still stands in the input box, it presses Enter once
more, as a person would, and says in the journal that it had to. A line that still stands after that is said as a wake
that did not reach the agent, never as a wake that did.

## Acceptance

- Seen to fail first: a wake whose Enter is swallowed leaves the line in the box; afterwards Enter is pressed once more and
  the journal says so, and a line still standing after that is reported as not delivered.

- A screen that matches `at_rest` on one look and shows work on the next is not typed into. Seen to fail: a unit test
  whose stand-in screen rests for one look only, against the code that types after one look.
- A screen at rest on two looks a short while apart is typed into once, as today. Seen to fail: the same test with
  the rest held, if the wake no longer types at all.
- The wake scenarios of the agent repositories stay green with the change, on both legs.

## To be checked

- How far apart the two looks are, and whether the daemon's pass waits between them or the next pass takes the second.
