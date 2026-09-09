# B35 — A Passphrase Nobody Can Type

**Status:** open, and it is a defect in the shipped binary rather than a missing feature. Found on
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
