# B51 — The tests that run nowhere

Two unit tests in `SocketContextTest` are gated on `@EnabledIf("selinux")`, which asks whether
`/sys/fs/selinux` exists. They have never run.

## What was measured

On 2026-09-12, during the integration tiers for a credential-scanner change:

- the ubuntu leg reported `Tests run: 51 … Skipped: 2`;
- the fedora leg reported **the same two skipped**, although the machine it rents has SELinux and
  the leg installs this product's own SELinux policy on it.

The reason is not the distribution. The leg builds the product on the rented machine with
`-DskipTests`; the unit tests in that log run on the **host driving the leg**, and that host has no
`/sys/fs/selinux`. So changing the target distribution cannot make them run: they are not executing
on the target at all.

## Why this is the shape that matters rather than two tests

A green build reported them as *skipped*, which reads exactly like a condition that did not apply
today. It is not: it is a condition that **cannot** apply anywhere in the pipeline as it stands.

This is the second instance of that shape here. The first is already in `AGENTS.md`: eleven `@slow`
acceptance scenarios were excluded everywhere, reported as skipped in every green build, and when
they were finally run they turned up three faults — one scenario asserting behaviour that had been
cut, one that could not run twice on a machine, and eleven that silently assumed exactly one agent
was installed. None of that was visible while the skip looked conditional.

## What would close it

The SELinux-dependent tests have to execute where SELinux is. That means the leg running the unit
tests **on the rented machine** rather than the driver running them here — which is a change to
what a leg is for, so it is a decision rather than a fix.

Two smaller things are worth doing either way, and neither depends on that decision:

- **A skip that can never apply should say so**, rather than presenting itself as a condition that
  happened not to hold. A test disabled because no machine in the pipeline can satisfy it is a
  different fact from one skipped on this run.
- **The build should be able to report which conditional tests were satisfied nowhere**, across the
  legs of a matrix. One leg cannot see this; the pair of them makes it obvious.

## What it is not

Not a defect in `SocketContext`, and not evidence that the SELinux path is broken — it is evidence
that nothing here has ever checked it. The acceptance scenarios do exercise a container with the
policy loaded on the fedora leg, so the mechanism is not untested end to end; what is untested is
the unit behaviour these two cases were written for.
