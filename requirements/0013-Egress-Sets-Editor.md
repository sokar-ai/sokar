# 0013 — Egress Sets Editor

**Status:** open

Editing what a task may reach is the highest-consequence configuration in the
product. It should be legible: which destinations are allowed, where each came from,
and what a change would open.

## Acceptance

- Allowed destinations are listed with their origin: project, agent, or a decision.
- A change shows what it adds or removes before it is saved.
- A destination that was refused on purpose is distinguishable from one nobody added.
- Editing is refused for a class that forbids it.

## Notes

The distinction between "deliberately refused" and "never declared" must survive into
the interface; they look identical to the firewall and mean opposite things.

## To be checked

- Does a change apply to a running task, or only to the next one? Editing what a
  running agent may reach is a different and larger question than editing a project.
