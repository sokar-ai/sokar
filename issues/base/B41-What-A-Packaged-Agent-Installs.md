# B41 — What a Packaged Agent Installs

**Status:** later.

**What must be true.** An operator asking `sokar agents --supply-chain` about a packaged agent is
told what that agent's package ships, read from the bill that package installed.

## Why

The change is Sokar's: `--supply-chain` is the CLI's output, the bill is what a package already
installs, and nothing in it belongs to one agent.

`sokar agents --supply-chain` answers well for an agent that fetches its tool and says almost
nothing for one whose package carries it. The gap is narrower than it looks and was measured
rather than assumed, on the fedora VM: a packaged agent **does** report its version -
`pi ... installs: 0.85.0` - because that comes from the definition. What is empty is the artifact
list, correctly, because nothing is fetched. So this is about *what the package carries*, not
about the version.

The answer already exists on the machine. Every agent package installs a CycloneDX bill at
`/usr/share/sokar/sbom/<package>.cdx.json`, and all three describe their payload
truthfully: `sokar-pi` covers its Node tree through the Maven-plus-npm graph, and
`sokar-claude-code` and `sokar-omp` record the CLI their image fetches, with the publisher's
SHA-256 and `sokar:delivery=fetched-at-image-build`.

## Acceptance

- For an agent whose package carries its payload, `--supply-chain` names what that package ships,
  read from the bill the package installed. Seen to fail: for `sokar-pi`, the output lists no
  component that its bill under `/usr/share/sokar/sbom/` holds.
- A component the image fetches rather than ships is shown as such - the bill already carries the
  property, so this is reading it rather than inventing it. Seen to fail: a component carrying
  `sokar:delivery=fetched-at-image-build` is shown as shipped.
- A missing or unreadable bill says so, and is distinguishable from an agent that ships nothing.
  The existing output already draws that line with `UNVERIFIED` and a reason; this keeps it. Seen
  to fail: with the bill removed or unreadable, the output is an empty list rather than
  `UNVERIFIED` with a reason.
- Nothing added here names an agent. The bill's path follows from the package, so it stays data.
  Seen to fail: an agent's name appears in the code added for this.

## To be checked

- **Who reads the file.** A remote client has no filesystem, and the daemon hands out paths rather
  than taking them on trust. Whether the CLI reads the bill locally or the daemon reports its
  contents is the same question that shaped `TaskInventory`, and it should be answered the same
  way rather than freshly.

  **What that precedent says**, recorded here so it is not argued from scratch again:
  `TaskInventory` exists because *"the CLI renders this and the daemon serializes it, so that
  'what tasks are there' is answered in one place. Two implementations of the same question are
  how a feature comes to exist in one and not the other, and how they come to disagree about
  something an operator is reading to decide what to stop."* Every clause holds here with the noun
  swapped, and it weighs more: a supply-chain answer exists to be trusted, so two implementations
  disagreeing about what an agent ships is worse than two disagreeing about a task list. Following
  it means the daemon reads the bill and returns components as data, the CLI renders them, and
  `UNVERIFIED` with a reason stays the answer for a missing or unreadable file - which a remote
  client has to be told rather than infer from an empty list. Written down as the precedent rather
  than as the decision; taking it is still this requirement's to do.
- Whether an operator wants the whole component list or only what a person would act on. A Node
  tree is 162 entries; `sokar agents --supply-chain` printing 162 lines per agent would be a
  listing nobody reads. **This one narrows once the first is settled:** if the daemon
  returns components as data, how many to print is the renderer's decision and may differ between
  the CLI and an interface without either being wrong.

**Guideline points, 2026-10-09:** the operator's review of security guidelines counts this as its item F9. The
points named for F7-F9 and F20 together are OpenSSF 2, Checkmarx, NCSC supply chain and AISVS 9.2.8.

