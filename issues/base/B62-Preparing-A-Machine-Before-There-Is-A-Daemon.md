# B62 — Preparing A Machine Before There Is A Daemon

**Status:** built on 2026-09-18, the same day it was asked for (QF18 on the channel).
`dist-setup/sokar-setup.sh`, published by CI beside the packages as
`sokar-dist-deb/setup/sokar-setup-<version>.sh` and `-latest.sh`.

**Measured on the Ubuntu VM, twice in a row:** the first run configured the repository, installed
`sokar` from it, made the `agents` user with linger, found the subuid and subgid ranges `useradd`
had already allocated, and checked dnsmasq for nftset support. The second run changed nothing and
said so on every line. An unsupported system is refused with exit 3 and one sentence naming what it
found, measured against a fabricated `/etc/os-release`.

**Measured on Fedora 44 as well, on 2026-09-18**: repository written, `dnf makecache`, the account
with linger and the subuid ranges `useradd` allocated, the dnsmasq check, and a second run that
changed nothing. `--list` and `--list --json` answer there too (`{"packages":[]}`, because no
published package declares `Provides: sokar-agent` yet).

**The Fedora run found two things the Ubuntu runs could not.** First, a regression: a restructure
had deleted the whole repository section, and Ubuntu did not notice because its source file was
already written by an earlier run. Fedora hit it on the first line that needed it. Second, a blind
spot that is now a check: on a machine that already has Sokar installed, `apt`/`dnf` answer
"nothing to do" whether the repository works or not, so a misconfigured source passed silently. The
script now verifies that the repository actually offers a `sokar` package and refuses with exit 5
when it does not.

**What is not done:** nothing here installs the message filter or the local transport unless they
are published, and they are not yet (the filter's repository, issue 009). The script says so in its
closing output rather than leaving a machine that quietly cannot message.

Everything Sokar knows how to do, it does through the daemon. **Preparing a machine is the one thing
that cannot**, because the machine being prepared has no daemon yet, no packages, and no work user
for the daemon to run as. Today that knowledge exists as prose in `build.md` and as steps in
`buildtools/deploy-vm.sh`, which is a developer's tool for a machine they already have.

So the interface is about to reimplement it. **That is the failure this issue exists to prevent**:
two descriptions of how a Sokar machine is made, drifting apart, with the one that runs as root
being the one nobody here maintains.

## What it has to do

A machine that a person has just rented, reachable over ssh as a user who can become root:

- **The packages**, from the repository they are published to, with the repository configured first.
- **A work user** - `agents` by default - with **linger** enabled, so a user service survives the
  person logging out, and with **subuid and subgid** ranges, without which rootless podman cannot
  map a container's users at all.
- **The daemon**, as that user's systemd service, and the host key material it needs.

## What it must not do

- **It must not be a `Tasks1` method.** There is nothing listening. This is the one Sokar interface
  that is a file rather than a call, and it is worth saying why in the script itself.
- **It must not be a package.** A package needs a package manager already pointed at our repository,
  and pointing it there is one of the things the script is for.
- **It must not start the user's service.** A root script starting another user's units is either
  wrong or a lie about the session it runs in. It prepares; the person's own session starts it.

## Acceptance

- **One script, reading `/etc/os-release`**, published beside the packages and versioned with them,
  so the wizard fetches exactly the one matching what it is about to install.
- **An OS it does not know is refused with exit 3 and one sentence naming what it found.** Not a
  best-effort attempt on an unknown distribution: a half-prepared machine is worse than an
  unprepared one, because the next step believes it.
- **Running it twice changes nothing the second time**, and says so. The wizard can be abandoned
  half-way and started again, and a person who is unsure whether it ran must be able to just run it.
- **It creates the work user itself**, rather than expecting one. Linger, subuid and subgid are
  where a hand-rolled version goes subtly wrong, and the failure appears much later as a container
  that cannot map its own user.
- **Every command it will run can be shown before it runs**, because the wizard shows them to the
  person who is about to give it root.
- **Success is exit 0 and then `sokar doctor` answering ready** over the daemon's socket. The exit
  code says the steps ran; only the daemon can say the machine works.
- **The acceptance suite prepares a rented machine with this script** and not with anything else, so
  the path an operator takes is the path that is tested.

## Notes

The interface's agent offered to create the work user in the wizard instead. Refused on purpose:
whoever owns those three settings ends up debugging them, and it should be the side that knows why
they are there.
