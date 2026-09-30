# B87 — A Machine Joins A Project In One Step

**Status:** built 2026-09-30 except forge access (point 5, the deploy key); written 2026-09-29 from `sokar-project` PJ12 at the operator's word, relayed by Agent
Coordinator. All of PJ12's questions are decided. Priority: after B86. The interface's half (the enroll
action, choosing a key, the fingerprint comparison, showing a new deploy key's public half) is
`sokar-frontend`'s and waits on this.

## What must be true

**A machine that is to take part in a project gets there in one step a person can follow.** Its public
keys reach every other machine of the project through a change the operator reviews and signs, no secret
leaves the machine that made it, and the one thing a person types by hand is the project's trust anchor.

## The keys, and how each travels

| Key | Secret or public | How it reaches others |
|---|---|---|
| The project's signing key (signs `project.yml` commits) | public, pinned per machine | once per machine, out of band: `project follow --signed-by` |
| An installation's message key (one per machine, never per task) | secret stays at 0600; public half travels | `allowed_signers` in the project's repository, changed only by signed commits |
| Matrix accounts and their tokens | secret | not distributed: made per task by the local homeserver (B86) |
| Forge access for the gate | secret | per machine, in its vault |

**Secrets never travel. Public keys travel through the project's own repository**, which every machine of
the project already follows and verifies: a signed commit, checked against the key pinned out of band, and
never one that is not a descendant of the one in force - so a removed signer cannot be put back by
replaying an older commit.

## What `sokar` builds

1. **`sokar project enroll <project>`** makes the machine's message key if it has none, and writes its
   **public** half as a change to the project's `allowed_signers`, labelled with the machine.
2. **The change goes through the machine's own gate**, as all work does, waiting for a person.
3. **The review names a new or removed signer as dangerous by kind** - `allowed_signers` joins the fixed
   list of B38's ranked review.
4. **Reading `allowed_signers` from the followed configuration.** Every machine that follows the project
   picks the change up at its next reconciliation; nothing is copied by hand.
5. **Forge access, either way a person chooses:** take a key the machine already has, or make a deploy key
   for this machine. A made key's public half is printed for a person to add to the forge once; either
   key's secret half stays in the vault. One deploy key per machine, never a forge application whose
   private key would sit on every machine.
6. **Removing a machine is the same path backwards:** a signed commit that deletes its line.

**What stays a manual step, on purpose:** the project's trust anchor. A new machine is given the fingerprint
of the key the project's configuration is signed with, once, out of band; a key is never read from the
repository it verifies. `enroll` prints the fingerprint it was offered beside the one it expects, so the
comparison is easy; the step itself stays.

## Decided 2026-09-30, by the operator, while building it

- **The signed merge is `sokar gate approve <name> --signed`.** Approving today only pushes the reviewed
  commit upstream - no merge, no signature - and every machine accepts a configuration only when its commit
  is signed with the project's key, which is the operator's. With `--signed`, approve makes a merge commit of
  the reviewed work signed with the git signing key configured in the person's own git where they approve
  (ssh-agent included), and pushes that. The machine never holds the key; without one configured it refuses
  and says so.
- **The file is `allowed_signers` beside `project.yml`**, at the repository's root, read from the followed
  configuration like the project file.
- **The change is put up for review in the project's mirror** as `refs/sokar/incoming/enroll-<machine>`: it
  appears in `gate pending`, `gate review` and the daemon's `Review` exactly as a task's work does.

## As built, 2026-09-30

- **`sokar project enroll <project> [--remove] [--as <principal>]`** (a project followed and verified):
  from the configuration in force, this machine's line (`sokar@<host> ssh-ed25519 ...`) is written into
  `allowed_signers` - replacing an older line of the same machine, or deleted with `--remove` - committed in
  a scratch clone with hooks off, and pushed to the project's mirror as `refs/sokar/incoming/enroll-<machine>`.
  It prints this machine's key fingerprint and the fingerprint pinned for the project's configuration.
- **`ReviewRanking`**: `allowed_signers` is dangerous by kind, "a signer list".
- **`sokar gate approve <name> --signed`** and the daemon's `Approve(signed)`: a merge signed by the
  approver's own git, pushed; an unsigned result is not pushed.
- **`KnownPeers`** reads `allowed_signers` from each followed, verified configuration in force, after the
  operator's own file and the machine's shared keys. A removal applies at the next reconciliation, and
  following never moves to a non-descendant, so an older commit cannot bring a signer back.
- **Not built: forge access (point 5)** - making a deploy key for the machine, or taking one it has.
- **Proven:** `GitGateTest` (signed and unsigned approval with real git and a throwaway key),
  `ReviewRankingTest`, and on the VM `project-enroll.feature`: follow a signed configuration, enroll,
  review, approve signed, and the upstream holds the signed merge with the machine's line.

## Acceptance

- A second machine takes part in a project after one enroll, one review and one signed merge, and no public
  key is copied by hand to any machine.
- No secret appears in the project's repository, in a review, or in anything `enroll` prints.
- A signer removed by a signed commit is refused at once on every machine, and replaying the older commit
  does not restore it.
- The trust anchor is the only value a person types.

## To be checked

None: PJ12's questions were decided before it was handed over.
