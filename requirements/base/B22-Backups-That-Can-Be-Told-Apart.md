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

## Built, 2026-09-08: listing and deleting, and the thing that made it possible

**Nothing recorded that a backup had ever been taken.** `gate backup <file>` writes a bundle
wherever the operator names it and forgets it the moment the command returns, so *"what has been
taken"* was not a question this machine could answer - it had no data to answer it from. The
listing was never the missing part; the record was.

- **`Backups(project)`** answers what was taken, newest first, from a record written when each one
  was taken. Empty is ordinary: a project nobody has backed up, and also one backed up by a Sokar
  older than the record.
- **`DeleteBackup(project, bundle, dryRun)`** removes the bundle and the record, previews first,
  and **refuses a path no record names** - otherwise it would be a file-deletion primitive wearing
  a backup's name.

**A record is not the bundle**, and the listing says so rather than implying otherwise. The file
can be moved, deleted or replaced afterwards and nothing here would know, so every answer checks
the disk: `present` and the size are read now, `taken` and `refs` are what was true then. A bundle
somebody moved is **shown as absent rather than dropped** - it was taken, somebody moved it, and
that is exactly what they need to see.

**When the file cannot be deleted the record stays.** Forgetting it would hide a bundle that is
still on disk, and a backup nobody can see is worse than one somebody has to delete twice.

### Two things the tests found

- **Two guards covering for each other.** A length check in front of a try/catch, both handling a
  half-written line: neither could be shown to matter, because removing either left the other. The
  length check was removed; the remaining one is proven.
- **A path recorded as typed.** `sokar gate backup out.bundle` is run from a project directory and
  the record is read by a daemon started somewhere else, so a relative path would name a different
  file to whoever read it - or none, which reads as a backup somebody deleted.

## Still open here

- **Synchronising with the upstream as one action.** Answered in principle - a fetch *can* be
  triggered, and must be its own method rather than a side effect of a listing - and not built.
- **A restore that would discard unreviewed work.** The refusal shape exists twice already
  (`DeleteProject`, `Stop`); this should be a third use of it rather than a fourth invention.

