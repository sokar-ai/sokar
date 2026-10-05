# B99 — Packages Signed And Checked

**Status:** soon.

**What must be true.** An operator installing Sokar or an agent gets only packages, and a setup script, whose
signature the machine has checked against the repository's packaging key.

## Why

From Codex's review (PJ19): `sokar-buildtools`' "Fedora package installation disables signature checking", and the
agent repositories' "Release RPMs are built with signing disabled", shared with Sokar. Placed Soon, with the agents'
side in their repositories.

Sokar's RPMs are built with signing off (`dist-rpm/pom.xml`, `rpm.sign.skip`), so the Fedora repository is set up
with `gpgcheck=0` - in `sokar-setup.sh`, in the documentation (`doc/getting-started.md`, "deliberate, and
temporary"), and on every rented machine a leg installs on (`AgentLeg` in `sokar-buildtools`). Integrity rests on
TLS to the project's own repository; `repo_gpgcheck` is not set either.

## The shape

- **Snapshot and release RPMs of Sokar and of every agent are signed** with the repository's packaging key.
- **Every place that sets up the repository checks them**: `gpgcheck=1`, `repo_gpgcheck=1` and the key named by
  `gpgkey=`, in `sokar-setup.sh`, the documentation and the legs, moved in one change.
- **Every repository that publishes to the same package repositories signs from the same day**: `sokar`, the matrix
  transport and its homeserver, the message filter, the three agents. Switched on for one alone, the setup refuses
  the others. So the key, the CI secret that carries it and how a repository signs with it are written here first,
  and every repository moves together.
- **The setup script is signed too.** `sokar-setup-<version>.sh` is the one Sokar interface that is a file, and a
  wizard runs it as root on a machine that has nothing of Sokar's yet (Codex's review of `sokar-frontend`, PJ19).
  Each version gets a detached signature beside it, made with the same key, so a caller holding that public key can
  check exactly the bytes it is about to show and run; `latest` is only a pointer to a version.

## Acceptance

- Every snapshot and release RPM of Sokar and of every agent carries a signature by the packaging key. Seen to
  fail: `rpm -K` on a published package reports it unsigned or signed by another key.
- `sokar-setup.sh`, the documentation and the legs set `gpgcheck=1`, `repo_gpgcheck=1` and `gpgkey=`. Seen to fail:
  installing a package with a broken or missing signature from the configured repository succeeds.
- The key, the CI secret that carries it and how a repository signs with it are written here before any repository
  switches signing on, and `sokar`, the matrix transport and its homeserver, the message filter and the three agents
  sign from the same day. Seen to fail: a setup on a clean machine refuses a package from one of them.
- A test refuses an unsigned package before it is published. Seen to fail: the build publishes an RPM with signing
  skipped.
- Each `sokar-setup-<version>.sh` has a detached signature beside it, made with the same key. Seen to fail:
  verifying a published setup script against its signature with the public key fails, or a version has none.
