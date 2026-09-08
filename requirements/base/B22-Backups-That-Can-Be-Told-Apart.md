# B22 — Backups That Can Be Told Apart

**Status:** open. `gate backup` and `gate restore` exist. What is missing is everything around
them that makes a backup usable rather than merely taken.

## What is missing

- **Listing what has been taken**, with enough to tell two apart: when each was taken and what it
  holds. Nothing answers that today, and an interface cannot offer to restore from a list it cannot
  read.
- **Deleting one**, behind a confirmation that names what it affects.
- **Synchronising with the upstream as one action, with an outcome.** `Project.behind` is measured
  on the daemon's own timer, so something already fetches — the open question is whether that can
  be triggered and reported.
- **A synchronisation that would discard work says so before it runs**, rather than after.

## The one with teeth

That last one is the criterion that matters. **Unreviewed pushes exist only in the mirror.** A
restore or a sync that overwrote them would destroy the only copy of work an agent did, and the
gate's whole promise is that such work waits for a person.

The shape already exists twice: `DeleteProject` refuses with `HOLDS_WORK` and names what it found,
and `Stop` answers the same for a task with commits that never reached the gate. This should be a
third use of it, not a fourth invention.

## Acceptance

- Backups are listed with enough to tell two apart.
- Restoring names what it will replace before it replaces it.
- A restore or a synchronisation that would discard unreviewed work is refused by name, and names
  the refs at stake.
- Deleting a backup is confirmed against what it holds.
- A triggered fetch is a separate operation from a listing, and a listing still reaches no network.

## Notes

The last point is not stylistic. A listing that fetched would make the queue cost what a listing
must not, and there is an architecture rule enforcing it - which is exactly why a triggered fetch
has to be its own method rather than a flag on a read.

## To be checked

- **Whether a fetch can be triggered at all**, or whether the upstream distance is deliberately
  only ever a background measurement. Both are defensible; the difference decides whether an
  interface offers a button or explains a timer.
