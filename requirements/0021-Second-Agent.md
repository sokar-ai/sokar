# 0021 — Second Agent

**Status:** open

The architecture claims an agent is a package rather than a patch. One agent does
not demonstrate that; two do.

## Acceptance

- The second agent ships as its own binary and its own package.
- Nothing outside the agent directory names it, enforced by the build.
- Installing it makes it usable without rebuilding or reinstalling anything else.
- Its credential kinds and destinations are declared, not coded.

## Notes

The enforcement already exists and already fails the build when violated. This
requirement is about exercising it.
