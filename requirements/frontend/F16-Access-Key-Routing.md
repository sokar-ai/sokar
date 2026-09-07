# F16 — Access Key Routing

**Status:** open

Which access keys reach which projects. The relationship is many-to-many and it is
what determines whether work can reach a repository at all, so it has to be legible at
a glance rather than reconstructed project by project.

## Acceptance

- Keys and projects are shown together, so which keys reach a given project and which
  projects a given key reaches are both answerable from one view.
- A link between a key and a project can be made and unmade directly in that view.
- New keys can be created and existing keys removed from the same place, with removal
  naming every project that would lose access.
- The view remains usable on a small window, falling back to a simpler arrangement
  rather than becoming unreadable.
- No key's secret half is ever displayed.

## Notes

The grid is the requirement, not a presentation preference. A per-project list hides
exactly the mistake people make here: one key wired to more projects than intended.
