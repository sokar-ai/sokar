# F20 — Access From Elsewhere

**Status:** open

Reaching the interface from a device that is not sitting in front of the machine,
without weakening the machine.

## Acceptance

- The interface can be reached from a browser on another device.
- Reaching it requires authentication; an unauthenticated request reaches nothing,
  including any live-updating connection.
- The credential for that access is set by the person, persists across restarts, and
  is shown exactly once when it is first generated.
- The remote view offers the same actions as the local one, or names precisely what it
  does not offer.
- Remote access is off unless deliberately started, and while it is on the interface
  says so.

## Notes

Related: [Remote Access](../base/B06-Remote-Access.md), which is the transport this rests on and
stays a central requirement because it constrains the daemon rather than the interface. What a
phone can do was folded in here.

## To be checked

Whether the actions that hand off to a local session — attaching interactively, opening
an editor, taking changes out to the clipboard — can be honoured remotely at all, or
whether the remote view must declare them unavailable. This changes what the acceptance
criterion above can promise.
