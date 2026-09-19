# B65 — A Gate For Configuration Coming In

**Status:** built on 2026-09-19. Reconciliation applies what this verifies, so every criterion below now has
something behind it. What is built: the pinned anchor
(`config/configuration_signers`, `allowed_signers` format, the same shape and the same reader as the
message keyring), the verdict over a commit, and `sokar doctor` reporting a machine that has nothing
pinned - because that machine does not break, it quietly stops following its projects.

**Measured against git 2.53 and real ssh keys**, not against a fake: a commit signed by the pinned
key verifies and names its principal; an unsigned one fails with nothing to say; one signed by a
*valid* key that is not pinned reports a good signature and *"No principal matched"*. The last two
are told apart, because "somebody forgot to sign" and "somebody who may not sign did" have different
cures.

**Verified by git rather than by us.** A commit's signature covers the commit object, and
reproducing that canonicalisation here would be a second implementation of something git already
does exactly.

Decided on 2026-09-19 that a project's configuration comes from its own repository,
which the machine pulls and reconciles itself against - see
[reconciliation](../../doc/glossary.md#reconciliation). This is the check that must exist
**before** it does.

Everything Sokar protects today is about work going **out**: the gate holds what an agent wrote
until a person has read it, and B13 exists because unreviewed work leaving by a side door is the
failure that matters. Reconciliation reverses the direction. **A git repository would then decide
what runs on this machine** - which image, which limits, and, in `egress`, **which hosts a task may
reach.**

So whoever can push to the team repository can open the firewall on every machine that pulls it.
Not on one machine, and not once: on all of them, at the next tick. The most carefully built part of
Sokar would be reachable by a route that has no check in it at all.

## What must be true

1. **The machine knows one key out of band, and applies nothing that is not signed by it.** The
   trust anchor cannot live in the repository it authenticates; an operator pins it when the machine
   is prepared, the same way a peer's key is pinned for messages.
2. **An unsigned or wrongly signed change is refused and reported, never applied and never silently
   skipped.** The machine keeps running what it last verified, and says loudly that it is behind.
3. **A refusal names what it refused**, so an operator can tell "somebody pushed without signing"
   from "somebody pushed who may not" from "the repository is unreachable". They have different
   causes and only one of them is an attack.
4. **What was applied is recorded** - which commit, when, and by which signature - so "why is this
   machine like this" has an answer that does not require guessing from file timestamps.
5. **A verified change is still not a licence to do anything.** The boundary of what reconciliation
   may touch is reconciliation's, and this issue does not widen it.

## What it buys beyond the obvious

**An agent may propose configuration and can never put it in force.** A project's repository holds
its planning as well as its `project.yml`, so an agent with a task on it is editing the file this
reconciles against - doing exactly what it is there for. The signing key that makes a change apply
is not on the machine that agent runs on, so its commit travels the ordinary way: through the gate,
to a person, who signs it or does not.

That is the same shape as everything else here - an agent produces, a person approves - and it
arrives for free once the check exists. Without the check it is the opposite: an agent editing a
file would change what its own containment allows.

## Acceptance

- A commit signed by the pinned key is applied; the same tree pushed unsigned is not, and the
  machine says so rather than staying quiet. **Built and measured.**
- A commit signed by **a** valid key that is not the pinned one is refused - being signed is not the
  test, being signed by the right key is. **Built and measured.**
- **A machine that cannot reach the repository keeps running what it verified last** and reports the
  gap. It never falls back to an unverified copy, and it never stops tasks because a fetch failed.
  **Built and measured.**
- A history rewrite that moves the branch under the machine is noticed rather than followed
  silently. **Built and measured, and it refuses rather than only noticing:** a signed commit that
  is not a descendant of the one in force stops and waits for `--accept-rewrite`. Somebody with the
  key may have rebased, and somebody *without* it may be serving an older signed configuration to
  put back a rule that was taken away - which needs no key at all, only control of the route. The
  two are identical from the machine's side, so it asks.
- What a refusal costs is measurable: after one, `sokar doctor` names it. **Built**: the
  `following` probe reports any project that is not up to date, and tells a shut vault apart from
  an unreachable repository.
- **A commit made by an agent is not applied**, however well-formed, because it is not signed by the
  pinned key - measured with a real agent commit rather than argued from the key's absence.

## Notes

**This is the gate's argument pointed the other way**, and it is worth saying in those words: Sokar
refuses to let work out without a person looking, and would be inconsistent if it let configuration
in without a machine looking. The asymmetry people expect - *"it is only configuration"* - is exactly
wrong, because configuration is what decides what the containment is.

**Nothing here is measured.** No signing scheme has been tried against a real repository, and the
obvious candidate, `git verify-commit` against an allowed-signers file, has the same shape as the
message signatures already built (`doc/` and B14) but has not been run for this purpose.

**It is deliberately first.** Reconciliation is more useful and this is the one that is hard to add
afterwards:
a reconciliation loop that runs unverified for a while teaches everybody that configuration arrives
without a check, and the check then breaks machines that were fine yesterday.
