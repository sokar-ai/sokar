# Legs

`sokar-acceptance-legs`, not published. What only this repository does with a machine:

- **`leg`** builds this tree on a machine rented at Hetzner and runs the [acceptance suite](../suite/README.md)
  there, as each acceptance leg of the GitHub build does. Every path it takes from the tree is the tree's own:
  [`ci/leg-build.sh`](../../ci/leg-build.sh) builds - the same script the machine images' build in `sokar-buildtools`
  proves an image with - [`ci/leg-install.sh`](../../ci/leg-install.sh) installs into the account, and
  [`ci/leg-binaries`](../../ci/leg-binaries) names what comes back for the packages. `LegBuildScriptTest` and
  `LegTreePathsTest` check every module and binary they name against the reactor and the image names in the poms,
  and run the install against a tree laid out as a build leaves it, so a regrouping that breaks one fails this
  build, not a leg.
- **`deploy`** installs a handover - the packages, the stub agent, the build readers - on a machine somebody keeps,
  machine-wide or into one account (`--account`).

Renting, ssh and the sweep are `sokar-machines`, shared by every repository; these two know this tree's layout,
so they live beside it, and a change to the layout needs no release of the tools.

```
./mvnw -pl acceptance/legs compile exec:exec@legs -Dlegs.args="leg --os ubuntu --repo . --acceptance"
./mvnw -pl acceptance/legs compile exec:exec@legs -Dlegs.args="deploy --vm <user@host> --key <file> --account"
```
