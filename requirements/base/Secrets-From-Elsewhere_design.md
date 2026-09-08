# Secrets from elsewhere — design

**Undecided, deliberately.** This records a discussion rather than a conclusion, so the decision can
be taken later on the evidence rather than re-derived from memory.

**It covers both secrets, because they turned out to be one question.** The vault passphrase and a
provider credential were argued separately and reached opposite answers within a day, on reasoning
that moved under both. The operator's decision is to hold them together and decide them together.

**The rule that holds until then: both are entered at the node, over ssh.** No `Unlock`, no
`StoreCredential`. An interface asks for neither and says *where* instead — the sentence it already
uses, and one that stays true whichever way this is decided.

[B18](B18-Storing-A-Credential-From-Elsewhere.md) states what must be true *if* a credential
becomes storable from an interface. Whether it does is decided here.

## How the answer moved, and why that is the reason to hold both

Worth keeping, because the sequence is the argument for not deciding either one quickly.

1. **Both refused**, on the reasoning that a secret should not pass through a GUI or the varlink
   layer.
2. **The credential half was challenged**: the recommended alternative — paste it into an ssh
   session — puts the key through a browser, a clipboard, a terminal emulator's paste buffer and
   its **scrollback**, which many terminals persist to disk. Measured: `vault put` read a typed
   credential through the echoing stream while the passphrase never did. The advice pointed at the
   path that wrote the secret down. **That is fixed** — a typed credential is now read without
   echo — but the argument it broke does not come back.
3. **The rule was refined**: a secret may be *transferred*, never *stored*.
4. **Then the passphrase was examined** and one of its two stated reasons — that this is a
   capability boundary — did not survive either.

Two of the three original arguments failed under examination. That is the reason both are now held
rather than one being settled on what is left.

## What the passphrase is, mechanically

_The credential half has no equivalent section: a credential is a value the vault stores. Everything mechanical about it is in [B18](B18-Storing-A-Credential-From-Elsewhere.md)._

Facts from the code, not proposals.

- **Argon2id**, 3 iterations, 64 MiB, parallelism 4, 16-byte salt, AES-GCM with a 12-byte nonce
  and a 128-bit tag, 32-byte derived key. `VaultFile`.
- **The KDF parameters and the salt live in the file header** and the read path takes them *from
  the file*, bounded by `checkCost`. They are not pinned in code for reading, and they are not
  secret.
- **`write()` generates a fresh salt on every write** and re-derives the key. See the open question
  about that below.
- **The passphrase is `char[]` end to end** — `Console.readPassword` → `PassphraseTiers` →
  `KernelKeyring.store(char[])` — and the byte buffers are wiped with `Arrays.fill`.
- **It is cached in `KEY_SPEC_USER_KEYRING`**, per uid, linked into the session keyring, optionally
  with a timeout.
- **Non-interactive unlock already exists**: `vault unlock --passphrase-command` and
  `--systemd-credential`.

## Why it was treated as different from a credential

Three arguments were made. Two survive; one does not, and the failure is recorded because it was
used to justify the current rule.

**1. It would destroy a property the code has.** A varlink parameter arrives as `Map<String,
Object>` — a **`String`**: immutable, unwipeable, alive until GC, present in any heap dump. The
console path is `char[]` and wipeable. So putting the passphrase on the wire removes the one place
this codebase handles a secret carefully.

**Weakened by what that property is worth.** The credential path is already `String` throughout —
worse, adding one credential builds a single `String` holding *the entire decrypted vault*
(`Json.write(document(entries)).getBytes(UTF_8)`). So the careful handling protects the key while
everything the key guards is handled carelessly. The honest form of this argument is *"do not level
the one good thing down to the rest"*, not *"this is categorically different"*.

**2. Frequency, and the caching pressure it creates.** A credential is typed once. A passphrase is
typed at every unlock — after a reboot, after `vault lock`, after a TTL expires. A secret typed
once resists *"remember me"*; a secret typed daily generates enormous pressure to store it, and
that pressure lands exactly on the guarantee B18 asks a client to keep.

**And the failure is not proportional.** A cached credential is one leaked secret. A cached
passphrase makes encryption at rest decorative and makes `vault lock` meaningless, because the
client can silently reopen what the operator just closed. **This is the strongest argument on this
page.**

**3. Blast radius.** It opens every credential and the ssh signing key seed. Real, but a
multiplier on the same exposure paths rather than a new argument.

### The argument that did not survive

It was called a **capability boundary** — *"a remote client cannot open the store"*. It mostly is
not one:

- the socket is owner-only, so anyone who can connect is already the uid that owns the vault file
  and can run `sokar vault unlock`;
- over a forwarded socket they authenticated with ssh, and with a shell they can unlock directly;
- `--passphrase-command` and `--systemd-credential` already provide non-interactive unlocking by
  design.

**It is a real boundary in exactly one case: forced-command ssh with no shell.** There it stops
being convenience and becomes the only route — the same dead end B18 documents for credentials.

## The client is not one platform

The interface runs on **macOS, Windows, Linux and Android**, and the protections are not uniform.
Any requirement written as a single sentence would be false on at least one of them.

| | screen capture protection | secure keyboard input |
|---|---|---|
| Android | `FLAG_SECURE` — screenshots, recording, recents thumbnail | the **IME is a third-party app** (below) |
| Windows | `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` | — |
| macOS | `NSWindow.sharingType = .none` | `EnableSecureEventInput` |
| **Linux / X11** | **none** — any client may read any window and grab the keyboard | **none** |

Wayland is materially better than X11, but portal behaviour varies by compositor and **this has not
been measured**.

**The Android IME is a threat with no terminal equivalent.** The passphrase is typed through a
keyboard app the user chose, which sees every keystroke; `FLAG_SECURE` does nothing about it.
`textPassword` plus `IME_FLAG_NO_PERSONALIZED_LEARNING` are requests, not boundaries.

**And mobile cuts the other way on the main question.** Every argument for the current rule rests
on *"they already hold an ssh connection, so they can type it there."* On a phone that is close to
useless. The fallback degrades hardest on the platform where interface entry is most likely to be
the only realistic route — which is the strongest argument *for* eventually allowing it.

## Options, with what each is actually worth

These options are written for the passphrase, which is the harder case. **Applied to a
credential each one is the same shape with the frequency argument removed** — a credential is typed
once, so option D does nothing for it, and option C is meaningless because a credential is not a
key-derivation input. So a credential could be decided more permissively than the passphrase, and
the one thing that should not happen is deciding it *by inheritance* in either direction. That is
what happened twice already.

### A — keep the rule: entry at the node only

**What it costs:** the mobile case above, and the forced-command case has no route at all.
**What it buys:** the `char[]` property, no caching pressure, and a guarantee that is the same on
four platforms because it does not depend on any of them.

### B — allow transfer under B18's rule, with extra conditions

The passphrase becomes a parameter; the daemon receives it, derives, wipes what it can, stores in
the keyring, and never lets it become a `String` it retains.

**The extra conditions beyond B18's**, because the caching failure is the one that matters:

- **no "keep me unlocked" affordance of any kind** — the single affordance that undoes the store;
- platform capture protection **where the platform provides it**, and the interface says on screen
  where it does not;
- nothing in a log, trace, echoed error, streamed reply or crash report, on any path.

**A guarantee phrased as "stored securely by the platform" would mean four different things.**
Android Keystore, macOS Keychain and Windows DPAPI can be hardware-backed and user-authenticated;
the Linux Secret Service is frequently absent or unlocked for the whole session.

### C — derive on the client, send the key

The client fetches the header (salt and parameters — not secret), runs Argon2id locally, and sends
32 bytes. **The human-memorable passphrase would then exist only on the laptop, ever** — which
matters because people reuse passphrases, and a captured passphrase is a credential to try
elsewhere while a captured key is not.

**Blocked today by the per-write salt.** A cached derived key is invalid the moment anything is
written. This needs the salt to become per-vault, which is the open question below.

**And it got worse with four platforms.** Argon2id at 64 MiB / t=3 / p=4 realistically needs a
native library per platform — NDK, `.dylib`, `.dll`, `.so` — on a client that ships none today. A
pure-Dart or pure-JS implementation at those parameters is expected to take seconds rather than
milliseconds, **which has not been measured**. On low-end Android, a transient 64 MiB allocation is
a real cost and being killed mid-derivation is a failure mode to design for.

**Assessment:** its benefit is narrow — reuse, not vault compromise — and its cost multiplied by
four. Probably not worth it, but recorded because the reasoning is not obvious.

### D — attack the frequency instead

The strongest objection was caching pressure, which is a function of how often the passphrase is
typed. Unlock at boot via `--systemd-credential` turns daily entry into monthly. **This needs no
new code, no contract change and no client work**, and it is platform-independent.

On mobile it matters more rather than less: every avoided entry is one that never goes through an
IME.

Note that the operator already ruled `--for` gets **no default** — a bound arriving as a default
would start asking people for a passphrase they were never asked for before.

## The ceiling, which applies to every option

**In a managed runtime a secret cannot be reliably erased.** A moving collector copies objects, and
strings are immutable in Java, Dart and JavaScript alike. Any requirement saying *"the interface
wipes the passphrase"* asks for something the language cannot deliver.

The achievable goal is **fewer copies, shorter lifetimes, and never at rest** — never erasure. That
is true of Sokar as much as of any client, and a document that promised otherwise would be the kind
of lie the requirements warn about.

## Open questions

- **Is the fresh salt on every write deliberate?** A salt must be random per *vault*; uniqueness of
  the encryption comes from the nonce, which is already fresh per write. Re-salting forces a full
  Argon2id derivation — 64 MiB, 3 passes — on every `sokar vault put`, for no benefit identified
  here. It also blocks option C. Worth answering on its own merits.
- **What Wayland actually offers**, per compositor, versus X11's nothing.
- **What Argon2id at these parameters costs** in the client's language on a low-end Android device.
- **Whether the forced-command case should be served at all**, or documented as a dead end. It is
  the one case where the current rule denies a capability rather than an convenience.

## Related

- [B18 — Storing A Credential From Elsewhere](B18-Storing-A-Credential-From-Elsewhere.md), which
  settled the credential half and whose storage guarantee this would extend.
- [Authentication](../../doc/authentication.md), which documents the rule as it stands.
