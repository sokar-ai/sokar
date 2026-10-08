# app-messaging

The command line's and the daemon's area for messages and their conversation, in the package `org.fuin.sokar.app` like every area. It is one
layer of the `sokar` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `MessagingMethods`; their contract is `45-messaging` in
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`. File locations: `MessagingPaths`.
- Depends on `app-base` and `app-model`, never on `app-vault` or `app-egress`, as AGENTS.md's area table sets out.
