# F11 — Live Log Viewing

**Status:** open

Everything a piece of work produces, readable inside the interface, while it is
producing it.

## Acceptance

- Logs are viewed in the interface. No action requires dropping to a separate tool or
  window to read output.
- Output is rendered so that structure is visible — what the agent said, what it did,
  what came back — rather than as an undifferentiated stream.
- The view can follow new output as it arrives, and following can be suspended to read
  back without losing the live position.
- The log of finished work is readable after it ends.
- Colour and formatting are legible under whatever appearance the person has chosen.

## Notes

The point of pulling this inside is that the alternative — a separate window per piece of work
— does not survive five concurrent runs. The daemon's `Tail` call streams a log as it is
written, capped per reply so one enormous log cannot become one enormous message.
