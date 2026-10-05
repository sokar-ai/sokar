# B102 — A Vault Cleared In One Step

**Status:** now.

**What must be true.** `sokar vault clear --yes` on a real machine leaves it as a new one: the vault, its
cached passphrase and device shares, and the accounts a transport keeps in it are gone, and `sokar vault init`
starts afresh - as the unit tests already show against temporary paths and a fake runner.

## Acceptance

- Walked on a rented machine with a project that has messages on a homeserver: a listing without `--yes`
  names the vault file, its backup and lock, the cached passphrase, a grant, the transport's account and a
  stopped task's token, and removes nothing; with `--yes` the three files are gone, `keyctl` finds neither the
  passphrase nor a device share, the homeserver no longer knows the account, and `vault init` followed by a
  task start in that project succeeds. Seen to fail: a file or a keyring entry still there, or a start refused
  because the homeserver knows an account the new vault holds no token for.
- On the same machine, a running task holding a token from the vault makes `vault clear --yes --force` refuse
  and name that task, and the vault file is still there afterwards.
