# B13 — Unreviewed Work Leaving By The Side Door

**Status:** open, and asked for after a question nobody had asked: what happens when a person opens
the work in their own editor.

The gate is built for the agent. Inside the container there is no credential for any forge, the
firewall drops port 22 to a declared host, and the only remote the working copy has is Sokar's
mirror. An agent cannot push past the gate, and that was verified.

**The person can.** A developer opens the project in an IDE over SSH, fetches the agent's branch
out of the mirror to look at it, likes it, and pushes - with their own key, from their own checkout,
straight to the upstream. Nothing in Sokar is in that path. The review the gate exists to force did
not happen, and nothing recorded that it did not.

This is not a hole in the container. It is the seam between the machine's two halves: the agent's
work is behind a gate, and the operator's own repository is not, and the mirror is how work crosses
from one to the other.

## What is actually exposed

Measured rather than assumed, because the first guess was wrong:

- **The working copy is not on the host.** `/workspace` lives inside the container - `git init` in
  the image, remote `sokar` pointing at the gate. Only the vault and ssh sockets are bind-mounted.
  So an IDE attached to the *container* is as constrained as the agent: no credential, no route.
- **The mirror is on the host**, at `~/.local/share/sokar/mirrors/<project>.git`, holding every
  pushed branch under `refs/sokar/incoming/`. It is an ordinary bare repository owned by the
  operator, and anything they can run can read it.
- **So the path out is: fetch from the mirror into the operator's own checkout, then push that
  upstream.** Two ordinary git commands, no privilege needed, no warning anywhere. `sokar gate
  approve` is a third command that does the same thing *and* records it - and it is the one that
  can be forgotten.
- **Agent commits are identifiable.** The working copy is configured with `user.name` `agent` and
  `user.email` `agent@localhost` unless the task overrides them, so a commit made by an agent can
  be told from one made by a person - which is what any guard here would have to rest on.

## Acceptance

- Work that reached the upstream without passing the gate is **prevented or reported**, not
  silently possible.
- The guard recognises agent work by something the agent actually leaves behind, not by where a
  branch happens to be.
- A person who means it can still do it. **This is protection against an accident, not against the
  owner of the machine** - anything else would be a lie, since it is their key, their checkout and
  their computer.
- Whatever is installed into an operator's own repository is asked for, reversible, and says what
  it did when it fires.
- Nothing here weakens the case where it already works: an IDE attached to the container stays as
  constrained as the agent.

## Notes

The honest framing matters. Sokar cannot stop a determined person from pushing their own commits
with their own credentials, and pretending otherwise would produce a feature that is both
irritating and untrue. What it can do is make the accidental case loud: the moment somebody pushes
commits an agent wrote and nobody approved, they should hear about it.

## To be checked

- **Where the guard lives.** A `pre-push` hook in the operator's own checkout is the only place
  that sees the push before it happens, and that repository is not Sokar's to change. Installing
  one has to be a deliberate act - `sokar gate protect` or similar - and has to survive the fact
  that git hooks are per-clone and not committed.
- **What the guard tests.** Author identity is the cheap signal and it is spoofable and easy to
  lose in a rebase. A trailer that the gate adds when it forwards - so that *approved* commits are
  the marked ones and everything else is suspect - inverts it into the safer direction, at the cost
  of rewriting commits at approval time.
- **Whether the mirror should be harder to fetch from.** Making it unreadable would break `gate
  review`, which is how anybody looks at the work at all. Probably nothing to do here, but it is
  the other end of the same path.
- **Whether the IDE case wants its own answer.** Attaching an editor to the container is the safe
  way to look at the work, and nothing in the product says so. Documentation may be worth more here
  than mechanism.
