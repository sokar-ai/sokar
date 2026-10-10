# B158 — A Refusal That Knows The Next Command Offers It

**Status:** implemented here for `vault login`; the other refusals listed below are open.

**What must be true.** Where a command refuses only to tell the person which command to run next, and Sokar already
knows that command's answer, it asks at a terminal and does it, instead of making the person type it. Without a
terminal it refuses as before, naming the commands.

## Why

Found in the joint test, 2026-10-10:

    $ sokar vault login claude
    sokar: 'claude' is already signed in on this machine. Copy what it has with 'sokar vault import claude',
    which logs in nowhere - or pass --force to authorize again, which may or may not invalidate the
    credential already here

The person had to read the sentence, then type one of two commands that Sokar had just named. B133 does the same for a
shut vault, and B132 for a grant missing at a task's start.

## The shape

- `vault login <agent>` on an agent already signed in asks at a terminal: import what it has (the default, as it logs
  in nowhere), log in again (`--force`'s meaning, said with its risk), or cancel; then does it.
- Without a terminal, or with `--force`, nothing is asked.
- The same rule for the other refusals of this kind, each where the next step is unambiguous and not destructive:
  - a task's start refused for a missing grant, sign-in or key: `Grant it first with 'sokar vault authorize …'`,
    `Sign in first with 'sokar vault login …'`, `Store its key first with 'sokar vault put …'` (`TaskLaunch`);
  - `task resume` of a task whose vault is shut, `Unlock it with 'sokar vault unlock'` (`TaskResumeCommand`), where
    B133 has not reached yet.
- Not of this kind, and left as refusals: `task remove` of a running task (stopping is a decision), and a start whose
  name has earlier work waiting (approve or reject is a review, not a command to run).

## Acceptance

- `vault login`: seen red first with a terminal stood in - the import is offered and runs, "again" logs in with
  `--force`'s meaning, "cancel" does nothing; without a terminal the refusal names both commands as before.
- Each further refusal above gets the same test when it is built.
