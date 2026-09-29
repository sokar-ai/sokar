# B83 — A Leg Of Ten Minutes

**Status:** implemented 2026-09-29, written the same day at the operator's request, after a `Build`
run took 49 minutes. Open until a `Build` run shows both legs with the cache and four accounts.

## What a run spends its time on

Measured from the logs of run `98951381363` (commit `28043c0`, `cpx42` legs; the full breakdown is
kept in the operator's `.build-timings.md`):

- The two legs are the critical path: about 26 minutes each, and `Publish` waits for both.
- In a leg, **the acceptance suite is 19.5 of 25.5 server minutes**: 196 scenarios, one after another,
  the same 196 on both operating systems. Building on the server is 3.6 minutes; renting, booting and
  deleting the server about one.
- The scenarios mostly wait - for images to build, for bounds to run out, for a screen to stay steady -
  rather than compute. A faster machine does not shorten waiting: a `ccx33` leg (8 dedicated cores,
  32 GB) ran the scenarios no faster than a `cpx42` (8 shared, 16 GB), measured 2026-09-29.

So what shortens a leg is running scenarios **at the same time**, not running them on a faster CPU.

## The unit of isolation is already there: an account

Everything Sokar keeps is per account - rootless podman and its image store, `$XDG_RUNTIME_DIR` and the
state directory, the daemon and its socket, the kernel keyring, the vault, the mailboxes. The shared
test VM runs four accounts side by side (`core`, `frontend`, `sluice`, `smith`) without one seeing
another. So a leg can run features in parallel, each under an account of its own, and prove on the way
that several people can use one machine - which is a claim the product makes and no test made.

## The images several accounts need, fetched once

Each account has its own image store, so N accounts would each pull every base image - N times the
time, and N times against Docker Hub's rate limit for anonymous pulls, from one address. Measured on the
Fedora VM (Fedora 44, podman 5.8.1, SELinux enforcing) on 2026-09-29:

| How an account gets `ubuntu:24.04` and `alpine:3.20` | Result |
|---|---|
| A read-only store filled by root (`additionalimagestores`) | Seen, but cannot be run or built on: the layers belong to root, which a rootless account's namespace does not map, so the container's mount points cannot be made. No SELinux denial. Not usable. |
| An archive each account loads (`podman load`) | 2.1 s; works, but the loaded image's digest differs from the registry's, so a pin by digest no longer matches. |
| **A pull-through cache on the machine** (`registry:2` as a proxy for `docker.io`, named a mirror in `/etc/containers/registries.conf.d/`) | First pull 8.6 s, every later pull by any account **1.3 s**; the digests are the registry's own, so pins hold; rootless podman reads the system-wide `registries.conf`, so no account needs anything of its own. |

## What must be true

1. **A leg's machine carries a pull-through cache for `docker.io`**, started at boot, filled when the
   snapshot is built with every image a leg pulls, and named a mirror system-wide. A leg, and each account
   in it, pulls from it rather than from Docker Hub. The cache's own image is pinned by digest, moved by
   the same job as the snapshot's other pins.
2. **A leg runs its features in parallel under several accounts**, each prepared exactly as the one
   acceptance account is today - lingering, its own subuid and subgid ranges, the run's key, `sokar setup`.
   How many is a setting of the leg.
3. **Scenarios within a feature keep their order and their account**: several features share state
   across their scenarios on purpose.
4. **What touches the whole machine runs alone**, after the parallel part - a reboot above all - and is
   marked so in the feature, not in a list kept elsewhere.
5. **A failure names the account it ran under**, so a failure caused by two accounts meeting is told
   from one caused by the scenario itself.
6. **Nothing an agent repository runs changes** unless it asks: the kit keeps one account as the default,
   and more accounts are something a leg opts into.

## Acceptance criteria

- A leg's log shows every pull answered by the machine's cache, and none by Docker Hub, on both operating
  systems.
- A leg with four accounts runs the same 196 scenarios, all green, and its acceptance part takes a
  fraction of the serial time; the saving is recorded beside the serial run's timings.
- A scenario marked as touching the whole machine never runs beside another.
- A deliberately broken scenario's failure names its account.

## How it is built

- **The cache** (point 1): the snapshot starts `registry:2` as a pull-through proxy on `127.0.0.1:5000`
  under a system unit, names it a mirror for `docker.io`, and pulls every image a leg needs through it.
- **The accounts** (points 2, 3, 6): the kit reads `sokar.acceptance.users`, a comma list; without it the
  one `sokar.acceptance.user` serves every scenario as before. Each worker thread takes an account the
  first time it asks and keeps it for the run, with one connection per account. A feature's scenarios run
  in order on one thread (`execution-mode.feature=same_thread`), so a feature keeps its account. More
  threads than accounts fail loudly. The suite turns parallel runs on with `sokar.acceptance.parallel` and
  `sokar.acceptance.parallelism`, both off by default.
- **The leg** (points 2, 4): `leg --accounts <n>` prepares `accept2..n` as root, as the build user was,
  copying the build user's install. It then runs `not @restart` over all accounts at once, and `@restart`
  alone as the build user afterwards. This repository's legs pass `--accounts 4`.
- **Naming the account** (point 5): a failed scenario's report says which account and thread it ran on.

## Measured

A leg on `ubuntu`, `cpx42`, four accounts, run from a workstation on 2026-09-29 (commit before the
squash; the snapshot predated the cache, so the three extra accounts pulled from Docker Hub):

| | Serial, run `98951381363` | Four accounts |
|---|---|---|
| Features beside each other (`not @restart`) | - | 5m02s, 193 passed |
| What touches the whole machine, alone (`@restart`) | - | 0m49s, 4 passed |
| **Acceptance scenarios** | **19m16s** | **5m51s** |
| Whole leg, server created to deleted | 25m36s | about 11 minutes |

The extra accounts were prepared - `useradd` gave each its subordinate ranges on the snapshot - and no
scenario failed from two accounts meeting. The first pull through the cache on a leg is still to be seen
once the snapshots are rebuilt with it.

## To be checked

1. How many accounts before a machine's own limits - memory, the image builds each account runs once -
   eat the gain. Four is the first guess; the leg's server type may have to follow it.
2. Whether any scenario not yet marked touches machine-wide state: a fixed port, `/etc`, the host's
   nftables, `sudo`. The first parallel runs will say; each one found is marked, not worked around.
3. Whether the agent repositories' legs want the same, once this is proven here.
