# Acceptance

What a person does at a terminal, run against a real machine. Two modules, the way `agents/` has
the contract beside the stub:

- [**kit**](kit/README.md) - `sokar-acceptance-kit`, published. Drives a machine over ssh from a
  Cucumber scenario and reports where GitHub shows it. Any repository's suite is built on it: this
  one's, and each agent's.
- [**suite**](suite/README.md) - `sokar-acceptance`, not published. This repository's own
  scenarios, and nothing else.

```
./mvnw -pl acceptance/suite verify -Dsokar.acceptance.host=<machine> -Dsokar.acceptance.key=<key>
```
