# B45 — One Documentation Somebody Can Find Their Way Through

**Status:** open. Written on 2026-09-11 after a question nobody could answer from the
documentation: *is there a full example of a project file?* There was not - eleven keys were
spread over five guides and one of them was described nowhere - and finding that out took reading
the reader's source.

## What is there now

Fifteen files, 3615 lines, no index, and no order except the one a link happens to impose.

    663  getting-started-fedora.md      348  way-of-working.md      113  build.md
    652  getting-started-debian.md      340  sokar-for-dummies.md    81  your-tooling.md
    281  glossary.md                    200  authentication.md       69  security-classes.md
    273  README.md                      195  commands.md             16  why.md
    139  faq.md                         132  cheat-sheet.md         113  project-file.md

**The two getting-started guides are the same document twice.** Normalise the distribution's name
and its package manager and 113 lines of about 660 differ; 14 of their 15 headings are identical.
A third of all the documentation is one text, maintained twice by hand, and every correction has to
be made in both or the two drift.

**Nothing says where to start or what is next.** There is no index. `README.md` links some of it;
some pages link each other; `why.md` is sixteen lines nothing points at.

**The same subject is explained in several places at once**, which is how `package_sources` came to
be documented nowhere: there was no one place it obviously belonged, so it went in none.

## What must be true

1. **One place per subject.** A reader looking for what a project file can hold, or what a security
   class does, finds exactly one page - and somebody adding a key knows where it goes without
   deciding.
2. **What differs between distributions differs, and the rest is written once.** The Debian and
   Fedora guides share a text and differ where they actually differ.
3. **There is an order.** A reader is told where to start, what comes next, and what is reference
   rather than narrative.
4. **It is published**, so somebody can read it without cloning the repository or browsing raw
   Markdown on a forge.
5. **Nothing on the published site is stale by construction.** Examples that can be executed are
   executed - `DocumentedProjectFileTest` parses the project file page's own YAML with the reader
   it documents, and that is the shape the rest should follow where it can.

## The published site

**mkdocs with the ReadTheDocs theme** (<https://www.mkdocs.org/user-guide/choosing-your-theme/>),
built by a GitHub Action and published to GitHub Pages.

Chosen over the alternatives for what it does not cost: the sources stay Markdown in this
repository, readable and reviewable as they are now, and nothing about writing a page changes.
The theme brings the navigation and the search the current documentation has no way to offer.

**The action is the part to get right, not the theme.** It runs on a push to main, builds, and
publishes; a build that fails publishes nothing, as the package job now does. Whether it runs in
the same workflow or its own is an open question below - the existing one already carries two
publishing steps that must not come apart.

## Acceptance criteria

- Every page is reachable from one navigation, and no subject has two pages.
- The Debian and Fedora instructions share their common text; what is distribution-specific is
  visibly distribution-specific.
- `mkdocs build --strict` passes, so a dead internal link fails rather than ships.
- A push to main publishes the site; a failing build publishes nothing.
- A reader arriving at the site's front page is told where to start.
- The project file page's example still parses with the reader, and any other executable example
  is executed the same way.

## To be checked

1. **Its own workflow or the existing one?** The build workflow already publishes jars and packages
   and must not split; adding a third thing to it makes that harder to reason about. A separate
   workflow costs a second file and a second place to look when something is not published.
2. **What happens to `sokar-for-dummies.md` and `why.md`?** One is a narrative introduction, the
   other sixteen lines nothing links to. Both may be the front page, or one of them may be what the
   front page is made from - that is a decision about voice, not structure.
3. **Does the glossary survive as a page?** 281 lines of definitions may be better as links from
   where each term is first used, and a glossary nobody arrives at is a page that ages quietly.
4. **Does the frontend's documentation join this site**, or keep its own? It is a separate
   repository with its own requirements, and a reader does not care which repository a page is in.
