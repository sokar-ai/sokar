# B04 — Egress Sets Editor

**Status:** the editor is built and every acceptance criterion below holds through the CLI. What
is left is a question about *when* a change applies, and a second surface: no daemon method
exposes any of this, so an interface cannot reach it.

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

What was missing was editing - a way to change the declaration, see what a change adds or removes
before it is saved, and be refused when the security class forbids it. That is below.

## Acceptance

- Allowed destinations are listed with their origin: project, agent, or a decision.
- A change shows what it adds or removes before it is saved.
- A destination that was refused on purpose is distinguishable from one nobody added.
- Editing is refused for a class that forbids it - an `offline` project declares nothing, and
  the project reader already rejects a file that tries.
- **A guarded project that reaches a forge is shown what it costs**, as the CLI does: the gate
  then rests on the container holding no credential for that host rather than on the host being
  unreachable.

## Built, 2026-09-07

`sokar shield egress` shows and changes what a project may reach.

**Shown through the same composition a run uses.** `EgressReport.compose` puts the agent's grants,
the provider's host and the project's own declaration together, first grant wins, and both the
task launcher and this command call it. It was two loops for a while, which is how the CLI and the
interface come to disagree about what is open - the second vocabulary this requirement warned
against would have appeared inside one binary first.

**A change is reported in hosts, not in set names.** A set is a name for several hosts, and an
operator adding one is entitled to see them:

```
opens          repo.maven.apache.org  set maven
               central.sonatype.com   set maven
               repo1.maven.org        set maven
               nexus.corp.example     project
written        project.yml
               applies to the next task, not to one already running
```

`--dry-run` prints exactly that and writes nothing. Adding a forge to a guarded project prints
what it costs at the moment the edit is made, in the words the run already used - and not again on
a later edit, because a warning repeated when nothing changed is one an operator learns to skip.

**The file is edited, not rewritten.** `project.yml` is the one file in this product a person
writes by hand and a colleague reviews in a diff. Reading it into a model and dumping it back
would be four lines and would throw away every comment, the key order and the quoting somebody
chose. So the two keys are replaced where they stand, a block list is normalised to the flow form
the wizard writes, an empty declaration takes the whole block away rather than leaving a bare
`egress:` that reads as configured, and any line the editor does not understand is a line it does
not touch.

**Two refusals, both before anything is written.** A set this machine does not have is refused
with the command that lists the real names - written, it would name something no task could
resolve, and every run would fail on it rather than this one command. And the result is parsed by
the project reader itself before it reaches the disk, which is where an `offline` project is
refused in the words that requirement already chose.

## Notes

The distinction between "deliberately refused" and "never declared" must survive into
the interface; they look identical to the firewall and mean opposite things.

The set contents were adapted from the project acknowledged in the
[README](../../README.md), except `maven`, which was measured here: 193 artifacts resolved into
an empty local repository through a logging proxy, which saw `repo.maven.apache.org` and, for
snapshots, `central.sonatype.com`. That project also has a chooser that writes the key rather
than making an operator author host lists, which is roughly the shape this requirement is asking
for.

## To be checked

- **No daemon method exposes any of this**, so an interface cannot show what a project may reach
  or change it, and it may not shell out to the CLI. The composition and the edit are both in
  `app`, where the daemon can already reach them; what has to be decided is what a method that
  edits a file *by path* means when the client is on another machine and has no filesystem there.
- Does a change apply to a running task, or only to the next one? The editor says it applies to
  the next one, which is what the code does: a container's ruleset and resolver are built when it
  starts. Whether they *should* be changeable underneath a running agent is the larger question,
  and it is the same one the clearance path answers with "only by adding an address to the live
  set".
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
