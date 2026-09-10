# B41 — What a Packaged Agent Installs

**Status:** open. Written by the agent-repositories agent on 2026-09-10 and placed here because
the change is Sokar's: `--supply-chain` is the CLI's output, the bill is what a package already
installs, and nothing in it belongs to one agent.

`sokar agents --supply-chain` answers well for an agent that fetches its tool and says almost
nothing for one whose package carries it. The gap is narrower than it looks and was measured
rather than assumed, on the fedora VM: a packaged agent **does** report its version -
`pi ... installs: 0.85.0` - because that comes from the definition. What is empty is the artifact
list, correctly, because nothing is fetched. So this is about *what the package carries*, not
about the version.

The answer already exists on the machine. Every agent package installs a CycloneDX bill at
`/usr/share/sokar/sbom/<package>.cdx.json`, and as of 2026-09-10 all three describe their payload
truthfully: `sokar-pi` covers its Node tree through the Maven-plus-npm graph, and
`sokar-claude-code` and `sokar-omp` record the CLI their image fetches, with the publisher's
SHA-256 and `sokar:delivery=fetched-at-image-build`.

## Acceptance

- For an agent whose package carries its payload, `--supply-chain` names what that package ships,
  read from the bill the package installed.
- A component the image fetches rather than ships is shown as such - the bill already carries the
  property, so this is reading it rather than inventing it.
- A missing or unreadable bill says so, and is distinguishable from an agent that ships nothing.
  The existing output already draws that line with `UNVERIFIED` and a reason; this keeps it.
- Nothing added here names an agent. The bill's path follows from the package, so it stays data.

## To be checked

- **Who reads the file.** A remote client has no filesystem, and the daemon hands out paths rather
  than taking them on trust. Whether the CLI reads the bill locally or the daemon reports its
  contents is the same question that shaped `TaskInventory`, and it should be answered the same
  way rather than freshly.
- Whether an operator wants the whole component list or only what a person would act on. A Node
  tree is 162 entries; `sokar agents --supply-chain` printing 162 lines per agent would be a
  listing nobody reads.
