# B03 — Credential Management

**Status:** open

The store is unlocked, entries are added, entries are named — and no value is ever
rendered. What can be shown is the name, the length, and whether it works.

## Acceptance

- Adding a credential never puts the value in a command line or a log.
- Listing shows names and lengths, never values.
- Unlocking rejects a wrong passphrase immediately rather than accepting it.
- A credential that is obviously too short to be real is questioned when stored.
- Locking is possible without restarting anything.

## Notes

The unlock check exists because a passphrase accepted now and rejected later reads as
a corrupt store.
