# B60 — Keyslots: A Device Unlocks Without The Passphrase

**Status:** decided on 2026-09-18 by the operator, and not built. It is option **E** of
[the secrets-from-elsewhere design](Secrets-From-Elsewhere_design.md), which weighs it against four
alternatives; this file is what must be true if it is built, and the design is why it was chosen.

**It dissolves B18's question rather than answering it.** That requirement is parked on whether a
credential may be entered anywhere but at the node, because the passphrase would then travel. A
keyslot means **no secret travels at all**: a device sends a share that is worth nothing without the
node's own wrapped blob, and the node holds a blob that is worth nothing without the share.

Adapted from what LUKS does with its keyslots.

## What must be true

1. **The vault's master key is stored only in wrapped form, once per credential allowed to open
   it.** No credential is the master key, and no credential can be derived into it alone.
2. **Keyslot 0 is the passphrase, and becomes a recovery credential** - typed by a person in an
   emergency, stored nowhere, and not what day-to-day unlocking uses.
3. **One keyslot per device.** At enrollment the client generates a random 32-byte share, keeps it
   in that platform's keystore, and sends it **once**. The node derives `KEK = HKDF(share)`, stores
   `Enc(KEK, masterKey)`, and **discards the share**.
4. **Unlocking releases the share, not a secret.** The node unwraps the master key for that session
   and persists neither the share nor the key.
5. **Revoking a device is deleting one wrapped blob**, and nothing else has to change: no re-keying,
   no other device disturbed, no passphrase rotated.
6. **The node must not be able to open the vault by itself.** Deriving the wrapping key from an
   agreement between the node's private key and the device's public key would let it, which collapses
   the whole design - *the private key in any agreement must be the one the node does not have.*
   A test has to fail if the node can unwrap with only what it holds.
7. **The vault file's format carries keyslots**, as version 2, and a reader understands version 1 as
   it stands today. The format is the part to do first: it is cheap while nothing is stored that
   somebody cannot retype, and expensive afterwards.

## Acceptance

- A device is enrolled, and the vault opens on that device with nothing typed. The share is in the
  platform's keystore and the node's copy of it is gone - asserted by searching the node's state for
  the share's bytes after enrollment.
- The passphrase still opens the vault, from a terminal, with no device present.
- A device is revoked: its blob is deleted, that device can no longer open the vault, and every other
  device and the passphrase still can.
- **The node alone cannot unwrap.** With the vault file and everything the node stores, and no share,
  the master key does not come out. Proven against the built code, not argued from the design.
- A vault written by version 1 is read, and a vault written by version 2 is refused by a reader that
  only knows version 1 rather than being half read.
- What a device holds is scoped to Sokar where the platform allows it, and where it does not, the
  product says so rather than implying otherwise.
- **A device declares how it stores its share** - user-scoped, application-scoped, FIDO2 or TPM2 -
  and the node records it with the keyslot, so a list of devices can say what each one is worth
  without the interface guessing from the operating system's name.

## What it is honestly worth per platform

**This is not a hardware story on a desktop**, and the requirement says so rather than letting the
comparison table imply it. On Ubuntu and Fedora the share lands in a Secret Service keyring that
unlocks at login and stays unlocked, with no hardware backing and no application scoping: any process
running as that user can ask for it. Windows sits closer to that than it looks - DPAPI is keyed to the
user's logon rather than to the application. The real split is **application-scoped** (iOS, Android,
macOS with code signing) against **user-scoped** (Windows, Linux), not Linux against the rest.

It is still better than a stored passphrase, because a share is scoped to one device and revocable
where a passphrase is neither. **Where a device is worth more than that**, the share should not be
stored at all: derived at unlock time from a FIDO2 token's `hmac-secret`, or from a TPM2 object sealed
behind a PIN, so that releasing it needs a physical touch or a PIN which same-user code cannot supply.
That is available on Linux today.

## The contract, proposed rather than decided

Written down because the interface is building four screens against it (`sokar-frontend` F38 to F41)
and only the last mapping onto the wire should change when it is built. **Nothing of this is on
`Tasks1` yet.**

```
type Keyslot (
  # Assigned by the node and kept by the device beside its share. Not derived from the share:
  # the node discards that at enrollment and could not derive anything from it later.
  id: string,
  # What a person called this device. Shown in a list; never an identifier.
  name: string,
  # USER_SCOPED, APPLICATION_SCOPED, FIDO2 or TPM2, as the device declared it.
  storage: string,
  enrolled: string,
  # "" when it has not been used since it was enrolled.
  lastUsed: string,
  # True only for the slot this session unlocked with, so a list can mark "this device".
  self: bool,
  # True for the passphrase, which is keyslot 0, is recovery only, and is not a device.
  recovery: bool
)

# The share arrives once, wraps the master key, and is discarded. It is never logged, never
# echoed back and never written anywhere.
method EnrollDevice(name: string, share: string, storage: string) -> (
  # ENROLLED, ALREADY_ENROLLED, UNKNOWN_STORAGE, BAD_SHARE, VAULT_LOCKED,
  # VAULT_WITHOUT_KEYSLOTS or FAILED.
  outcome: string, slot: ?Keyslot, detail: string)

method Keyslots() -> (slots: []Keyslot)

method RevokeKeyslot(id: string) -> (
  # REVOKED, NO_SUCH_SLOT, LAST_WAY_IN, VAULT_LOCKED or FAILED.
  outcome: string,
  # What can still open the vault afterwards, so a screen can say it without asking again.
  remaining: []Keyslot, detail: string)

method UnlockWithShare(share: string, slot: ?string, minutes: ?int) -> (
  # UNLOCKED, SHARE_REJECTED, ALREADY_OPEN, VAULT_WITHOUT_KEYSLOTS or FAILED.
  outcome: string, until: string, slot: ?Keyslot, detail: string)
```

Four properties of that shape are deliberate:

- **The share is base64 of 32 random bytes and appears in no reply, no log line and no error
  message.** The only thing that comes back is which slot it opened.
- **`slot` is optional at unlock.** The node tries each wrapped blob until one authenticates, which
  costs a key derivation per slot, so a device that lost its id still works and nothing has to be
  recovered. Sending the id only saves that.
- **"Already enrolled" is decided without storing anything**: the share the node was just given
  derives a wrapping key, and if an existing blob unwraps with it, that device is already a slot.
- **An unknown storage class is refused rather than recorded.** A list that cannot say what a device
  is worth is worse than one that refuses to show it.

## To be checked

- **Whether enrollment needs a second channel.** The first share arrives from a device the node has
  never seen; what makes that device the operator's rather than somebody else's is not decided here,
  and "the ssh session it arrived over" may be the whole answer.
- **What happens when the last device is lost** and the passphrase is the only keyslot left, against
  what happens when the passphrase is also forgotten. One of those is recoverable and one is not, and
  the product should say which before somebody finds out.
- **Whether the passphrase keyslot may ever be removed.** Keeping it is a permanent recovery path and
  a permanent target; removing it makes the device set the only way in.
- **How this meets B32** ([index](README.md)), which is about a cached passphrase saying what it is:
  a keyslot changes what is cached and therefore what that requirement is describing.
