# B43 — Tasks After The Machine Restarts

**Status:** open, and smaller than when it was written on 2026-09-11. What made a restart
unrecoverable moved to [B44](B44-One-Way-To-Start-Work.md), which fixes the cause. What is left is
whether anything should happen by itself when a machine comes back, and how a person finds out.
What a task brings back *with* it is
[B46](B46-The-Conversation-A-Restart-Loses.md): this requirement is about the container, that one
is about the conversation inside it.

## What happens today

Nothing brings a task back, and nothing says a restart is why it is down:

    NAME                       PROJECT   CLASS     STATE          AGE   HELPERS
    sokar-utils4j-shell-5684   utils4j   guarded   Exited (143)   1d    0

Measured on the Ubuntu test VM:

    --list-boots    -3   2026-09-09 16:31:12  ->  2026-09-09 17:53:44
                    -2   2026-09-09 17:53:46  ->  2026-09-09 20:10:07
    container       FinishedAt 2026-09-09 17:53:40   ExitCode 143

143 is 128 plus SIGTERM: the shutdown killing everything it had. Two seconds later the machine was
back, and the first podman call after the boot ran `system refresh`, which is what marks the
containers stopped. The workspace, the branch and the uncommitted work are all still there.

## Two things this is not

**Not lingering.** `loginctl enable-linger` keeps a user's processes alive across a *logout*, and
every machine Sokar rents runs it (`Rental.java`, `AgentLeg.java`, `Snapshots.java`). It does
nothing for a reboot. It was the first explanation reached for here, and it was wrong twice before
`--list-boots` was asked; recorded so nobody reaches for it a third time. That the libvirt test VMs
lack it is a separate gap, and a real one.

**Not a podman restart policy.** `--restart=always` or `podman-restart.service` would bring the
*container* back and nothing else. A task is a container plus the helpers Sokar started beside it,
and the contract already says what that combination means:

> a container that is up with no helpers has lost its gate or its clearance watcher, and is not
> the same thing as a healthy task

The cheap fix produces exactly the state the interface exists to warn about: a task that looks up,
with an agent in it, and no gate between its commits and the upstream. Whatever brings a task back
goes through `Start`'s path, not podman's.

## What must be true

1. After a restart, a person can see **that** a restart is why their tasks are down, without
   reading a boot log. `Exited (143)` alone does not say it.
2. Getting them back does not require remembering names, per
   [B25](B25-Names-The-Operator-Should-Not-Have-To-Find.md).
3. Anything that comes back comes back whole - container and helpers - or is reported as not having
   come back. A half-resumed task is never presented as running. B44 makes this possible; this
   requirement is what makes it happen without being asked task by task.
4. Nothing resumes a task into a credential state it should not have. The gate token is adopted
   rather than reissued; one whose hours ran out while the machine was off is expired, and an agent
   that wakes holding it reads the failure as a bad credential rather than as a clock.

## Acceptance criteria

- A machine with tasks on it is rebooted. `sokar task list` distinguishes tasks the machine took
  down from tasks a person stopped, and a test asserts that on a real restart.
- Bringing them back is one command that names none of them, and its report says per task whether
  it came back whole, partially, or not at all.
- A task whose token expired while the machine was down is refused or renewed deliberately, never
  resumed silently into a state where the agent's next call fails.
- The interface is told which tasks are in this state over the existing contract, without polling.

## To be checked

1. **Automatic or offered?** Resuming everything on boot is a surprise on a shared machine and a
   cost on a laptop; offering it is another thing to notice. The answer probably differs between a
   rented CI machine and somebody's desk. Current leaning: do not act, but make it visible.
2. **Who runs it?** The daemon is the only thing present at boot, but a resume that starts a gate
   and a broker is not obviously the daemon's to do unasked - and after B44 it needs the vault
   unlocked, which at boot there is nobody to do.
3. **What about a task that was `UNATTENDED`?** It was started with a prompt and left to run. Its
   agent's session is gone; resuming the container does not resume the work, and pretending
   otherwise is worse than leaving it down.
4. Whether `Exited (143)` should be translated at all, given
   [B11](B11-What-A-Task-Says-About-Itself.md) says plainly not to parse runtime state strings.
