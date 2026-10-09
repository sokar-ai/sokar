# B137 — An Offline Project Never Connects

**Status:** implemented here; the acceptance suite's bundle round trip is open.

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
- A project's definition can come as a file, for every class: `sokar project follow NAME FILE`, where `FILE` is a
  bundle of the project repository or a local directory holding it, checked against `--signed-by` exactly as a URL
  is. A project followed from a file is never fetched in the background; following it again from a newer file is how
  its settings change, and `project refresh` says so instead of fetching.
- An offline project is followed only from a file. A follow from a URL whose definition says `offline` is refused,
  and so is a URL-followed project whose definition becomes `offline` on a later fetch, before anything of the new
  definition is used. One command for all classes, no command of offline's own.
- A mirror is seeded only from `sokar gate restore`, from the followed definition, or from the checkout the command
  stands in.
- `doc/security.md`'s "Getting a history in" lists only those. The class picture shows no upstream: one file in, one
  file out.

## Acceptance

- Seen to fail first:
  - an offline project with `upstream:` is read without complaint;
  - an offline start with `--upstream` clones over the network.
- Afterwards both are refused with the way in, and no git command naming a URL runs for an offline project.
- An offline project seeded from a bundle with `gate restore` starts a task, and `gate backup` writes its work.
- A project followed from a bundle is used, is not fetched by the background watch, and changes when followed again
  from a newer bundle. A URL follow of an offline definition is refused, as is a fetch that turns a followed project
  offline.

## Decided

**Decided, 2026-10-09:** following was the one connection left. `sokar project follow NAME URL` clones the
project's own repository on the host, and the daemon fetches it again every five minutes; nothing skipped an offline
project there. The operator chose the file for the definition too, with no command of offline's own.

**Decided, 2026-10-09:** a project offline today with an `upstream:` is simply refused after the update; there are no
users beyond the testers, so nothing is kept for compatibility.

## As built, 2026-10-09

- `Project` refuses `upstream:` for offline, the project's and each further repository's, naming `sokar gate
  restore`; `WorkspaceSetup` refuses `--upstream` for an offline start before any git command runs.
- `FollowedProjects.fromAFile`: a source with a scheme or a host before a colon is an address, anything else a path
  (a bundle or a directory). `follow` records a path absolute; `ConfigurationWatch` fetches no path and answers
  `FROM_A_FILE` with how it changes; `project refresh` does not count that as a failure.
- `Reconcile` reads the class of the commit about to be applied: offline from an address is `OFFLINE_FROM_A_URL`,
  so a first follow records nothing and a later fetch keeps what was in force.
- Tests: `ProjectReaderTest` (three), `OfflineUpstreamTest`, `ReconcileTest` (directory, bundle, address, a project
  turning offline, a file never fetched). Two tests that followed a plain path and expected it fetched now name it
  as `file://`.
- **Open:** the acceptance suite following a bundle on a clean machine, restoring a bundle and backing up the work.

