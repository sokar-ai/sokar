# B130 — Two Refusals By Name

**Status:** decided.

**What must be true.** Two refusals that a returning task name runs into reach a client by name, with what it needs
to offer the next step, instead of as `Failed` with git's text or a general start failure. An interface can then
offer the waiting push or the next free name in the same dialog, and the CLI says the same in words.

## Why

A task's name can come back: a task that could not return after a restart is removed, and a new one starts under
its name. Two things of the earlier task can still be there.

- **Its branch.** The checkout or the upstream still holds `sokar/<task>` from earlier work. Approving the new
  task's work onto it is a non-fast-forward. Today `Approve` answers `Failed` with git's raw output
  (`! [rejected] … (non-fast-forward)`), and `sokar gate approve` prints the same. Nothing is written either way, so
  it is safe, only badly said.
- **Its work at the gate.** For a guarded or offline task, work of the earlier task still waits at the gate. A new
  task of that name is refused at its start, as the general start failure with the daemon's words, exit 65 on the
  CLI.

## The shape

- **`BranchExists(branch: string, at: string)`** in `35-gate.varlink`: `Approve` refuses before it pushes when the
  target branch exists and is not an ancestor of the work. `at` is the commit the branch holds. The CLI says
  `sokar/<task> already holds <at>, from earlier work; approve onto another branch with --branch sokar/<task>-2`.
- **`EarlierWorkWaits(task: string, commit: string, subject: string)`** in `15-task.varlink`: `Start` refuses with
  it where it refuses today. The CLI's words and exit 65 stay as they are.
- **What is offered:** the next free name, `sokar/<task>-2`, then `-3`, for the branch, and the next free task name
  for the start. It is a suggestion only; the person chooses. Never a force over the old branch, since it can be
  someone's reviewed work.
- One contract change for both, in the same handover.

## Acceptance

- `Approve` onto a branch that holds unrelated work answers `BranchExists` with the branch and its commit, and
  nothing is pushed. `sokar gate approve` prints the sentence above and the suggested branch.
- `Start` of a guarded task whose name's earlier work waits at the gate answers `EarlierWorkWaits` with that work's
  commit and subject, and nothing is created.
- Seen to fail first: today's `Failed` for the branch and today's general start failure for the waiting work, each in
  a unit test of the daemon's side, then green with the named error.
- The interface and the plugin show each refusal and offer the next step, tested on a handover against a real daemon.
