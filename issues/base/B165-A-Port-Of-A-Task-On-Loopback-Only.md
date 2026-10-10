# B165 — A Port Of A Task On Loopback Only

**Status:** open.

**What must be true.** `sokar task port TASK 8080` makes a server listening on that port inside the task reachable on
the machine's `127.0.0.1`, for the person at the machine only, by an explicit command, and journaled.

## Why

Whoever builds a web interface has to see the result before approving it, and today it can only be read as code.
Comparable tools forward a task's port or give it a preview address. Sokar keeps it on loopback: nothing of the task
becomes reachable from another machine.

## The shape

- Bound to `127.0.0.1` (and `::1`) only, on a free port the command names, never `0.0.0.0`; the forward ends with the
  command, or with `sokar task port TASK 8080 --stop`, and with the task.
- Nothing is reachable from the task towards the host by it: the forward goes one way, into the task.
- Each forward opened and closed is journaled with the task, the ports and who ran it.
- Size: small to medium.

## Acceptance

- Seen to fail first, then green: a server in a task answers on the forwarded loopback port; the same port is not
  reachable on the machine's other addresses; the journal names both ends.
- `doc/commands.md` and `doc/reach.md` say it.

## To be checked

- Whether the interface and the IDE plugin open the address for the person, over their ssh tunnel.
