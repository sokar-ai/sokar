# B75 — A Login Nobody Measures Is A Login That Does Not Work

**Status:** open. The chain that logs an agent in is exercised by the build, with an agent that
fakes a login, so that a break is found by a run rather than by a person.

## What happened, because this did not exist

`sokar vault login` could never store anything, for any agent, and nobody knew.

`podman cp container:/home/agent/.claude <target>` copies the **directory**, leaving
`<target>/.claude/.credentials.json`; the extractor is handed `<target>` and looks for
`<target>/.credentials.json`, one level above the file. Every login therefore found nothing and
answered *"the login left no credential in ~/.claude — it may have been cancelled"*.

**The message is what made it survive.** It names the one cause that puts the fault on the person,
and it is plausible: somebody who has just been asked several questions by an unfamiliar agent
might well have answered one wrong. It was believed by the operator, by two agents and by me,
across three attempts on 2026-09-19, and each time it moved the search somewhere else.

**What it cost:** an afternoon of three people, and two fixes that were right about something else
- the exit code, and collecting only after the process ends - built and shipped before the real
cause was measured. What finally found it was running `podman cp` twice by hand and looking at the
two shapes.

**What nothing in the pipeline would have caught**, and still would not: no unit test can see the
shape of `podman cp`, no acceptance scenario logs in, and the end-to-end legs have no account to
log in with. The only measurement that exists is one operator, once, by hand.

## What must be true

1. **The build runs a login, end to end, and fails when it stops storing.** From the throwaway
   container to a named credential in a vault - the same code path a real agent takes, not a
   parallel one written for the test.
2. **An agent fakes it.** The stub agent declares a `login` section whose arguments write a
   credential file where its manifest says its config directory is, and exits. No account, no
   network, no secret: the file's contents are a fixture.
3. **The stub's login is a login, not a special case.** It goes through `vault login`, the copy,
   the extractor and the store, and nothing in Sokar knows it is a stub. A test path that skipped
   any of those would have passed through the whole of 2026-09-19 without noticing.
4. **Both moments are measured**: the credential stored while the login still runs, and the
   credential stored after it ends when the vault was shut. They are different code, and only one
   of them existed before this.
5. **It runs where containers run.** That is the acceptance suite on a real machine, not the unit
   tests - the fault being guarded against lives in what podman does, and a mock of podman would
   have agreed with the broken code.

## What this does not ask for

**No account, ever.** A real subscription login cannot be automated here and must not be attempted:
the value of this is the plumbing, and the plumbing is the part that broke.

## To be checked

- **Whether the stub agent can carry this**, which is Agent Smith's to answer: his stub already
  installs its own tool into the image, so writing a file at a declared path may be a small
  addition - or it may cross what a stub should pretend to be. If it does, a second fixture agent
  that exists only for this is the alternative, and is worse because it is one more thing to keep
  in step.
- **Where the fixture credential's type comes from.** A real one is `oauth`, and a stub that
  always says `api-key` would leave the type mapping unmeasured - which is its own silent path.
