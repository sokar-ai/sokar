# F10 — Task Inspection And Work Handover

**Status:** open

What a piece of work is, what it has done, and how a person gets its output out of
the interface and into whatever they use next.

## Acceptance

- Detail for the selected work shows at minimum: its project, its agent, its mode, the
  security posture it runs under, its current state and how long it has been in it.
- The effect on the repository is visible: what branch it works on, what it has
  changed, and whether anything is waiting for review.
- The changes it has made can be taken out of the interface in one action, against
  either the starting point or the previous state, and the interface says how much was
  taken or that there was nothing to take.
- Detail stays readable as the window is resized; nothing is truncated to the point of
  being wrong.
- Work that has ended remains inspectable long enough to establish why it ended.

## Notes

This absorbed two central requirements - what a run did to the repository, and reviewing and
approving what it produced. The handover is deliberately low-ceremony: the common case is a
person wanting the diff somewhere else, immediately. The gate itself is the domain's, and
approving is still the only action that sends anything anywhere.
