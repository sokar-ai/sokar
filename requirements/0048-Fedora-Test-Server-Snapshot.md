# 0048 — Fedora Test Server And Its Snapshot

**Status:** **built.** The snapshot exists and is verified; what it took is below.

[0047](0047-Separate-Repositories-And-CI.md) establishes that SELinux enforcing is the one thing
a GitHub-hosted runner can never cover, and that the answer is an on-demand Hetzner server driven
over SSH from a hosted controller job. This is the half that makes that affordable: **a prepared
snapshot**, so a run boots a machine that is already able to build and test rather than spending
ten minutes installing before it can start.

Two scripts, and this requirement is the first:

| | |
|---|---|
| **this one** | provision a server, customise it, snapshot it, destroy it |
| next | create a server *from* that snapshot, run the suite, destroy it |

## Acceptance

- One command produces a snapshot that a later run can boot from.
- The server is **always destroyed**, including when the script fails part-way.
- The snapshot is verified before it is kept: SELinux enforcing, rootless podman working, and
  every tool the suite needs present.
- Nothing secret is in the repository, and the token is read from one place.
- Running it twice does not leave two servers or two snapshots behind.

## The machine

| | |
|---|---|
| type | `cpx42` — 8 vCPU, 16 GB, 320 GB, 20 TB traffic |
| image | `fedora-44` |
| location | one of `fsn1`, `nbg1`, `hel1` |
| cost | €0.111/hour, so a 15-minute run is **€0.028** |

**`eu-central` is a network zone, not a location.** `servers.create()` takes a `Location`, and
`network_zone` is a *field on* a location - `fsn1`, `nbg1` and `hel1` all sit in `eu-central`.
Passing `eu-central` as the location fails.

## The steps

1. **Create** the server from the stock `fedora-44` image, with an SSH key that is already in the
   project, labelled so a sweep can find it.
2. **Wait** until SSH answers - poll the port, do not sleep a fixed number of seconds. A cloud
   image takes 30-60 s to finish cloud-init, and the first attempt after 15 s is what makes these
   scripts flaky.
3. **Customise**, over SSH:
   - `dnf install` podman, nftables, dnsmasq, git, nsenter (`util-linux`), and the SELinux policy
     tools the installer needs
   - a non-root build user with `subuid`/`subgid` entries, or rootless podman will not start
   - **`loginctl enable-linger`** for that user, or `/run/user/<uid>` will not exist and Sokar
     keeps task state there
   - GraalVM, and the musl toolchain via `buildtools/install-musl.sh` pointed at the release
     mirror rather than musl.cc, which is unreachable from some networks
   - a warm `~/.m2` - the point of the snapshot is that a run starts building immediately
   - the base images the suite pulls (`ubuntu:24.04`), so no run pays for that either
4. **Verify**, and refuse to snapshot if any of it fails:
   - `getenforce` says `Enforcing`
   - `podman run --rm alpine true` works rootless
   - `dnsmasq --version` lists `nftset`
   - `/run/user/<uid>` exists for the build user
5. **Power off.** A snapshot of a running server is not consistent.
6. **Snapshot** with `create_image(description=..., type="snapshot", labels=...)`.
7. **Destroy** the server.
8. **Print** the snapshot's id and description, which the next script takes as input.

## The API, from the local source

`/home/michi/git/hcloud-python`, whose own examples read the token from `HCLOUD_TOKEN`.

```python
from hcloud import Client
from hcloud.images import Image
from hcloud.server_types import ServerType
from hcloud.locations import Location

client = Client(token=environ["HCLOUD_TOKEN"], application_name="sokar-ci")

response = client.servers.create(
    name="sokar-ci-build",
    server_type=ServerType(name="cpx42"),
    image=Image(name="fedora-44"),
    location=Location(name="fsn1"),
    ssh_keys=[...],
    labels={"sokar": "ci", "role": "snapshot-builder"},
)
response.action.wait_until_finished()
...
server.power_off().wait_until_finished()
server.create_image(description="sokar-ci fedora-44 <date>", type="snapshot",
                    labels={"sokar": "ci"}).action.wait_until_finished()
server.delete().wait_until_finished()
```

`wait_until_finished(max_retries=None)` raises `ActionFailedException` when the action ends in
error and `ActionTimeoutException` when it is still running after the retries are used up. Both
must be caught by the cleanup, not by nothing.

## Destroying the server is the whole design

At €0.111/hour a forgotten server costs **€81/month** - more than a year of intended use. So:

- the delete runs in a `finally`, not at the end of the happy path
- it runs even when the verification refuses to snapshot
- a separate sweep deletes anything labelled `sokar=ci` older than an hour, because a script
  killed between two statements cannot clean up after itself

## Where the token lives

`HCLOUD_TOKEN` in the environment, which is what the library's own examples use.

- **locally**: `~/.claude/.ssh/hetzner-api-token.txt`, read into the environment by the caller -
  the same place the other test credentials live
- **in CI**: a repository secret, exposed to the job as **`REMOTE_BUILD`**

So the scripts read `REMOTE_BUILD` first and fall back to `HCLOUD_TOKEN`, which is the name the
library's own examples use and therefore the one a reader expects locally. Neither is defaulted to
a value, and a missing token fails with which variables were looked at.
- never a command-line argument, because a process list is world-readable, and never a default
  inside the script

The token needs write access to servers, images and SSH keys in one project, and nothing else.

## Built, and what it cost to get right

**Status: the snapshot exists.** Built 2026-09-05, verified, and the server destroyed itself each
time. Nine attempts, of which four failed on the *provisioning* and none on the suite - worth
recording, because every one of them would otherwise be rediscovered.

### What the stock image does not give you

- **SELinux ships `permissive`.** Measured on a fresh server before anything was installed:
  `getenforce` says `Permissive`, `/etc/selinux/config` says `SELINUX=permissive`, and the
  targeted policy is installed with `/sys/fs/selinux` present. Only the mode is wrong - so it
  needs the config changed, `/.autorelabel`, and a reboot. Everything above was written while
  permissive, so the relabel is not optional.
- **No `gcc`.** native-image is useless without one and says so as *"Default native-compiler
  executable 'gcc' not found"*, twenty minutes into a run.
- **No `sudo` for an ordinary user**, which is right - so nothing in a run may assume it.

### The four provisioning defects, and what each teaches

| what failed | why | the lesson |
|---|---|---|
| native-image had no `gcc` | never installed | "present" is not "works" - see below |
| rootless podman would not start | `install -d -o build` sets ownership on the **last** component only, so `/home/build/.local` was root-owned | a single root-owned directory in a user's home reads as a Sokar permissions bug |
| the verification reported a missing compiler that was there | its scratch directory came from `mktemp -d` as **root**, and `javac` ran as `build` | a check that fails for its own reasons is worse than no check |
| the run could not `sudo` | the build user has no password, correctly | install into the operator's own directories - which is the shape a task runs in anyway |

**So the verification compiles rather than inspects.** It builds a real `Probe.java` twice - once
ordinarily, once `--static --libc=musl` as the hooks need - and refuses to snapshot if either
fails. That distinction is what the first two rebuilds paid for.

### The thing that would have been missed entirely

**Sokar's own SELinux policy module.** The development VM has it because it was installed by hand
months ago; a fresh machine has nothing. Without `sokar_socket` a task container is denied
`connectto` on its own vault socket - and the denial is `dontaudit`'ed, so it appears as an agent
that cannot authenticate with **nothing in the audit log**. The snapshot now compiles and loads
it while the checkout is still present, and the verification checks `semodule -l`.

### What is in the image

GraalVM 25.0.2 pinned by the digest its release publishes, the musl cross-toolchain and a
musl-built zlib, `gcc`/`glibc-devel`/`zlib-devel`, podman with `ubuntu:24.04` and `alpine:3.20`
pre-pulled, SELinux enforcing with Sokar's policy loaded, and a **warm `~/.m2`** from building the
project once - the checkout itself is deleted, since it goes stale immediately and the dependency
cache does not.

**1.48 GB, about EUR 0.018 a month.** A run boots it in about a minute.

### Do not rebuild to test a change to the provisioning

Provision once with `--keep` and iterate against the live server over SSH. Every defect above was
found at the end of a fifteen-minute cycle and would have been found in under a minute that way.

## Confirmed against the API, 2026-09-05

Asked rather than assumed, with a read-only call per line:

| | |
|---|---|
| `fedora-44` | **exists** as a system image |
| `cpx42` | **exists**, 8 vCPU / 16 GB / 320 GB, not deprecated |
| `eu-central` | contains `fsn1`, `nbg1`, `hel1` - confirming it is a zone, not a location |
| SSH keys in the project | **none** - one has to be created before anything can be provisioned |
| servers in the project | none, so nothing is leaking today |

The script should still list the images and fail with that list rather than a bare 404: an image
name is exactly the sort of thing that moves, and `fedora-43` is already there beside `fedora-44`.

## To be checked
- **What the snapshot actually costs.** Billed on used space rather than the 320 GB disk, so it
  depends on how much the customisation installs - expected to be a few euro-cents a month, worth
  confirming once.
- Which SSH key: the one already used for the development VMs, or a key created for CI. A key
  that can reach the test machines and nothing else is the safer answer.
- Whether the snapshot should carry the repository itself. It goes stale immediately, but a warm
  `~/.m2` is most of the benefit and does not.
- How the snapshot is refreshed when GraalVM or the base image moves - the same problem as
  [0044](0044-Automated-Agent-Updates.md), and probably the same answer.
