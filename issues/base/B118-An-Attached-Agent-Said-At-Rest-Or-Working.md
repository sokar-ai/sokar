# B118 — An Attached Agent Said At Rest Or Working

**Status:** soon.

**What must be true.** `sokar task status` and `sokar task list` say whether the agent of an attached session is
`at rest` or `working`, read from its screen against the `session.at_rest` its definition declares, as `waiting`
is read today. A task whose agent declares no `at_rest` keeps saying `unknown`.

## Why

A task's activity is observed from outside where it can be: working and idle by the age of `task.log`. An attached
session writes to its terminal, not to that log, so for it the activity is always `unknown`. That was measured with
an omp task: `unknown` before its prompt, during a 15-second tool call and after its answer alike. The agents already
declare what their screen shows at rest, measured for every shipped agent, and the wake reads it to decide when a
message may be announced. Status and list read the same declaration, so this needs nothing new from an agent and
fits the decision that waiting for a person is never inferred from silence: `at_rest` is the agent's own
declaration, not silence.

It also closes, for attached tasks, the gap `sokar-frontend`'s `doc/Contract-Gaps.md` names: "a quiet task is shown
as a guess".

## Acceptance

- An attached task whose agent declares `at_rest` is listed `at rest` while its screen matches and `working` while
  it does not, by `task status`, `task list` and the daemon's `Task` on `Watch`. Seen to fail: an attached stub
  agent that answers and rests is still said `unknown`.
- A question to a person still reads as `waiting`, and a stopped container as `dead`, whatever the screen shows.
  Seen to fail: a task waiting for a clearance answer said `at rest`.
- An agent without `at_rest`, and an unattended task, read as they do today. Seen to fail: either changes its
  activity.

## To be checked

- Whether `at rest` is a new value of `activity` (adding one is compatible, and a client renders an unknown value)
  or `idle` read from the screen, so `sokar-frontend` shows nothing new.
- How often the screen is read for `list` over many tasks, since each reading is a call into a container.
