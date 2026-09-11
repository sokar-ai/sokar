# B43 — Tasks After The Machine Restarts

**Status:** open. Written on 2026-09-11 after a container's death was explained wrongly twice
before it was measured. Nothing here is designed yet; what is settled is what actually happens.

## What happens today

Nothing brings a task back. A machine reboots, every container is down, and the only thing that
says so is a state string in `sokar task list` that reads like a failure:

    NAME                       PROJECT   CLASS     STATE          AGE   HELPERS
    sokar-utils4j-shell-5684   utils4j   guarded   Exited (143)   1d    0

Measured on the Ubuntu test VM:

    --list-boots    -3   2026-09-09 16:31:12  ->  2026-09-09 17:53:44
                    -2   2026-09-09 17:53:46  ->  2026-09-09 20:10:07
    container       FinishedAt 2026-09-09 17:53:40   ExitCode 143

143 is 128 plus SIGTERM: the shutdown killing everything it had. Two seconds later the machine was
back, and the first podman invocation after the boot ran `system refresh`, which is what marks the
containers stopped. The workspace, the branch and the uncommitted work are all still there. Only
`sokar task resume <name>`, typed by hand, brings any of it back - and only for somebody who knew
to look.

## Two things this is not

**Not lingering.** `loginctl enable-linger` keeps a user's processes alive across a *logout*, and
every machine Sokar rents runs it (`Rental.java`, `AgentLeg.java`, `Snapshots.java`). It does
nothing for a reboot. It was the first explanation reached for here and it was wrong; recorded so
the next person does not reach for it either. That the libvirt test VMs lack it is a separate
gap, and a real one.

**Not a podman restart policy.** `--restart=always` or `podman-restart.service` would bring the
*container* back and nothing else. A task is a container plus the helpers Sokar started beside
it - the git gate, the credential broker, the clearance watcher - and the contract already says
what that combination means:

> a container that is up with no helpers has lost its gate or its clearance watcher, and is not
> the same thing as a healthy task

So the cheap fix produces exactly the state the interface is built to warn about: a task that
looks up, with an agent in it, and no gate between its commits and the upstream. Whatever brings a
task back has to be `Resume`'s path, not podman's.

## What must be true

1. After a restart, a person can see **that** a restart is why their tasks are down, without
   reading a boot log. `Exited (143)` alone does not say it.
2. Getting them back does not require remembering names. What Sokar already knows, the operator
   is not made to find (see [B25](B25-Names-The-Operator-Should-Not-Have-To-Find.md)).
3. Anything that comes back comes back **whole** - container and helpers - or is reported as not
   having come back. A half-resumed task is never presented as running.
4. Nothing resumes a task with credentials it should no longer have. The phantom token is adopted
   rather than reissued, because a container's environment is fixed at creation; a token whose
   hours ran out while the machine was off is expired, and an agent that wakes holding it reads
   the failure as a bad credential rather than as a clock.

## Acceptance criteria

- A machine with tasks on it is rebooted. `sokar task list` distinguishes tasks the machine took
  down from tasks a person stopped, and a test asserts the distinction on a real restart.
- Bringing them back is one command that names none of them, and its report says per task whether
  it came back whole, partially, or not at all.
- A task whose token expired while the machine was down is refused or renewed deliberately, never
  resumed silently into a state where the agent's next call fails.
- The interface is told which tasks are in this state, over the existing contract, without
  polling for it.

## To be checked

1. **Automatic or offered?** Resuming everything on boot is a surprise on a shared machine and a
   cost on a laptop; offering it is another thing to notice. Neither is obviously right, and the
   answer probably differs between a rented CI machine and somebody's desk.
2. **Who runs it?** The daemon is the only thing present at boot, but a resume that starts a gate
   and a broker is not obviously the daemon's to do unasked.
3. **What about a task that was `UNATTENDED`?** It was started with a prompt and left to run. Its
   agent's session is gone; resuming the container does not resume the work, and pretending
   otherwise is worse than leaving it down.
4. Whether `Exited (143)` should be translated at all, given
   [B11](B11-What-A-Task-Says-About-Itself.md) says plainly not to parse runtime state strings.
