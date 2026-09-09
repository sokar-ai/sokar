# B32 — A Cached Passphrase That Says What It Is

**Status:** open, and it is a false statement rather than a missing feature. Found on 2026-09-09 by
reading a fix in the reference implementation and checking whether this code had the same shape. It
did, one level worse.

## What is wrong

`vault lock` prints one of two sentences:

```
locked    the next command asks for the passphrase again
nothing was cached
```

The second is printed whenever `KernelKeyring.forget()` returns false, and `forget()` returns false
whenever the keyring search returns `-1` — **for any reason at all**. The errno is captured and not
read:

```java
if (serial == -1L) {
    return false;
}
```

So a search that fails for a reason other than absence is reported to the operator as **"nothing
was cached"**, while the passphrase is still in the keyring. That is not a missing feature; it is
the tool making a security claim that is not true.

**It is reachable.** The key is created in the session keyring and linked into the user keyring,
and the search is against the *session* keyring. `pam_keyinit` revokes a session keyring when its
login session ends, so a process that outlives its login — a `vault serve` for a long task — is
searching a revoked keyring while the key it is looking for sits in `@u`, where nothing has touched
it.

## What the reference implementation did, and where it drew the line

A comparable project widened the *miss* set to `ENOKEY`, `EKEYEXPIRED` and `EKEYREVOKED`, and kept
everything else a failure — with exactly this reason:

> a `forget` that did so would report the passphrase cleared while it may still be cached

They arrived at it from the stricter side: only `ENOKEY` was a miss, and a revoked key produced a
loud failure where a quiet miss was right. This project starts from the other side, where
everything is a miss, and the same sentence is the argument for the correction.

**The direction matters and is not symmetric.** On the *read* path, treating a failure as a miss is
harmless: the passphrase is asked for instead. On the *forget* path it is a lie. The two paths
share a search today and must not share a verdict.

## What must be true

**Sokar never reports a cached passphrase gone unless it is gone.**

## Acceptance

- `vault lock` distinguishes three outcomes rather than two: cleared, nothing was cached, and
  **could not be established** — the last naming what to do about it.
- A search that fails for a reason other than absence, expiry or revocation is not reported as
  absence anywhere.
- A key that is expired or revoked reads as absent everywhere, including from `forget`, because a
  retry cannot change that verdict and there is nothing for an operator to act on.
- The read path still falls through to asking for the passphrase whatever the failure, and says
  nothing: there is nothing to report about a cache that could not answer when the answer is being
  asked for anyway.
- Where the key is looked for covers where it was put. Today it is created in one keyring and
  searched for in another, by way of a link that outlives neither.

## Notes

**`--for` makes expiry ordinary here.** `vault unlock --for 30m` sets a timeout on the key, so
`EKEYEXPIRED` is not an exotic state on this machine — it is what the documented, recommended
option produces half an hour later.

**This is the sixth instance of one shape in one day**: absence and failure rendered alike. The
others were the vault listing, `Credentials`, `Providers`, a task's unhanded work, and a log list
that hid two files. Worth stating as a class rather than as six accidents.

## To be checked

- **Whether the session keyring is the right one to search at all.** The key is linked into `@u`
  precisely so it survives; searching `@s` and relying on the link inherits the session's lifetime
  after all. Searching `@u` directly, or both, is a smaller change than it looks and may be the
  whole fix.
- **Whether tiers are worth borrowing.** A comparable project documents a chain — session keyring,
  user keyring, `systemd-creds`, a passphrase command — where this has one mechanism and a prompt. A
  chain is more to explain; it is also the answer to "the cache must outlive a login", which is a
  real deployment and currently has none.
- **What `vault lock` should exit with** when it cannot establish the state. Zero would make a
  script believe it locked something.
