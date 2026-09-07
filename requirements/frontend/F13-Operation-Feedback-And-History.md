# F13 — Operation Feedback And History

**Status:** open

Long operations — builds, synchronisations, setup, credential work — run for minutes
and produce a lot of output. The interface has to stay usable while they do, and the
output has to survive being scrolled past.

## Acceptance

- Starting a long operation never freezes the interface; other work stays visible and
  other actions stay available.
- Output from an operation never disturbs the rest of the display.
- Every operation started in a session is listed afterwards, with its outcome, and its
  full output can be reopened.
- An operation still running can be watched live and left again without stopping it.
- Failure is reported as failure, in the same place success would have been reported,
  with the output that explains it one step away.

## Notes

The session record is what makes an unattended machine reviewable: somebody comes back
after an hour and needs to know what happened, in order, without having watched.
