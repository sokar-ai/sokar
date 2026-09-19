# B74 — A Project From Before Following Still Has A Way Out

**Status:** built on 2026-09-19. A project this machine lists but does not follow can be
previewed and cleared away, with the same refusals that protect a followed one.

## How a machine ends up with a project it cannot get rid of

`delete` was removed with B72, and rightly: a project comes to be here by following its repository
and goes away by `unfollow`. That is true of every project made from that day on.

It is not true of the ones already there. A project that came to be the old way — a task was
started with a file, which wrote a mirror and a registry entry — is in no follow record, so
`unfollow` has nothing to take hold of and answers *this account does not follow a project called
'objects4j'*. The project is still in the listing, still owns a mirror, an image and a recorded
file, and there is now no command that ends it.

**Measured, 2026-09-19.** The operator asked for `utils4j` and `objects4j` to be removed from the
`ubuntu26.04` VM and there was no command for it; they were cleared by hand — four locations per
project plus an image, after checking by hand that neither mirror held a review branch and that
each mirror's `main` was also in its source repository. Agent Frontend measured the same gap from
the interface the same hour (QF38): `Unfollow` with `dryRun` answers `NoSuchProject`, so the
interface can offer *Stop following* and have nothing to show for it, while `--force` would sweep
the project blind.

**What makes this worth a requirement rather than a shrug:** the checks that were done by hand are
the product. A mirror can hold pushes nobody has reviewed — work that exists in no other place —
and the only reason this particular removal was safe is that somebody looked. A person clearing a
project through an interface has no way to look.

## What must be true

1. **A preview exists for every project the machine lists**, followed or not. `unfollow --dry-run`
   and `Unfollow(dryRun: true)` answer `PREVIEWED` with `removes`, `keeps` and `unreviewed` for a
   project that is only a mirror, a registry entry and an image. `NoSuchProject` stays the answer
   for a name the machine does not list at all — that distinction is the whole value of the error.
2. **The refusals do not depend on how the project got here.** Without `force`, `HOLDS_WORK` when a
   mirror holds anything that was never reviewed, and `TASKS_RUNNING` when a task of it is up — the
   same answers a followed project gives, because what is at risk is the same.
3. **Everything the project owns is named, and nothing else is.** The mirrors of every repository
   it names, the registry entry, the build recipe, the upstream measurement, the image. A removal
   that leaves some of those behind produces a project that half exists, and one that reaches
   beyond them destroys somebody else's.
4. **What is kept is said as plainly as what goes.** The source repository is not Sokar's and is
   never touched; the vault is not the project's; a followed clone belongs to the follow record.
5. **One way in for both interfaces.** The terminal and the socket answer the same, because a
   person with only a terminal went around both refusals once already by deleting a directory.

## What this does not ask for

**`delete` does not come back.** A project that is followed is ended by `unfollow`, and this is the
same verb answering for a project that predates following rather than a second way to end one. The
name a person types should not depend on which era their project came from.

## Built, 2026-09-19

**The guard moved, not the machinery.** `ProjectDeletion` already knew how to preview and refuse
for any project the machine lists; both entry points refused before asking it. `unfollow` and
`Unfollow` now ask first and answer from the result.

**`NoSuchProject` means what it says.** It is the answer for a name this machine does not list -
not for one it lists but does not follow. The CLI refusal names what the machine does have, the
way every other name refusal here does.

**`--force` with nothing there is still a success**, so a cleanup trap can run it blind after a
failure without a script having to guard the guard.

**The preview says what is kept as well as what goes.** The project file is the operator's, and a
confirmation that lists only casualties invites the reader to assume the worst.

**`Unfollow` answers `following`** so an interface can say *stop following and remove* or just
*remove* without guessing.

**`unfollow` completes project names**, from the same list its refusal prints.

**Measured** in `ProjectUnfollowCommandTest`: a project nobody follows is previewed without being
touched, is cleared while its project file survives, is refused when its mirror holds work nobody
reviewed, and a name the machine does not list is still its own answer. The end-to-end script
checks that last half on a real install - the other half has no fixture there, because every
project that script makes is followed.
