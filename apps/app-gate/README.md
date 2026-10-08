# app-gate

The command line's and the daemon's area for the gate and review, in the package `org.fuin.sokar.app` like every area. It is one
layer of the `sokar` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `GateMethods`; their contract is `35-gate` in
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`. File locations: `GatePaths`.
- Depends on `app-messaging`, as AGENTS.md's area table sets out.
