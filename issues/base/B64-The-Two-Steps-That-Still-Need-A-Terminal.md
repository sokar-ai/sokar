# B64 — The Two Steps That Still Need A Terminal

**Status:** open, found on 2026-09-18 by walking the flow for a machine that had just been prepared:
packages installed, nothing started, an operator sitting at an interface on another computer.

B62 prepares a machine end to end and a keyslot lets a device open its vault ([doc/vault-keyslots.md](../../doc/vault-keyslots.md)). Between them there is a gap
that neither closes, and it is the first five minutes of every new machine:

1. **Nothing starts the daemon.** `sokar-setup.sh` deliberately leaves it to the work user's own
   session, because a root script starting another user's service is either wrong or a lie about the
   session it runs in. So somebody has to run `systemctl --user enable --now sokard` as that user.
2. **Nothing registers the OCI hooks.** `sokar setup` writes into that account's own podman
   configuration, and podman reads hook descriptors per user - so root does not know whose to write,
   and a `hooks_dir` in the system configuration would point every user's podman at Sokar. Without
   it `sokar doctor` says *"hooks registered: MISSING - a task would run with no firewall at all"*
   and exits 69. Starting a task registers them too, which makes this the difference between a
   prepared machine and one that repairs itself on first use. Found on 2026-09-18 by the
   interface's agent on a machine the script had just prepared; the script's closing text now names
   it, and nothing does it for them.
3. **Nothing can create the vault except a terminal on that machine.** There is no method on
   `Tasks1` that makes one, and the daemon has none to offer: a vault is created with a passphrase,
   and a daemon has no terminal to ask for one at. `EnrollDevice` therefore answers
   `VAULT_WITHOUT_KEYSLOTS` on a new machine, and a device cannot be the first way in.

So the interface's wizard, which exists so that a person never has to open a shell, has to open one
three times. The first two are the same kind of thing - work that belongs to the account the daemon
runs as - and only the third carries a decision.

## What must be true

- **A person preparing a machine from an interface never has to know these two steps exist.**
  Whatever does them may be a command run over ssh, a method on the socket, or something else - but
  it is Sokar's to describe, and the interface must not be the second place where the knowledge
  lives.
- **Whatever is chosen does not weaken what the daemon is.** Today's rule is that a daemon can shut
  the vault but never open it, because it has no terminal; the keyslots weakened that in exactly one
  direction - a *share*, which arrives from a device and is worthless on its own. A passphrase
  travelling to the daemon would be a different thing and needs to be decided rather than slid into.
- **A machine that is half-prepared says so.** `sokar doctor` should answer "there is no vault here
  yet" as a state, not leave an interface to infer it from an empty credential list.

## The decision this issue carries

**Where the first passphrase is typed.** Three shapes, and they are not equally honest:

| | What it costs |
|---|---|
| **The wizard runs `sokar vault init` over ssh** | Nothing new in Sokar; the wizard already has an ssh session, since that is how it installed the packages as root. The interface gains "runs a command on a machine", which it does not have today and which is a capability worth naming. |
| **A `Tasks1` method that creates a vault from a passphrase sent over the socket** | The passphrase travels. The socket is a unix socket owned by that user and forwarded over ssh, so it is not travelling far - but "the daemon never receives a passphrase" stops being true, and every later argument that relies on it has to be re-read. |
| **A vault whose first and only way in is a device** | No passphrase anywhere, and no recovery: keyslot 0 is the passphrase precisely so that losing every device is survivable ([doc/vault-keyslots.md](../../doc/vault-keyslots.md)). It would have to be paired with something else recoverable, which is a larger design than this gap deserves. |

The first is the recommendation. It is the only one that adds no new path for a secret, and the
capability it needs is one the wizard already exercises with root.

## Acceptance

- A machine prepared by `sokar-setup.sh` reaches "ready for a device to be enrolled" **without a
  person typing a command**, by whatever route is chosen.
- `sokar doctor` names the missing vault as its own state, with what to do about it.
- The route is written down in one place, and the interface's agent builds against that rather than
  against a description of it.
- Starting the daemon and creating the vault are **separately restartable**: a wizard abandoned
  between them, and run again, does not fail on the half that already happened.

## Notes

**Found by answering a question rather than by testing.** The operator asked what he would still have
to do on a freshly installed server, and the honest answer turned out to be "open a shell twice".
Nothing here is broken; what is missing is a route, and the missing route is invisible from inside
either issue that leads up to it.

**`sokar vault init` exists as of this issue's writing** (it was added the same day, because the
error message for an old vault format already told people to run it and it did not exist). So the
first option needs no code in Sokar at all - only an agreement about who runs it.
