# F15 — Secret Store Control

**Status:** open

The protected store holding credentials has states a person must be able to see and
change: locked, unlocked, and how its unlocking is remembered between sessions.

## Acceptance

- The current state of the store is visible without performing any action on it.
- The store can be unlocked, and locked again, from the interface.
- How the unlocking is remembered — for this session only, or persisted so it survives
  a restart — is an explicit choice with its trade-off stated.
- The recovery secret can be revealed once, deliberately, and the interface records
  that the person confirmed they have stored it. Until they confirm, the interface
  keeps saying so.
- The passphrase protecting the store can be changed, and the change states what it
  affects.
- The revealed secret is not written to any log, transcript or operation record.
- Two parts of the interface asking about the store at once never leave a stale answer
  displayed over a newer one.

## Notes

The unacknowledged-recovery-key reminder is not a nag: a store whose recovery secret
was never written down is a total loss waiting for a disk failure.
