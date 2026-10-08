# apps

The command line and the daemon's logic, as layers: the shared ground, one module per area, and `app`, which
assembles them into the `sokar` executable. A module of packaging `pom` that only groups them.

- [app-base](app-base/README.md), [app-model](app-model/README.md) - the shared ground.
- [app-agents](app-agents/README.md), [app-vault](app-vault/README.md), [app-messaging](app-messaging/README.md),
  [app-egress](app-egress/README.md), [app-task](app-task/README.md), [app-project](app-project/README.md),
  [app-gate](app-gate/README.md) - one area each.
- [app](app/README.md) - the executable.

Which area depends on which is the table in [AGENTS.md](../AGENTS.md#areas-and-who-works-in-which).
