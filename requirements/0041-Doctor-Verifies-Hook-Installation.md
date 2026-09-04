# 0041 — Doctor Verifies Hook Installation

**Status:** open

`sokar doctor` reports the directory the hook *binaries* were installed in. It never
checks whether the hook *descriptors* were installed, or whether the container
runtime was told where to read them. Both are written by `sokar setup`, and neither
is part of installing the package.

So on a machine where the package is installed and `setup` was never run, doctor is
entirely green and every task container starts with **no firewall at all**: the
ruleset is rendered, nothing loads it, egress stays open, and the resolver never
comes up. The one thing Sokar exists to guarantee is absent, and the command whose
job is to say so reports success.

The failure is silent in the direction that matters. A missing hook cannot make a
task fail loudly, because a container with no restrictions runs *better* than one
with them.

## Acceptance

- Doctor fails, with a non-zero exit, when the descriptors are missing or the
  runtime is not configured to read them, and names `sokar setup` as the fix.
- It distinguishes "never installed" from "installed and pointing at binaries that
  are not there", because a package upgrade can produce the second.
- It checks what the runtime will actually read, not what Sokar would have written -
  a descriptor in a directory outside the configured `hooks_dir` is not installed.
- A task run refuses to start rather than silently running unprotected, if that can
  be established without a per-run cost.

## Notes

Found on 2026-09-04 on a fresh Fedora 44 VM. The packages installed cleanly,
`sokar doctor` exited 0, and the acceptance suite then reported four failures whose
single cause was that `/etc/containers/oci/hooks.d` was empty and no `hooks_dir` was
configured. Doctor had already been run and had said nothing.

Installing the descriptors per operator rather than system-wide is deliberate -
rootless podman cannot write the system path - so the gap between "package
installed" and "Sokar usable" is inherent to the design and has to be reported
rather than removed.

## To be checked

- Whether a task run can verify the hook actually fired, rather than only that it
  was registered. Registration is checkable before the run and proves less: the
  descriptor can be present and the hook still fail, which is what the acceptance
  suite catches today by reading `hooks.log` afterwards.
