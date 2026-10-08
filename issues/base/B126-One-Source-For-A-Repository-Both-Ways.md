# B126 — One Source For A Repository, Both Ways

**Status:** implemented here; the walk in `walk9` is open.

**What must be true.** A task's repository has one source, and its work comes from there and goes back there. A
task started in a checkout takes its history from that checkout, and `sokar approve` puts the work back into it; Sokar
never reaches the checkout's remote, which the person pulls from and pushes to themselves. A task started for a remote
address, or for a followed project, takes its history from that remote, and approving pushes the work there; no local
repository is needed for it.

## Why

Today the two directions disagree. Starting in a checkout reads its `origin` URL and puts that into `default` as the
repository's upstream; the gate's mirror is cloned from that URL, and refreshed from it when a new task of the
repository starts. `sokar approve` in the checkout, though, pushes the work into the checkout itself. So commits made
in the checkout and never pushed never reach a task, not even its first start, while the work comes back to a place it
was never taken from (read from the code at `7b4192c0`, 2026-10-07).

A checkout is where a person works. When it is the source, the remote is theirs to deal with, and Sokar needs no
credential for it at all. When the remote is the source, the checkout is no part of the flow: the gate's mirror is the
only copy Sokar keeps, and the person pulls the approved work from the remote like anyone else's.

## Acceptance

- **Started in a checkout:** the mirror is seeded from the checkout's committed history, its local commits included,
  and refreshed from it; `approve` puts the work into the checkout as `sokar/<task>`, never the checked-out branch;
  nothing is fetched from or pushed to any remote of the checkout, and no credential is asked for. Seen to fail: a
  checkout with a commit `origin` lacks starts a task whose workspace does not hold that commit.
- **Started for a remote** (an address on the command line, or a followed project): seeded and refreshed from the
  remote; approving pushes there, with the credential the vault lends for it. Seen to fail: an approval that writes
  into a local checkout instead.
- **The `default` entry records its source** - a checkout's path, or a remote's address - and two checkouts of the same
  remote are two entries with two mirrors. Entries made before this keep working as remote-sourced ones.
- **Refreshed on demand and when a stopped task is started again** (today only a new task's start refreshes the
  mirror): the mirror is brought up to the source, and the agent is told through its mailbox what moved and that `git
  fetch sokar` brings it. For a checkout this is a local fetch, without the network.
- **`approve` names work the task holds that is not at the gate** (commits or uncommitted changes, as `task stop`
  already reports them), with how to bring it there, instead of only "nothing waits at the gate".
- `doc/commands.md` and the guide in a task say which source a task has and where its work goes.

## To be checked

- Which branches a checkout gives the mirror: all of its local branches, or the checked-out one and its upstream's.
- What a refresh does when the source rewrote a branch the agent built on: refuse, or move it and say so.
