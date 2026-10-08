# app-vault

The command line's and the daemon's area for the vault, credentials and grants, in the package `org.fuin.sokar.app` like every area. It is one
layer of the `sokar` executable, which [app](../app/README.md) assembles; it is not a program of its own.

- Daemon methods: `VaultMethods`; their contract is `30-vault` in
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`. File locations: `VaultPaths`.
- Depends on `app-base` and `app-model`, never on `app-messaging` or `app-egress`, as AGENTS.md's area table sets out.
