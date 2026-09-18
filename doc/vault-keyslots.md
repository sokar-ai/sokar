# Keyslots: how the vault is opened

The vault is one AES-256-GCM file holding a JSON document. **No credential is its key.** A master
key is generated once, when the vault is created, and stored only in wrapped form — once per
credential that may open it. Adding a device adds a wrapped copy; removing one deletes a wrapped
copy. Nothing else changes: no re-keying, no other device disturbed, no passphrase rotated.

Adapted from what LUKS does with its keyslots. Built in 2026-09; this page is what outlives the
requirement that asked for it.

## The file

```
magic "SOKARVLT" | version | header length | header JSON | content nonce | AEAD ciphertext+tag
```

Everything from the magic to the content nonce is **authenticated as additional data**, so nothing
in the header can be changed without invalidating the content's tag — not the KDF parameters, not a
wrapped key, not a device's recorded name.

**There is one version, and no migration.** Version 2 was a hard cut, decided while the only vaults
that existed were a tester's own. A file of any other version is refused rather than half read; the
message says to create the vault again with `sokar vault init`, which is possible because everything
a vault holds can be entered again.

**Why the header is JSON rather than packed binary.** It holds a table of variable length whose
shape will grow — a slot has a name, a storage kind and two timestamps — and a hand-rolled parser for
that is a place for a length field to be read wrong. It costs a few hundred bytes in a file holding
tens of entries.

## The slots

**Keyslot 0 is the passphrase**, wrapped with Argon2id, and it is the *recovery* credential: typed
by a person, stored nowhere, not what day-to-day unlocking uses. Every other slot is one device,
which keeps a 32-byte share in its own platform's keystore; its wrapping key is `HKDF(share, salt)`.

**Everything about a slot except its key is readable while the vault is locked** — the device's
name, when it was enrolled, when it last opened the vault. That is deliberate: an interface has to
show which devices can open a vault *before* anything opens it. None of it is secret.

### The property everything rests on

**The node must not be able to unwrap by itself.** If it could, a copy of the machine would be a
copy of the vault and enrolling a device would be theatre.

The trap has a name: deriving the wrapping key from an agreement between the node's private key and
the device's public key. That looks like sound key exchange and collapses the whole design, because
the node holds one half. **The private key in any agreement must be the one the node does not
have** — which is why there is no agreement here at all: the share arrives once, wraps the master
key, and is discarded.

Two tests carry this together, and neither is enough alone: one asserts that a share other than the
enrolled one does not open the slot, and one searches the node's whole state for the share's bytes
after enrollment and finds nothing. No test can enumerate everything the node does not have; what it
can do is prove the share is not among what it has.

### A passphrase change does not disturb a device

The master key does not change, so a rekey replaces exactly one slot. Every enrolled device keeps
working and never hears about it. That is the difference between a keyslot and a key.

### A device may not rotate the passphrase

`sokar vault passphrase` asks for the current one and does not accept a device instead. A share
proves you may *read* the vault; letting it replace the recovery credential would let a stolen
device lock out the person who owns the machine. That is a power the design never claimed, and it is
a refusal rather than an omission.

## Where an unlock is held

`UnlockWithShare` keeps **the share** in the kernel keyring for the time asked — not the unwrapped
master key.

A cached master key would be a secret on the machine that **outlives every revocation**: a slot can
be deleted, but a key somebody already holds opens the vault for good. A share stops working the
moment its keyslot is removed, and it is no more than what the passphrase cache already is: a way
in, held in memory, with a timeout the kernel enforces.

**The daemon can open the vault with a share, and never with a passphrase.** It has no terminal to
ask at, so a passphrase would have to travel to it; a share arrives from a device over the socket and
is worth nothing on its own. That asymmetry is the whole reason the daemon is allowed to open a
vault at all.

## What a storage kind is honestly worth

A device declares how it keeps its share and the node **records the word without being able to check
it**. One machine cannot verify what another does with its own keystore, and saying so is more honest
than implying a guarantee.

| declared | what it actually protects against |
|---|---|
| `USER_SCOPED` | another account, and a stolen file. **Not** against code running as that user right now |
| `APPLICATION_SCOPED` | other applications of that user as well |
| `FIDO2` | same-user code, because releasing it needs a physical touch |
| `TPM2` | same-user code, because releasing it needs a PIN |

**The real split is application-scoped against user-scoped, not Linux against the rest.** On Ubuntu
and Fedora a share lands in a Secret Service keyring that unlocks at login and stays unlocked;
Windows DPAPI is keyed to the logon rather than to the application. iOS, Android and macOS with code
signing are the application-scoped ones.

So **a keyslot is not a hardware story on a desktop**. It is still better than a stored passphrase,
because a share is scoped to one device and revocable where a passphrase is neither — and
`sokar vault devices` prints what each slot is worth in words rather than printing the label:

```
ID                                     KIND         NAME             WHAT IT IS WORTH
passphrase                             passphrase   passphrase       typed by a person, stored nowhere
b0ab092b-2d16-4f3d-8343-6bf8f9821cb4   device       the GUI laptop   any process running as you can ask for it
```

Where a device should be worth more than that, the share must not be stored at all but derived at
unlock time from a token — which is a separate piece of work, and until it exists `USER_SCOPED` is
the honest answer on a desktop.

## On the wire

`Keyslots`, `EnrollDevice`, `RevokeKeyslot` and `UnlockWithShare` on `org.fuin.sokar.Tasks1`, with
the interface description held against the daemon by a test. Two fields are worth knowing about:

- **`self`** is computed per session from the share the machine is currently holding, so it is false
  while the vault is shut. A client that needs to mark "this device" reliably keeps the slot id it
  got from `ENROLLED`.
- **`lastUsed`** is written when a device opens the vault. It is how an operator spots a device that
  has not been near the machine in months and should not still have a way in.
