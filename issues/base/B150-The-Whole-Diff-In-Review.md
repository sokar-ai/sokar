# B150 — The Whole Diff In Review

**Status:** open.

**What must be true.** A review shows the whole diff, or says plainly that and where it was cut.

## Why

A person approves what they saw; a cut diff that does not say so lets unseen changes pass.

**Guideline points it answers:** AISVS 9.2.2. From a review of security guidelines, 2026-10-09.

## The shape

Checked 2026-10-09: `sokar gate review` and the daemon's review print the whole diff; nothing cuts it. What is open is the wire: whether a diff larger than a message may be is cut, refused or sent whole, and that an interface says it when it shows less.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- The message size limit of the socket for a review.
