# B117 — What Clearing Leaves For A Person

**Status:** soon.

**What must be true.** A person who clears a project or this account learns of every deploy key this machine ever
handed to a forge for it, including a project that was unfollowed before, and is told what unreviewed work a
clear removes before it is gone.

## Acceptance

- A project is followed, given a deploy key, unfollowed, and then `sokar clear` runs: the key is named `for you`
  with its forge, repository and fingerprint. Seen to fail: the clear answering no key for that project.
- A clear that would remove work waiting at the gate or a task's unpushed work says which, by task and project,
  in the listing before anything is removed. Seen to fail: a listing without `--yes` that does not name it.

## To be checked

- Whether the machine keeps a record of the deploy keys it handed out after unfollowing forgets them, or the
  interface alone finds them by their title `sokar <machine> …` across the repositories a person's sign-in reaches.
- Whether `--force` is still needed to clear unreviewed work, as removing one task needs it, or the confirmation
  naming that work is enough.
