# Legs

`sokar-acceptance-legs`, not published. What only this repository does with a machine:

- **`leg`** builds this tree on a machine rented at Hetzner and runs the [acceptance suite](../suite/README.md)
  there, as each acceptance leg of the GitHub build does. What it builds is [`ci/leg-build.sh`](../../ci/leg-build.sh),
  the same script the machine images' build in `sokar-buildtools` proves an image with. `LegBuildScriptTest` checks
  every module the script names against the reactor, so a regrouping that breaks it fails this build, not a leg.
- **`deploy`** installs a handover - the packages, the stub agent, the build readers - on a machine somebody keeps,
  machine-wide or into one account (`--account`).

Renting, ssh and the sweep are `sokar-machines`, shared by every repository; these two know this tree's layout,
so they live beside it, and a change to the layout needs no release of the tools.

```
./mvnw -pl acceptance/legs compile exec:exec@legs -Dlegs.args="leg --os ubuntu --repo . --acceptance"
./mvnw -pl acceptance/legs compile exec:exec@legs -Dlegs.args="deploy --vm <user@host> --key <file> --account"
```
