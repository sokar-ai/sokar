# B135 — The SELinux Module Loaded By The Package

**Status:** decided.

**What must be true.** Where SELinux is enabled, installing the `sokar` package loads Sokar's policy module, so a
task reaches the vault proxy with no manual step, and removing the package removes the module. `sokar doctor` reports
it present after a plain install.

## Why

Today the packages ship `selinux/sokar_socket.te` and `install-selinux-policy.sh` under `/usr/share/sokar/selinux`
and nothing more. Until a person runs `sudo /usr/share/sokar/selinux/install-selinux-policy.sh`, SELinux refuses a
task container's `connectto` on Sokar's sockets. The agent then reports an authentication failure, and `doctor` says
"MISSING - a task cannot reach the vault proxy". Asked on 2026-10-09: why does the install not do what is
known to be needed? Decided the same day: the package does it.

## The shape

- **`.rpm`, `%post`:** where `selinuxenabled` succeeds, compile and load the module, as `install-selinux-policy.sh`
  does today: `checkmodule`, `semodule_package`, `semodule -i`. It is compiled on the target on purpose, since a
  `.pp` is tied to the policy version of the machine that built it, and Sokar is built where there is no SELinux.
  Where SELinux is not enabled, nothing happens.
- **`.rpm`, `%postun` on removal** (not on upgrade): `semodule -r sokar_socket` where the module is loaded.
- **Requirements:** `checkpolicy` and `policycoreutils` in the `.rpm`.
- **`.deb`:** the same in `postinst` and `postrm`, where SELinux is enabled, which is rare on Debian and Ubuntu. The
  tools there are `Recommends`, not `Depends`, so a machine without SELinux does not install them.
- **A failure to load is not a failed install.** The package says what failed and names the script, and `doctor`
  reports it as today. Refusing the whole install over the module would leave a machine with nothing.
- **The script stays**, for a machine where SELinux is enabled after the install, and the docs say so.

## Acceptance

- On Fedora 43 with SELinux enforcing: `dnf install` of the package, then a task reaches the vault proxy with no
  manual step, and `doctor` reports `selinux policy  installed`. Seen to fail first with today's package: the same
  task fails to authenticate, and `doctor` reports it missing.
- `dnf remove` leaves no `sokar_socket` in `semodule -l`.
- On Ubuntu 26.04 and Debian 13 without SELinux: the install changes nothing of SELinux, and `doctor` reports it not
  needed.
- The package check (`dist/package-check`) reads `%post`/`%postun` and `postinst`/`postrm` and finds the module's
  load and removal.
