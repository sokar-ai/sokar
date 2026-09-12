# B36 — A Snapshot Small Enough To Have A Choice

**Status:** met on 2026-09-10, the evening of the day the trade was made. Both images were rebuilt
small, both legs passed on a cheap machine, and the fallback list is back. What it measured is
below; the file stays until the built table is where it belongs.

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

## Done 2026-09-10

**Both images report a 40 GB floor.** `430390116` (ubuntu-26.04, 1.6 GB) and `430390168`
(fedora-44, 1.3 GB), built with no `--type` so `BUILD_TYPES` took the smallest on offer - `cx23`
at 0.0088 EUR/h in `nbg1`. Same contents as the 320 GB pair they replace, which is what says the
old floor was waste rather than need.

**A leg booted one on a type other than `cpx42`, and passed.** Both did: `cx23`, `all checks
passed` on ubuntu and on fedora, down to the gate hook, the vault refusals and the daemon socket.
That is the fallback proving it can fire rather than being argued to.

**40 GB is not close.** Sampled every 30 seconds across a whole ubuntu leg - working tree, build,
six native images, install, doctor, tier 1 with its container work - the peak was **3,631 MB of
38 GB usable, 10%**, and it stopped growing two minutes before the end. Fedora finished at 3.2 GB,
9%. The open question below asked whether the disk survives a full build; it survives it four
times over.

**What the images actually contain, checked against the list in `AGENTS.md` rather than by eye:**
GraalVM 25.0.2, the musl toolchain and a musl-built `libz.a` under the build user's
`~/.local/opt`, gcc 15.2.0, `zlib.h`, `dnsmasq` with `nftset`, `ubuntu:24.04` and `alpine:3.20`
pre-pulled, a 95 MB `~/.m2`, and on fedora SELinux `Enforcing` with `sokar_socket` loaded.

**One trap worth writing down.** `podman images` as `root` shows nothing: rootless podman keeps a
store per user and the images belong to `build`. Asked as root it looks exactly like an image that
was never pulled - which is the shape of the three rebuilds that shipped incomplete.

**`Spec.DEFAULT_TYPES` names four types again**, `cpx42` first because the runner blocks while the
rented machine builds, then `cx43`, `cpx32`, `cx33`. `cx23` is left out on purpose: it works, and
two cores stretched the leg to about 25 minutes, which in CI the runner pays for.

## Still open

- **How fast `cx43` actually builds.** It carries the same 8 cores and 16 GB as `cpx42` at about a
  quarter of the price, and that is the whole reason it sits second - but it is unmeasured. The
  only comparison that exists is `cpx42` against `cx33`, which changes the core count at the same
  time. This does not block the fallback, because a fallback has to work rather than be quick; it
  matters only if `cx43` is ever proposed as the default, which would be a change about money.
- **Whether a later rebuild quietly raises the floor again.** Nothing enforces the small build. A
  `--type cpx42` chosen for speed - exactly what produced the 320 GB floor - would empty
  `DEFAULT_TYPES` back to one entry without anything failing.

  **Where it could enter is now known rather than guessed.** Six call sites rent a machine:
  `build.yml` and `update.yml` in each of the three agent repositories. On 2026-09-10 not one of
  them passes `--type`, so they all take `DEFAULT_TYPES` and this file's ordering decides what
  they get. That is a property of today, not a guarantee - the first `--type` written into one of
  those six for speed is where the floor returns, and it would read in review as a performance fix
  rather than as a change of what can boot the image. A guard, if one is ever built, has those six
  lines to look at.

## What was to be checked, and is now answered

- **Is 40 GB actually enough for a leg?** The image rests at 2.7 GB and the build adds a Maven
  repository, six native images and container layers. It has never been measured on a 40 GB disk
  under a full leg, only under a snapshot build — and running out of disk is a hard failure that
  the type fallback does not catch.
- **Does a smaller build machine change what the image contains?** It should not, but the recipe
  installs from distribution repositories, and a package set is a moving target.
