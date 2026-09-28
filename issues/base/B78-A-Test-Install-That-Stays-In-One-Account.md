# B78 — A Test Install That Stays In One Account

**Status:** open, written 2026-09-28 at the operator's word, relayed by Agent Coordinator (QC1).

## Why

Every agent now has its own account on the shared ubuntu VM - `core`, `frontend`, `sluice` - and
`deploy` installs the `.deb` machine-wide. So each day-to-day test install replaces the `sokar` every
account runs from its next restart, and has to be announced for that reason. On 2026-09-28 one
package reached `frontend`'s daemon that way, installed for somebody else's test.

## What must be true

**`deploy` can install a build into one account only**, so that what one agent tests changes nothing
another account runs. The machine-wide install stays, for a test of the package itself.

## What is already so, read at `acb35e6` and checked against Agent Coordinator's reading

- **The package runs nothing when it installs.** No maintainer script in the `.deb` or the `.rpm`;
  hooks are registered per user by `sokar setup`. The SELinux module ships as files and an
  administrator loads it (`install-selinux-policy.sh`, which `sokar doctor` names).
- **A user's own copy already wins** for the hooks (`~/.local/bin`, `SokarPaths`), agents
  (`AgentDirectory`), providers, egress sets, transports and the message filter (the XDG data
  directory first).
- **The only fixed path is the user unit**: `/usr/lib/systemd/user/sokard.service` runs
  `/usr/bin/sokard`; a unit of the same name in `~/.config/systemd/user/` replaces it for that account.

## What the per-account mode has to change - measured here, not in the reading

- **`deploy` refuses exactly this today.** After installing it checks that `sokar` on the login PATH
  is the package at `/usr/bin/sokar`, and stops when a copy in `~/.local/bin` shadows it - which is
  the per-account install. In that mode the check inverts: PATH must find the account's copy.
- **`sokar` finds its helpers from the running binary** (`SokarBinary.path()` prefers the process
  that is itself `sokar`), so a task started from the account's copy starts the account's helpers -
  worth one measurement, not an assumption, since a daemon started from the machine-wide unit would
  not.
- **Machine-wide by nature, and staying so:** the SELinux module on Fedora, and what the package
  depends on - podman, dnsmasq, nftables. A per-account install cannot and should not change those.

## Acceptance

- `deploy` in the per-account mode installs into the account's own directories without `dpkg`,
  writes the account's user unit so its `sokard` is the account's copy, runs `sokar setup`, and says
  which binary PATH, the daemon and the hook descriptors now name - the same three, measured.
- A second account on the same machine runs what it ran before, measured by its `sokar --version`
  and its daemon's.
- The machine-wide mode is unchanged, and still announced with its hash.
