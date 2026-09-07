# F05 — Project Configuration

**Status:** open

The settings a project carries that change what its work can do: which agents are
available inside it, what hardware it may reach, and which destinations it may talk
to.

## Acceptance

- The agent roster for a project is editable in the interface, and the choice between
  "everything, including agents added later" and an explicit frozen list is explicit,
  because they are different commitments.
- Selecting or clearing any individual item makes it plain that the selection is now
  an explicit list rather than an open-ended one.
- Hardware access is selectable from what the machine actually has, and the interface
  is usable before that detection finishes.
- The curated sets of destinations a project may reach are selectable, with the same
  "all, including future additions" versus explicit-list distinction.
- Any change states what it will affect and when it takes effect — immediately, or at
  the next rebuild.
- Deleting a project requires a confirmation that names what will be destroyed.

## Notes

Related: [Egress Sets Editor](../base/B04-Egress-Sets-Editor.md) covers editing the
destination sets themselves; this covers choosing which of them a project uses.
