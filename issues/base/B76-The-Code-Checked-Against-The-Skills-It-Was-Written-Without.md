# B76 — The Code Checked Against The Skills It Was Written Without

**Status:** open, written 2026-09-27 at the operator's instruction, **high priority, next after
B53**. Every agent opens this issue in its own repository; this is Sokar's.

## What happened

`AGENTS.md` names the skills this repository expects - **GraalVM**, and the **Java** set - and says
where they come from: `https://fuinorg.jfrog.io/artifactory/agent-skills/`. **Nothing had been
written with them.** They were not installed in the harness, and the section that asks for them
sits a thousand lines into a file an agent reads for the rule it needs.

Measured on the first module that was checked, `sokar-release`, written the same day without them:
reading it against `java-code-review` and `security-audit` found **two defects that its 104 tests
had not**, both fixed in `4a0e933`:

- **A credential went wherever a pom property pointed.** `GITHUB_TOKEN` was sent to the configured
  address, so a typo or a hostile change to one property sent a CI token to another host. It now goes
  to `https://api.github.com` only.
- **A fault answered the question.** An unexpected exception left the JVM with exit 1, which the
  callers read as "a person must look" or "they disagree". It now exits 2, unanswered.

**Tests that pass are not evidence that the code was reviewed** - they are evidence that it does what
its author thought of. The skills are a second author's list of what to think of.

## What this issue covers

**Every Java module in this repository**, read against:

    java-code-review    null safety, exceptions, collections, resources, API shape
    test-quality        what the tests prove, not how many there are
    security-audit      secrets, input from outside, what reaches another host
    concurrency-review  the daemon, the proxies, the watchers - shared state and threads
    clean-code, solid-principles
    graal               the modules built as native images: metadata, class initialization,
                        resources - the properties this project has learnt the expensive way

## How to get them

**The harness here does not load them**, and this session could not write to its skills
directory. Reading is enough: download each package as `AGENTS.md` *How to get them* says, check its
SHA-256 against the repository's, unpack it anywhere, and read `SKILL.md`.

## What must be true

**Every Java module has been read against the skills that apply to it, and each finding is either
fixed with a test that was watched to fail, or declined with the reason written down.**

## Acceptance

- A list of the modules, each with the skills it was read against and the commit that closed it.
- **Every fix carries a test proven to fail** by undoing the fix, as this repository already requires.
- **Where a skill and this repository disagree, the repository's measurement wins and the
  disagreement is written here** - for example `test-quality` asks for `@DisplayName`, and this
  repository names test methods as sentences.
- A skill's claim that this build's own behavior contradicts is recorded with the measurement. The
  first one: `sokar-release` guarded against the JDK HTTP client forwarding `Authorization` across a
  redirect; measured on JDK 25, it drops the header when the host changes, and a test now holds it
  to that.

## Done so far

    sokar-release    java-code-review, test-quality, security-audit    4a0e933

## Open questions

1. **The order.** The daemon and the proxies first, because they hold credentials and run threads
   - or the modules built as native images first, because `graal` is the skill most specific to
   this repository.
