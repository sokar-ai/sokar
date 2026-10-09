# B131 — The Oldest System A Package Promises

**Status:** implemented here.

**What must be true.** The `.deb` and the `.rpm` install only where `sokar`, `sokard` and the stub agent can start:
they declare the oldest C library and zlib the binaries need, a build that would need more fails before it is
published, and the oldest system Sokar supports is written down.

## Why

Today the packages declare podman 5 and the tools around it, but no C library and no zlib. The binaries link both
dynamically; the hooks are static. Measured on a build of `b672aba4` on Ubuntu 26.04 (glibc 2.43), with
`objdump -T` and `readelf -d`:

    apps/app/target/sokar               GLIBC_2.34   libz.so.1 libc.so.6
    daemon/target/sokard                GLIBC_2.34   libz.so.1 libc.so.6
    agents/stub/target/sokar-agent-stub GLIBC_2.34   libz.so.1 libc.so.6
    hooks/target/sokar-hook-*           static

2.34 is low today because native-image asks little of the C library. Nothing holds it there: a newer GraalVM, a new
dependency or a newer build host can raise it, and apt or dnf would still install the package. It would then fail
only at start, on the machine of somebody who did everything right. The build job runs on `ubuntu-latest`, which
moves to 26.04 by itself.

The agent packages and the message repositories already solve this: a pinned runner, the floor measured and
declared by hand in the packages, and a test that fails when a binary needs more (`NativeLinkageCheck` in
`sokar-claude-code`, `PackagesIT` in `sokar-message-matrix`).

## The systems

Decided on 2026-10-09: **Ubuntu 26.04 is the minimum**, the first Ubuntu with podman 5 (24.04 has
4.9.3), and **Debian 13 and Fedora 43 and 44 besides it**, confirmed the same day:

    system         podman   glibc   why
    Ubuntu 26.04   5.7.0    2.43    the first Ubuntu with podman 5
    Debian 13      5.4.2    2.41    podman 5, and the lowest glibc of the set
    Fedora 43      5.8.8    2.42    the oldest Fedora still supported
    Fedora 44      5.8.7    2.43    current

Fedora 40 to 42 have podman 5 but are end of life; Debian 12 has podman 4. **So the floor the packages may declare
is at most glibc 2.41**, Debian 13's; today they would declare 2.34, what the binaries need.

## The shape

- **The floor, measured and declared:** `libc6 (>= 2.34)` and `zlib1g` in the `.deb`'s `Depends`;
  `libc.so.6(GLIBC_2.34)(64bit)` and `libz.so.1()(64bit)` in the `.rpm`'s `Requires`, from one property in the pom,
  as the agents do.
- **A check that fails the build:** every binary the packages carry is read with `readelf`; a `GLIBC_` or `ZLIB_`
  version above the declared floor, or a needed library no package declares, fails `dist-check` before anything is
  published. Seen red first, with a floor set below what the binaries need.
- **A ceiling on the floor:** the same check refuses a declared glibc above 2.41, so raising the floor past
  Debian 13 is a decision, not a side effect.
- **The runners pinned:** `ubuntu-24.04`, not `ubuntu-latest`, until the floor is measured on 26.04 runners and
  found still within it.
- **Written down:** the supported systems in `doc/getting-started.md`, where installing is already explained per
  distribution.

## Acceptance

- `dpkg-deb -f … Depends` and `rpm -qp --requires` name the C library and zlib with the measured floor.
- A build whose binaries need a newer `GLIBC_` than the floor fails in `dist-check`, seen on purpose.
- The packages install and `sokar doctor` runs on Debian 13 and Fedora 43, each in a container or on a machine, once.

The same pattern, from its first version, for `sokar-build-github`'s package (its GH01).

## As built, 2026-10-09

- **The check is shared:** `sokar-release check-linkage` in `sokar-buildtools` (`--declare LIBRARY=PREFIX:FLOOR`,
  `--ceiling PREFIX=VERSION`, the binaries). A static-pie binary, as the hooks are, needs nothing. The agents' own
  `NativeLinkageCheck` copies can move to it.
- **Here:** `package.glibc.floor` 2.34, `package.zlib.floor` 1.2.2 and `package.glibc.ceiling` 2.41 in the root
  pom; the `.deb` depends on `libc6 (>= 2.34), zlib1g`, the `.rpm` requires `libc.so.6(GLIBC_2.34)(64bit)` and
  `libz.so.1(ZLIB_1.2.2)(64bit)`; `dist-check` runs `check-linkage` on the six binaries under `-Pdist`. The
  build's three jobs run on `ubuntu-24.04`. `doc/getting-started.md` names the supported systems.
- **Seen to fail first:** `-Dpackage.glibc.floor=2.33` stops the package build at `check-linkage`, naming
  `GLIBC_2.34` for `sokar`, `sokard` and the stub.
- **Measured once:** the packages install and `sokar --version` runs in clean `debian:13` (glibc 2.41, podman
  5.4.2) and `fedora:43` (glibc 2.42, podman 5.8.4).
- **Every build:** `dist/package-check` installs in `debian:13` and `fedora:43` beside `ubuntu:26.04` and
  `fedora:44`, each pinned by digest; Debian from its own mirrors, not Ubuntu's.
