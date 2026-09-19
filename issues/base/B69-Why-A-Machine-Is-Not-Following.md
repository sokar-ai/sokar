# B69 — Why A Machine Is Not Following

**Status:** built on 2026-09-18. A machine that is not applying a project's
configuration says **why** as a named reason and **about which commit**, rather than as prose a
screen has to parse.

## What is there and what is not

Following is reported today by `Following()`, per project: `name`, `url`, `commit`, `at`,
`outcome`, `detail`. The commit applied and when it was tried are therefore already answered. What
is not:

1. **A refusal does not say which commit was refused.** `Reconcile` records the commit *in force*
   on every outcome, which is right for "what is running" and useless for "what was rejected". The
   verdict knows it and throws it away.
2. **Every kind of refusal is one outcome.** `ConfigurationGate` already distinguishes
   `NOT_SIGNED`, `UNKNOWN_KEY`, `NO_ANCHOR` and `UNREADABLE` - and `Reconcile` collapses all four
   into `REFUSED` with a sentence. *Nobody signed this* and *somebody signed this with a key you
   were never given* are different events: the first is usually a mistake, the second is either a
   key that has moved or somebody trying to put a project file past the machine.
3. **A locked vault is not a reason at all.** It is a hint appended to the text of an
   `UNREACHABLE`, because a private repository cannot be fetched without the credential. So a
   screen showing "cannot reach the repository" is showing something that is true and misleading:
   nothing is wrong with the network or the URL.
4. **None of it is on `Projects()`**, so a project view has to join two calls to say whether the
   project it is showing is the one in force.

## What must be true

1. **A refusal names the commit it refused**, beside the one still in force. Both, not one: what is
   running and what was rejected are different questions and a screen asks both.
2. **The reason is a value, not a sentence.** At least: unsigned, signed by a key this machine was
   not given, no key pinned at all, the repository could not be read, a history rewrite is waiting
   to be accepted, the repository could not be reached, and **this account's vault is shut**.
   `detail` stays, for the person.
3. **A locked vault is its own reason**, separate from unreachable, because the action is different:
   one is *unlock your vault*, the other is *look at the network or the URL*.
4. **Which key signed it, when that can be said.** A public key is not a secret and naming it is
   what turns *"signed by a key you were not given"* into something a person can act on - they
   compare it with the one they meant to pin. Only the fingerprint; never anything from the vault.
5. **The follow state is on `Projects()`**, so a project view answers from one call. `Following()`
   stays: it is the account's list of what it follows, which is a different question from what one
   project's state is.

## Built, 2026-09-18

**The values existed and were discarded.** `ConfigurationGate` already told the four refusals apart;
`Reconcile` collapsed them into one and threw the refused commit away with them. What this cost was
naming them once and carrying them - `NOT_SIGNED`, `UNKNOWN_KEY`, `NO_ANCHOR` and `UNREADABLE` are
outcomes now, beside `VAULT_LOCKED`, and the result carries the refused commit in a field of its own.

**A shut vault is its own reason, and only when there is a vault.** Found by a test: a machine with
no vault at all was reported as locked, which would send somebody to unlock a vault they have never
made. It is `VAULT_LOCKED` only when a vault exists and cannot be opened.

**`needsAPerson` is on the reply**, because "it failed" and "nothing will change until somebody
acts" are different things: an unreachable repository may answer on the next pass by itself, a
refused signature never will.

**One rendering for two questions.** `Following()` and `Projects().following` are the same values
through the same method, so the account's list and a project view cannot come to disagree.

**`doctor` reads the outcome rather than deciding again.** It worked out "the vault is shut" from
the vault itself, beside a rule that now exists in the outcome - two answers about one machine that
could differ.

## Acceptance

- An unsigned commit and one signed by an unpinned key produce **different** named reasons.
  **Met**, and both are tested against real git and real signatures.
- A refusal names the commit refused and the commit in force, and they are not the same field.
  **Met.**
- An account whose vault is shut reports that as its own reason, with no suggestion that the
  repository could not be reached. **Met**, with the "only when a vault exists" rule the tests
  forced.
- The reason for a project's state can be read from `Projects()` alone. **Met.**
- A refusal names the key's fingerprint where one was presented, and nothing else about it.
  **Met**, read out of git's own sentence by shape rather than by position.
- `sokar doctor` keeps saying the same things it says today, from the same values rather than from
  a second rule. **Met.**

## Notes

**Asked for by the interface**, which had decided how to show it before asking: a refused signature
is a state on the project and an item under *Needs you*, not a line in `doctor` that is missed while
the project keeps running on its old state. That is the right reading - a machine quietly refusing
configuration is a machine drifting from what its operator thinks it has.

**The values already exist one layer down.** This is mostly not new work: `ConfigurationGate`
computes the distinction and `Reconcile` discards it. What it costs is deciding the names once and
carrying them to the wire.
