# B63 — A Share That Is Not Stored At All

**Status:** open, split off from B60 on 2026-09-18 when the keyslots were built. B60 made a device a
way into the vault; this makes a device worth more than the account it runs under.

A keyslot's share is 32 bytes the device keeps in its platform's keystore. On the two platforms most
operators actually use, that keystore is **user-scoped**: a Secret Service keyring that unlocks at
login on Linux, DPAPI keyed to the logon on Windows. **Any process running as that user can ask for
the share.** So on a desktop a keyslot protects against a stolen file and against another account -
and not at all against the thing people picture when they hear "a device unlocks it", which is code
running as them right now.

B60 says this out loud rather than implying otherwise: `sokar vault devices` prints *"any process
running as you can ask for it"* next to a `USER_SCOPED` slot. **This issue is how that sentence gets
to say something better.**

## What must be true

1. **The share is not stored.** It is derived at unlock time and cleared afterwards, so there is
   nothing in any keystore for same-user code to ask for.
2. **Releasing it needs something code cannot supply**: a touch on a FIDO2 token, or a PIN for a
   TPM2 object. That is the whole point; a derivation that happens silently is the stored share with
   extra steps.
3. **The same token produces the same share every time**, on that machine and after a reboot -
   otherwise the keyslot it opened stops opening. What the device stores is the *inputs* (a
   credential id and a salt, or a sealed object's handle), never the output.
4. **Losing the token loses that slot and nothing else.** The passphrase is the recovery credential
   and every other device keeps working, exactly as B60 already promises.
5. **What the node records is what actually protected the unlock**, not what a client claimed. Today
   `storage` is a word the device sends and Sokar writes down; a slot that says `FIDO2` because a
   client typed `FIDO2` is worth no more than a `USER_SCOPED` one and reads as worth more. Either
   the node can tell, or the product must stop presenting the two as different - and saying which
   of those is possible is part of this issue.

## Acceptance

- **A machine with a token enrolls itself.** `sokar vault enroll --fido2` at the machine derives a
  share from the token, wraps the master key with it, and stores nothing but the inputs. This is the
  case that needs no interface and no second device, and it is the one to build first.
- **The unlock fails with the token absent**, and says so in a sentence naming the token rather than
  reporting a rejected share.
- **The unlock fails with the token present and nobody touching it**, within a stated timeout. A
  test that passes because the tester's token happened to be in a touched state proves nothing.
- **The share never reaches the filesystem or a keyring** - asserted the way B60 asserts it, by
  searching the machine's state for its bytes after an unlock.
- **A reboot changes nothing**: the same token opens the same slot.
- **`sokar vault devices` says what a FIDO2 or TPM2 slot is worth**, and stops saying it for a slot
  whose claim the node cannot check, if point 5 lands that way.

## What belongs to whom

**The node side is Sokar's**: deriving from a token at the machine, the `--fido2` and `--tpm2`
enrolments, and whatever verification of `storage` turns out to be possible.

**The device side is the interface's** (`sokar-frontend`): a phone or a laptop that holds a share
for a *remote* machine has its own keystore story, and on iOS and Android it is already
application-scoped. This issue does not tell that side what to do; it should be agreed on the
channel once the node side exists, because the node's answer to point 5 decides what a client may
claim.

## Notes

**Nothing here has been measured.** B60's measurements are of keyslots, not of tokens: no FIDO2
token and no TPM2 device has been near this code. The libraries exist on Linux - `systemd-cryptenroll`
does exactly this for LUKS, and libfido2 and tpm2-tss are packaged - so the shape is known to work;
what is not known is how it behaves in this codebase, whether a native image can call those
libraries without carrying half of them, and what a token that is absent looks like from Java. The
first commit against this issue should be a measurement, not a design.

**Why this is not part of B60.** B60 is about where the master key lives and how many credentials
may unwrap it, and it is complete without a single token. This is about what one credential is worth,
which is a different question with different failure modes - and bundling them would have meant
neither shipping until a token was on somebody's desk.
