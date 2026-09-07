# F01 — Application Shell

**Status:** open

One window and one mental model: the projects on the machine, and the work running
under whichever project is selected. Everything else opens over that frame rather
than navigating away from it, so a person never loses their place to answer a
question about something else.

## Acceptance

- The opening view shows which projects exist and which of them have work running,
  before any selection is made.
- Selecting a project shows its state and its work; selecting a piece of work shows
  that work's detail. Neither costs more than one selection.
- Every action is reachable from the keyboard alone. A pointer is optional
  everywhere, never required.
- One command finder lists every action in the product by name, including actions
  that belong to screens not currently open, so nothing is discoverable only by
  remembering where it lives.
- Closing a detail view returns to the view it opened over, with the same selection
  still made.
- A status line states the outcome of the last action in words, and stays readable
  while a further action runs.
- Appearance is a user choice and it survives a restart.
- The interface stays usable at the smallest window a person actually works in;
  dense views fall back to a simpler arrangement rather than becoming unreachable.

## Notes

Every other requirement in this folder assumes this frame. If moving between the
overview and a detail is expensive, people stop looking, and the state of the
machine stops being known.
