# B04 — Egress Sets Editor

**Status:** open

Editing what a task may reach is the highest-consequence configuration in the
product. It should be legible: which destinations are allowed, where each came from,
and what a change would open.

## What already exists

The mechanism this is an interface over is built and verified on both distributions. Working from it rather than around it is the point:

- **A project declares hosts** in `project.yml`, as curated `egress.sets` and as
  `egress.domains` for a host no set covers. Absence means nothing is reachable.
- **Sets are data**, one YAML file each, scanned from `/usr/share/sokar/egress` and from the
  operator's own `$XDG_DATA_HOME/sokar/egress`, where a file of the same name wins. So the
  editor writes files; it does not need a new store.
- **`sokar shield sets`** lists them, with `--verbose` for the hosts. That is the CLI half of
  the first acceptance criterion below.
- **`sokar task run` already prints every destination with its origin**, in one list, marking
  those refused on purpose - `agent claude`, `provider anthropic`, `set maven`, `project`,
  `refused on purpose`. The interface should show what that prints, not invent a second vocabulary.
- **A declared name opens ports 80 and 443 only**, so "what a change would open" has a port
  dimension that is already fixed and does not need to be offered.

What is missing is editing: a way to change the declaration, see what a change adds or removes
before it is saved, and be refused when the security class forbids it.

## Acceptance

- Allowed destinations are listed with their origin: project, agent, or a decision.
- A change shows what it adds or removes before it is saved.
- A destination that was refused on purpose is distinguishable from one nobody added.
- Editing is refused for a class that forbids it - an `offline` project declares nothing, and
  the project reader already rejects a file that tries.
- **A guarded project that reaches a forge is shown what it costs**, as the CLI does: the gate
  then rests on the container holding no credential for that host rather than on the host being
  unreachable.

## Notes

The distinction between "deliberately refused" and "never declared" must survive into
the interface; they look identical to the firewall and mean opposite things.

The set contents came from [Terok](https://github.com/terok-ai/terok), except `maven`, which was
measured: 193 artifacts resolved into an empty local repository through a logging proxy, which
saw `repo.maven.apache.org` and, for snapshots, `central.sonatype.com`. Terok also has a chooser
that writes the key rather than making an operator author host lists, which is roughly the shape
this requirement is asking for.

## To be checked

- Does a change apply to a running task, or only to the next one? Editing what a
  running agent may reach is a different and larger question than editing a project.
- **Whether a set may carry more than domains.** A CDN that answers a different address per
  request is fine, because dnsmasq adds each answer as it answers. One that is reached by
  address, without a name, is not, and would need something else - which would change what the
  editor edits.
- **Whether an undeclared name should resolve and prompt** rather than NXDOMAIN. It would make
  the failure legible and reuse the clearance path, at the cost of telling the container that a
  host exists and of turning every stray lookup into a question. It decides whether this
  interface is mostly used before a task runs or during one.
- **Whether a set can be versioned or pinned**, so "the maven set" means the same thing on two
  machines. An editor that can write a set makes this sharper, not softer.
- **`os-packages-fedora` cannot be complete**, and the interface has to say so rather than
  presenting it as equivalent to the others: `dnf` fetches from mirrors named by a mirrorlist,
  which differ by region and by day, so each one arrives as a clearance prompt. Pinning a baseurl
  in the image snippet is the way out, and that is not something this editor edits.
