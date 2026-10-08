# app-task

The command line's and the daemon's area for tasks, in the package `org.fuin.sokar.app` like every area. It is one
layer of the `sokar` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `TaskMethods`; their contract is `15-task` in
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`. File locations: `TaskPaths`.
- Depends on `app-base`, `app-model`, `app-agents`, `app-vault`, `app-messaging` and `app-egress`, as AGENTS.md's area table sets out.
