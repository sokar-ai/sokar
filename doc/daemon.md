# Running the daemon

`sokard` serves the Sokar domain over an owner-only unix socket, so an interface — on this machine
or on another one through an ssh forward — can do what the CLI does. **Nothing in the CLI needs it
running.**

    sokard --help        # says what it is
    sokard --version     # says which build
    sokard               # serves, until it is stopped

It takes no options beyond those two, and an option it does not know is refused rather than
ignored.

## The socket

It binds `$XDG_RUNTIME_DIR/sokar/sokard.sock`, owner-only. Two things about its lifetime are worth
knowing, because an interface meets both:

- **A daemon that is stopped or signalled removes it.** A socket file with nothing behind it is a
  name that refuses every connection, and an interface cannot tell that from a daemon that is still
  starting. Only `SIGKILL` can still leave one, and nothing can change that.
- **A second daemon does not take the socket from the first.** Starting one while another is
  listening is refused, naming the path. Unlinking the file would not stop the first daemon — the
  file is only a name — it would make it unreachable, and nothing anywhere would say so.

A socket left behind by a `SIGKILL` or a reboot is taken over by the next start, which unlinks it
only after proving nobody answers on it.

## The systemd unit

`sokard.service` is a **user** unit, and that is the design rather than a convenience.

**A Sokar node is an OS user, not a machine.** The vault, the containers in rootless podman's
per-user storage, the hooks podman reads, the socket under `$XDG_RUNTIME_DIR` — every one of them
is keyed to the user. A system service would run as one particular account, and that account would
be the only node on the machine. Two developers with their own logins are two nodes, and each
starts their own.

## Using it

    systemctl --user enable --now sokard
    systemctl --user status sokard

The package installs the unit and enables nothing: starting a daemon is the operator's decision,
and a package that started one on install would start it for every account on the machine.

**A daemon meant to outlive the session that started it needs lingering**, or systemd stops
everything belonging to the user at logout:

    loginctl enable-linger "$USER"

This matters exactly for the case the unit exists for — an interface on another machine reaching
this one through an ssh forward, when nobody is logged in at it.

## Three things deliberately not here

**No `NoNewPrivileges=yes`.** It reads as free hardening, and it breaks the daemon after every boot.
The first rootless podman call sets up the user namespace through the setuid `newuidmap` and
`newgidmap` and starts podman's pause process; no-new-privileges forbids exactly that. Under it the
daemon works only while something outside the unit has already run podman, so it passes on a machine
somebody has been using and reports podman missing on a fresh boot. Measured on 2026-09-13: with it,
`newuidmap: write to uid_map failed: Operation not permitted`; without it, rootless networking
through `pasta`.

**No `RuntimeDirectory=sokar`.** It would be the obvious line, and it is a trap: the same runtime
directory holds each task's state, and systemd removes what it creates when the unit stops. The
daemon and the CLI both create the directory themselves, so nothing is gained and a stopped daemon
would take running tasks' state with it.

**No `sokard.socket`, so no socket activation** — which is what an interface would most like, since
the first connection through a forward would then start the daemon by itself and a stale socket
would stop being a category at all. It needs the service to accept a listening file descriptor
systemd already bound and passed in, and the JDK offers no public way to adopt an inherited
listening descriptor into a `ServerSocketChannel`. Doing it anyway means writing the accept loop
against the descriptor directly, which is a different transport rather than a unit file. Recorded
rather than dropped: it is the right shape, and it is not one line.

What the unit does give an interface is the honest version of starting a daemon remotely:
`systemctl --user start sokard` over the forward's ssh, supervised and restarted on failure,
rather than an unsupervised process left behind by an ssh command.
