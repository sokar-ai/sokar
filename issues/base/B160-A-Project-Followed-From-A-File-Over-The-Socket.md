# B160 — A Project Followed From A File Over The Socket

**Status:** open. Blocks `sokar-frontend` F104.

**What must be true.** A client follows a project from a file as `sokar project follow NAME FILE` does: from a path on
the machine, and from a bundle the person has on their own computer, which the client hands over in the call itself.

## Why

Since B137 an offline project is followed only from a file, and the interface follows only from an address. So an
offline project cannot be made from the interface at all. Two pieces are missing on Sokar's side, found on 2026-10-10:

- `Follow(name, url, ...)` already passes `url` to the same `Reconcile` as the command, so a path counts as a file.
  But the daemon does not make a relative path absolute, as the command does, and the contract does not say that `url`
  may be a path.
- A bundle the person has on the computer the client runs on cannot reach the machine outside a task: `HandIn` writes
  only into a running task's `/sokar/files`.

## The shape

- **A path:** `Follow` resolves a relative `url` that is a path against the account's home, as the command resolves
  it against its working directory, and records it absolute. The comment on `Follow` in `20-project.varlink` says
  that `url` may be an absolute path on the machine, a bundle or a directory, read once and never fetched in the
  background.
- **A bundle's bytes:** `Follow` takes the bundle itself, in a parameter beside `url`, so no file outlives the call.
  The daemon writes it where only the account reads it, follows from it, and keeps it as the followed source for a
  later follow from a newer bundle. A refusal - an offline definition from an address, a signature that does not
  check, a bundle that is no bundle - is said by name as for a path.
- A size limit for the bundle, said in the contract and refused by name when exceeded; the interface shows a large
  upload with its progress.

## Acceptance

- Seen to fail first: a relative path sent to `Follow` follows the file the account means; a bundle's bytes in the
  call follow an offline project; an oversized bundle is refused by name; the same signature checks as for a path.
- `InterfaceDescriptionTest` holds the new parameter and the refusal.

## To be checked

- The size limit, and whether the bytes come in one call or in parts.
