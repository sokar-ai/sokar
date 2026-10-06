# B48 — The Overview That Stays Open

**Status:** soon.

**What must be true.** A person at a terminal watches their tasks change, from the same answer the interface
reads, without a loop they wrote themselves.

## Why

It is small, and it is the only way a person at a terminal sees what `task status` (its activity and since) and
the waiting reading (Task.screen and Task.lastMessage) went to the trouble of working out.

### What happens today

`sokar task list` prints a table and exits. Somebody who wants to watch their tasks - which is the
normal thing to want while three of them are running - types it again, or wraps it in a loop:

    while true; do clear; sokar task list; sleep 2; done

That is a fleet view written by hand, badly: it redraws whether anything changed or not, it
flickers, it loses the cursor, and it cannot tell the operator *which* line changed. Every person
running Sokar over ssh writes some version of it.

**The hard half is already built and is not reachable from a terminal.** The daemon serves
`Watch`, and it is careful in exactly the way a hand-written loop is not:

```java
// Only on change - and "change" cannot include the age. The runtime's state is a
// phrase carrying one: 'Up 3 seconds' becomes 'Up 4 seconds' a second later, so
// comparing the whole answer made this fire every second and told a fleet view to
// redraw because a clock had moved.
```

The wire client can consume it - `VarlinkClient.callMore` exists and hands each reply to a
consumer - and nothing in the CLI calls it. So the one client of `Watch` is the graphical
interface, and the terminal, which is where a person is when they are logged into the machine at
all, gets the snapshot.

### The thing that has to be decided rather than copied

`Watch` deliberately does **not** report an age-only change, and it is right not to. A view that
redrew because a clock moved is the failure that comment records, measured against a real task.

But *"idle for forty minutes"* is the number a person watching is actually watching, and it
changes once a minute by definition. So a watching terminal view cannot simply redraw what
arrives: it redraws the age from the timestamp it already holds - the contract carries that timestamp
precisely so arithmetic replaces parsing - and redraws the rest only when the stream says
something changed. One source for what is true, a local clock for how long it has been true. Get
this wrong in the other direction and the view either freezes its ages or asks the daemon for a
list every second, and the second is what `Watch`'s design exists to prevent.

## The shape

1. **One command shows the tasks on this machine and keeps showing them**, updating as they
   change, until the person stops it. It is the same listing `sokar task list` prints - not a
   second rendering that can drift from it.
2. **What it shows comes from the same answer the interface gets.** It consumes `Watch` rather
   than polling the inventory in a loop of its own; two change detectors for one question is how
   the CLI and the graphical view come to disagree about the same machine.
3. **It redraws when something changed, and for nothing else** - with the age as the stated
   exception, recomputed locally from the timestamp rather than fetched.
4. **Stopping it is Ctrl-C, and stopping it is not a failure.** It leaves the terminal as it found
   it: no lost cursor, no colours left on, no scrollback eaten. The reset this product already had
   to learn for attaching applies here too.
5. **It degrades rather than refuses.** Where output is not a terminal, it prints changes as they
   happen instead of drawing over itself, so `| tee` in a session log still produces something
   readable. Where the stream is not available it says so and falls back to the single listing,
   naming what it could not do.
6. **What needs a person is visible without reading every row.** Now that the daemon produces `waiting`, a
   task waiting on somebody is the thing this view exists to surface; a person should not have to
   compare columns to find it.

## Acceptance

- The command is started against a machine with tasks on it, a task changes state, and the view
  shows the change without being asked and without redrawing rows that did not change. Seen to fail: a test
  that changes one task's state sees no redraw, or sees every row redrawn.
- Nothing changes for a minute and the view redraws only the ages. Asserted, because the
  alternative passes a casual look and costs a redraw per second. Seen to fail: the assertion goes red when
  the view redraws a row other than its age, or asks the daemon for a list, while nothing changed.
- Ctrl-C returns to a shell whose cursor, echo and colours are as they were, asserted by the
  acceptance kit rather than by reading the code - this is the failure mode that was already met
  once when attaching, and it is only visible at a real terminal. Seen to fail: the acceptance scenario goes
  red when the terminal reset on exit is removed.
- Piped to a file, the output is readable line-by-line history rather than escape sequences. Seen to fail: a
  test of the piped output finds an escape sequence.
- The rendering is the same code that prints the one-shot listing, proven by a test that changes
  the listing's format and sees both change. Seen to fail: that test, when only one of the two changes.

## To be checked

- **Is it a flag or a verb?** `sokar task list --watch` reads as the listing that keeps going, and
  the house already has `sokar shield watch` as a verb. A flag on the listing keeps the two
  renderings obviously the same thing; a verb is easier to give its own options later. Decide once
  - the two will not be reconciled afterwards.
- **Does it need the daemon?** `sokar task list` does not: it builds its answer through
  `TaskInventory` in this process, so it works on a machine where nothing is running but podman.
  Consuming `Watch` makes the watching form need a daemon that the one-shot form does not, which
  is a difference an operator meets as an error message. Either that is stated plainly, or the
  watching form falls back to polling its own inventory and accepts being the second change
  detector this file's point 2 refuses.
- **Does it belong to one project or to the machine?** The listing is per machine today. Watching
  is the moment somebody wants only their own project's tasks, and a filter that exists in the
  watching form and not in the listing breaks the *"same listing"* promise above.
- **Does the same view want to show the clearance questions?** `Prompts` carries questions that
  expire, and a person watching their tasks is exactly the person who should see one. It may be
  the right thing here, or it may be what makes this view two things at once.
