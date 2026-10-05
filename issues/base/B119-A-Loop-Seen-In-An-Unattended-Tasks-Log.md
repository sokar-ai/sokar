# B119 — A Loop Seen In An Unattended Task's Log

**Status:** soon.

**What must be true.** A wait in the acceptance kit stops early when an unattended task's agent repeats a failing
tool call that its screen does not show, read from the task's `task.log` against a marker the scenario declares.

## Why

The kit's waits stop on a loop by counting what scrolls into each task's pane history: a declared tool-failure
marker three times since the step began, or one line more often than `sokar.acceptance.repeats` allows. That sees
omp, which draws every failed call, and Pi, whose failures scroll. It does not see Claude Code: measured on 2.1.267,
two failed calls collapsed into one line updated in place, so nothing new scrolls and a loop within one turn runs to
the step's time limit. An unattended Claude writes each tool result to `task.log` as JSON, where a failed one carries
`"is_error":true` and can be counted.

An attached session writes no `task.log`, so this does not cover it. There, the step's time limit stays the bound.

## Acceptance

- A scenario declares what a failed tool call looks like in a task's log, apart from the screen's marker:
  `Given a tool failure in the task's log looks like "\"is_error\":true"`.
- While a terminal or script wait runs, the kit reads the `task.log` of every task the account runs, and stops once
  the declared log marker has been added three times since the step began, naming the task and the line. Seen to
  fail: a fake log gaining three failed results leaves the wait running to its end.
- A log that does not exist, as for an attached session, and lines that were there before the step, count nothing.
  Seen to fail: a wait stopping on failures logged in an earlier step.

## To be checked

- Whether the log is read through `sokar task logs` or from the state directory directly, as the kit's reads of a
  task's state are today.
