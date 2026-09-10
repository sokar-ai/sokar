# B35 — A Passphrase Nobody Can Type

**Status:** closed on 2026-09-10, and **it does not reproduce**. The acceptance criteria are met and
the workaround is gone; what is not explained is what was measured on 2026-09-09. Read the section
at the end before trusting the diagnosis above. Found on
2026-09-09 while trying to make an acceptance step type a passphrase at the prompt, which is the
one thing this product asks an operator to do.

## What happens

Every command that asks for a passphrase reads it with `Console.readPassword()`, which switches
terminal echo off first. In the native image that call throws:

```
java.io.IOError: java.io.IOException: Inappropriate ioctl for device
    at java.base/jdk.internal.io.JdkConsoleImpl.echo(Native Method)
    at java.base/jdk.internal.io.JdkConsoleImpl.readPassword0(JdkConsoleImpl.java:151)
```

Reproduced three ways on 2026-09-09 - through `script`, through `ssh -tt`, and through a real
`pty.fork()` driving the locally built binary on the development machine itself. The plain
`readLine` prompt beside it works: `Value for 'demo':` accepts input, and the passphrase prompt
that follows throws. **It is the echo switch, not the console.**

## What it costs

Every interactive passphrase path: `vault put` creating a store, `vault unlock`, `vault
passphrase`, `vault agent`, and the vault tier inside `AgentLogin`. All of them end in an
unhandled `IOError` with a stack trace, which is also the wrong shape for a refusal - see the
handler B24's work added, which turns an unhandled failure into one line.

**What still works** is every non-interactive route: `--passphrase-command`, the kernel keyring,
and a systemd credential. That is why this has gone unnoticed: the documented setup path for a
machine uses `vault unlock` once, and every path after it reads the cached passphrase.

## What must be true

**An operator can type a passphrase at the terminal, and nothing about how Sokar is built prevents
it.**

## Acceptance

- Typing a passphrase works in the shipped native binary, on both supported distributions.
- It is not echoed. That is the whole reason `readPassword` was used, and a fix that reads the
  line plainly has traded a crash for a leak.
- A terminal that genuinely cannot switch echo off is refused with a sentence, not an `IOError`.
- An acceptance scenario types a passphrase at a real pty, so this cannot regress unnoticed - the
  machinery now exists and this defect is exactly what it was built to catch.

## Notes

**The acceptance suite found this by trying to do what a person does**, which is the argument B27
makes for its own existence, arriving sooner than expected.

**It also shaped the kit**: the step that unlocks a vault writes the passphrase to an owner-only
file and points `--passphrase-command` at it, because typing it - the honest way - does not work.
That workaround is temporary and says so.

## To be checked

- **Why the native image cannot do it.** `JdkConsoleImpl.echo` is a native method; whether the
  image needs a flag, a reachability entry, or a different console implementation is unmeasured.
- **Whether a JVM run of the same code works**, which would settle "native image" versus "this
  code" in one command.
- **What the fallback should be** if it cannot be fixed: reading the line with echo on is not
  acceptable, so the honest answer may be to refuse and name the non-interactive routes.


## It does not reproduce, and that is the finding

Tried three ways on 2026-09-10, all of them the ways this file claims it fails, and all of them working:

- **The same binary**, `app/target/sokar`, built at 18:09 on 2026-09-09 - before this file was
  written at 20:07 the same day - driven through a real `pty.fork()`. `Vault passphrase:` prompts,
  reads, does not echo, and caches.
- **The packaged binary on a test machine**, over `ssh -tt`. Same.
- **Through the acceptance kit's own terminal**, which is where the defect was found in the first
  place. Same.

Nothing in `ConsolePassphrase` or `VaultPutCommand` has changed since - `git log` over both since
2026-09-08 is empty. So the binary, the code and two of the three reproduction routes are the same,
and the behaviour is not.

**What settles the "to be checked" items anyway:**

- **A JVM run of the same code works** - `java.io.ProxyingConsole`, 7 characters, not echoed - so
  had it been real it would have been the image rather than this code.
- **A minimal native image also works**, built with `native-image --no-fallback` on GraalVM 25.3.4,
  which is newer than the 25.0.2 the product pins. If the fault was ever real it may live in that
  gap, and that is the one hypothesis worth keeping.
- **The fallback question is moot** while it works. Should it return, driving `tcgetattr` and
  `tcsetattr` through FFM reads a line with echo off in both runtimes and was proven at a pty
  during this work - the same mechanism `KernelKeyring` already uses, so it is in idiom.

## What changed

**The kit types the passphrase at the prompt**, which is what this requirement asked for and what
an operator does. The workaround it describes - writing the passphrase to an owner-only file and
pointing `--passphrase-command` at it - is gone, so the passphrase now reaches no file and no
command line at all. The step asserts it was not echoed, which is the property the prompt exists
for and would have caught a fix that traded a crash for a leak.

Exercised by 13 scenarios in an agent repository against a real machine, credential scenarios
included.

## The lesson worth keeping

**A defect recorded from one environment is a measurement, not a property.** This one was written
with a stack trace and three reproductions and still did not survive contact with the same binary a
day later. What is missing from the original is what would make it checkable: the exact command,
the terminal it ran under, and whether the process was sandboxed - the last being the one
difference nobody wrote down.
