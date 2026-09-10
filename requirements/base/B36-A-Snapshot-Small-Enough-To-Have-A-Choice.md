# B36 — A Snapshot Small Enough To Have A Choice

**Status:** open, and it is a trade made deliberately rather than a defect. Made on 2026-09-10, when the
images were rebuilt on a fast machine because somebody was waiting.

## What happens

A Hetzner snapshot records the disk of the machine it was taken on, and restores only onto a disk
at least as big. The images a leg boots were rebuilt on `cpx42` — 320 GB — so `cpx42` is now the
only type that can boot them. `Spec.DEFAULT_TYPES` is a list of one for that reason, and the
availability fallback it was written for cannot fire.

That fallback is not decorative. On 2026-09-10 both `cx23` and `cx33` were sold out in `eu-central`
within minutes of each other, and the snapshot builder fell through to the only small type left.
A `cpx42` shortage would today stop every leg outright rather than costing one.

## How it got here

- The original pair demanded **320 GB to hold 1.6 GB**, which nobody had noticed because nobody
  could rebuild them: the builder lived outside the repository.
- Rebuilt at **40 GB**, which made every cheap type able to boot them — measured, `cx23` at
  0.0088 EUR/h against `cpx42` at 0.1114.
- Rebuilt again at **320 GB**, because building on two cores takes about half an hour and
  `--type cpx42` takes minutes, and an agent was waiting for the artifacts.

The saving was never the point of the small floor — a leg rents `cpx42` anyway, because the
runner blocks while the remote build runs and a slower machine costs CI minutes, which is the
scarce resource. **The point is having somewhere to fall back to.**

## What it would take

`Main snapshot --os <os> --repo .` with no `--type`, so `Snapshots.BUILD_TYPES` picks the
smallest disk on offer. About half an hour per image on two cores, both can run in parallel, and
nothing else changes: the recipe, the checks and the contents are the same.

The only thing to get right is timing. The build compiles Sokar on the machine, so it must not be
done while somebody is waiting on a green build — which is exactly the pressure that produced the
320 GB floor in the first place.

## Acceptance criteria

- Both images report a `disk_size` of 40 GB, or the smallest a build machine on offer allows.
- A leg boots one on a type other than `cpx42`, proving the fallback can fire.
- `Spec.DEFAULT_TYPES` names more than one type again, cheapest adequate last, and the comment
  explaining why there is no fallback is removed rather than left to go stale.
- The floor is recorded where it is decided, so the next person choosing `--type` for speed knows
  what they are spending.

## To be checked

- **Is 40 GB actually enough for a leg?** The image rests at 2.7 GB and the build adds a Maven
  repository, six native images and container layers. It has never been measured on a 40 GB disk
  under a full leg, only under a snapshot build — and running out of disk is a hard failure that
  the type fallback does not catch.
- **Does a smaller build machine change what the image contains?** It should not, but the recipe
  installs from distribution repositories, and a package set is a moving target.
