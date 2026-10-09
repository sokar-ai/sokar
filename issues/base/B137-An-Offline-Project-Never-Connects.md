# B137 — An Offline Project Never Connects

**Status:** decided.

**What must be true.** A project of the class `offline` makes no connection out, neither from the task nor from the
host on its behalf. A repository comes in as one file a person carries to the machine, and the work leaves the same
way.

## Why

The task of an offline project reaches nothing. The host did, once: a project with `upstream:`, or a start with
`--upstream`, had its mirror cloned over the network on the first start (`git clone --bare <upstream>`). After that
the mirror is never fetched again. So "offline" meant "offline after the first start", which is not what a person
choosing it for a machine without a route, or for code that must not touch a network, expects. The operator decided
on 2026-10-09 that offline never connects.

## The shape

- `upstream:` in an offline project is refused when the project file is read, as `credentials` and `egress` are
  today, with the way in named: `sokar gate restore <bundle>`, or a checkout already on this machine.
- `--upstream` on a start of an offline project's task is refused the same way.
- A further repository of an offline project takes no `upstream:` either.
- Nothing measures, watches or keys an upstream for an offline project. That covers the upstream distance, the
  upstream watch and the deploy keys, each of which already does nothing for offline and is then left with nothing
  to skip.
- A mirror is seeded only from `sokar gate restore`, from the followed clone, or from the checkout the command stands
  in.
- `doc/security.md`'s "Getting a history in" lists only those. The class picture shows no upstream: one file in, one
  file out.

## Acceptance

- Seen to fail first:
  - an offline project with `upstream:` is read without complaint;
  - an offline start with `--upstream` clones over the network.
- Afterwards both are refused with the way in, and no git command naming a URL runs for an offline project.
- An offline project seeded from a bundle with `gate restore` starts a task, and `gate backup` writes its work.

## To be checked

- A followed offline project. `sokar project follow NAME URL` clones the project's own repository (its project file,
  image recipe, settings) on the host, and the daemon fetches it again every five minutes, and on `project refresh`.
  Nothing skips an offline project there today. Whether that is allowed for an offline project, or whether its
  definition must come in as a file too.

**Decided, 2026-10-09:** a project offline today with an `upstream:` is simply refused after the update; there are no
users beyond the testers, so nothing is kept for compatibility.
