# B23 — Secrets In This Process's Memory

**Status:** open, and deliberately *later*. Nothing here blocks anything; it is written down so the
ceiling is known rather than rediscovered, and so the partial fix already made is not mistaken for
the whole thing.

## What was fixed, and what it leaves

Storing anything in the vault used to build a single `String` holding **every credential it holds,
in plaintext** - `Json.write(document(entries)).getBytes(UTF_8)`. A `String` cannot be cleared,
lives until the collector reaches it, and appears in any heap dump.

That one is gone: the document is written into a buffer the vault owns and overwritten once the
bytes are encrypted, and the byte array is wiped too.

**What it leaves is most of the surface.** The read path parses JSON into `String`s,
`VaultEntry.value()` is a `String`, and every caller that looks at a credential holds one. So the
worst single copy is gone and the general property is not.

## The ceiling, which is the reason this is not simply "do the rest"

**In a managed runtime a secret cannot be reliably erased.** A moving collector copies objects, and
the original is left behind untouched; strings are immutable in Java as they are in Dart and
JavaScript. Growing a buffer copies its contents for the same reason.

So the achievable goal is **fewer copies and shorter lifetimes**, never erasure. A requirement
promising otherwise would be the kind of claim this project's own notes warn about, and any test of
it would be asserting something the platform does not offer.

## What must be true

**A credential's plaintext exists in as few places, and for as short a time, as the platform
allows - and what cannot be achieved is written down rather than implied.**

## Acceptance

- No structure holds more than one credential's plaintext at a time.
- A credential's value is not an immutable object on the paths that read, write and broker it.
- Every buffer holding plaintext is overwritten when the work that needed it is done.
- Where a guard cannot be tested, the code says so rather than looking covered.
- The documentation states that erasure is not achieved, and why.

## Notes

**The one place this is already true is the passphrase**, which is `char[]` from
`Console.readPassword` through `PassphraseTiers` to the kernel keyring, with the byte buffers
wiped. That is the shape the rest would take.

**The cost is spread rather than deep.** `VaultEntry` holding bytes changes every caller that reads
a credential - the broker, the wiring, the import, the listing - and each of those has to be
careful in a way it currently does not. That is why this is worth doing on purpose rather than
alongside something else.

## To be checked

- **Whether the broker can avoid a `String` at all.** It reads the real credential per request and
  puts it in an HTTP header, and the HTTP machinery takes strings. It may be that the last copy is
  unavoidable there, in which case this requirement should say so about that path specifically.
- **Whether it is worth it against the threat it addresses.** A heap dump of the daemon requires
  the ability to read the daemon's memory - which is the same uid that can read the vault file and
  the passphrase in the keyring. The honest case for this is defence in depth and crash artifacts,
  not a boundary somebody is being kept outside of.
