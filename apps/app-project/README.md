# app-project

The command line's and the daemon's area for projects, following, backups and clearing, in the package `org.fuin.sokar.app` like every area. It is one
layer of the `sokar` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `ProjectMethods`; their contract is `20-project` in
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`. File locations: `ProjectPaths`.
- Depends on `app-task` and `app-messaging`, as AGENTS.md's area table sets out.
