# F21 — Continuity And Updates

**Status:** open

The interface is not where the work lives. Closing it, losing a connection, or
updating it must not disturb anything that is running.

## Acceptance

- Closing the interface never stops running work, and reopening it shows that work as
  it stands.
- Where the environment allows work and sessions to outlive the window, they do, and
  reopening reconnects to them rather than starting again.
- Only one instance is in charge at a time; a second launch joins the existing one
  instead of failing or competing with it.
- Quitting asks for confirmation and states what will keep running afterwards.
- When a newer version becomes available while the interface is open, it says so and
  offers to restart into it; declining leaves everything as it was.
- A restart of the interface, requested or otherwise, returns to the same selection.

## Notes

The measure of this requirement is whether a person hesitates before closing the
window. If they do, it has failed.
