# F14 — Authentication Flows

**Status:** open

Getting agents and providers authenticated, from the interface, both for the machine
as a whole and for a single project.

## Acceptance

- Authentication can be started without a project selected, for the machine as a
  whole, and from within a project for that project.
- The available providers are listed with what each one is, rather than as bare
  identifiers.
- Where a provider supports more than one way of authenticating, the choice is
  presented with the consequence of each.
- Where a credential must be typed, it is never displayed as it is typed, never shown
  back afterwards, and never appears in any log or output.
- Existing configuration held elsewhere on the machine can be imported rather than
  retyped.
- The interface states which providers are currently authenticated and which are not.

## Notes

Related: [Credential Management](../base/B03-Credential-Management.md), which governs how
values are stored. This file covers only the flows a person walks through.
