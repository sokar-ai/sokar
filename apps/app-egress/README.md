# app-egress

The command line's and the daemon's area for egress, the shield and clearance, in the package `org.fuin.sokar.app` like every area. It is one
layer of the `sokar` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `EgressMethods`; their contract is `25-egress` in
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`. File locations: `EgressPaths`.
- Depends on `app-base`, `app-model` and `app-agents`, never on `app-vault` or `app-messaging`, as AGENTS.md's area table sets out.
