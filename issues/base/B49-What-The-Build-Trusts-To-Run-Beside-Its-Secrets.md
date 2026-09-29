# B49 — What The Build Trusts To Run Beside Its Secrets

**Status:** implemented in this repository 2026-09-29; open until Dependabot's first pull request has been
reviewed and landed, and until each agent repository and the interface have done the same. Written
2026-09-12 at the operator's instruction, after the same finding came
back as the one high-severity item from reviews of the agent repositories. It is not one
repository's problem: every repository in this product has the shape described below.

## What happens today

Every workflow step that is not ours is fetched by a **mutable tag**:

    actions/checkout@v7          graalvm/setup-graalvm@v1
    actions/upload-artifact@v7   actions/download-artifact@v8
    jfrog/setup-jfrog-cli@v5     crazy-max/ghaction-import-gpg@v7

A tag is a name its owner may point anywhere. Fetching `@v7` is a promise to run whatever that name
refers to at the moment the job starts - a new release, a retargeted tag, or the work of whoever
took the account over since the last build.

**What is standing next to those steps is the whole of this product's credentials.** In sokar
alone: `HETZNER_API` and `HETZNER_SSH`, which rent machines and log into them;
`JF_ACCESS_TOKEN`, which publishes packages people install; `OSS_SONATYPE_TOKEN` and
`OSS_SONATYPE_USERNAME`, which publish to Central; and `OSS_SONATYPE_GPG_PRIVATE_KEY` with its
passphrase - **a signing key, handed deliberately to a third-party action**. The agent
repositories add a token that can open and merge a pull request in the update workflow.

Nothing about this is hypothetical machinery: it is the ordinary way these workflows are written,
and it is why this finding is the same in every repository.

## What must be true

1. **Every third-party action is pinned to a commit, not a tag.** A commit names one tree; a tag
   names whatever it is pointed at. Our own reusable workflows and actions, if any, are the only
   exception, and only because their history is ours.
2. **The tag stays visible.** A bare forty-character hash is unreadable and unreviewable, so each
   pin carries the human version beside it as a comment. A pin nobody can read is a pin nobody
   updates.
3. **Something keeps them current, and it is named.** This is the half that decides whether the
   requirement helps: **a pin without an update process rots, silently, and trades a live risk for
   a stale one.** A build that runs a two-year-old action with a known flaw is not safer than one
   that ran the current tag. Whatever does it - Dependabot, a scheduled job, a person with a
   calendar - has to be written down here and be something that produces a review rather than an
   automatic merge.
4. **It holds in every repository**, because the credentials are distributed across them and the
   weakest workflow is the one that decides. The agent repositories, the interface and this one are
   one surface.
5. **A new workflow cannot quietly reintroduce a tag.** Whatever enforces this is part of the
   build rather than a habit: a check that fails on an unpinned third-party `uses:`.

## Acceptance criteria

- `grep` over every workflow in every repository finds no third-party `uses:` naming a tag or a
  branch.
- Each pin carries the readable version beside it.
- Adding a step that names a tag fails a build, and the failure says what to do about it.
- The update process exists, has run at least once, and its result was reviewed by a person before
  it landed.
- The most privileged workflow - the one holding the signing key - is checked by hand after the
  first pinning, because that is the one where being wrong costs the most.
- **Nothing else is fetched by a name either.** `grep` for a `curl` or a `wget` in any job that
  also holds a publishing credential, and pin whatever it finds by digest. An action is not the
  only thing a workflow runs: a release binary downloaded into a publishing job has the same
  trust and none of the ceremony, and the worst case is the one that writes the packages
  themselves. Sokar is believed to fetch nothing this way and that has not been proved, which is
  exactly why it is a criterion rather than a note.

## Decided 2026-09-29, by the operator

- **Dependabot keeps the pins current, in every repository.** `.github/dependabot.yml`, the
  `github-actions` ecosystem, weekly, one grouped pull request, a three-day cooldown (the same release age
  as every other pin), never merged automatically.
- **The signing key goes to no third-party action.** `ghaction-import-gpg` is replaced by our own step:
  `gpg --batch --import` from the secret. The passphrase is not needed there; Maven's signing passes it
  with loopback pinentry from `settings.xml`.
- **GitHub's own actions are held to the same rule.** `actions/checkout`, the artifact pair and
  `actions/cache` are pinned like any other; the check has no exception for them.
- **The runner image is out of scope**, deliberately. `ubuntu-24.04` instead of `ubuntu-latest` would
  still change weekly and would add a chore; this is recorded so it is not mistaken for an oversight.

## How it is built here

- Every `uses:` names a commit with its version beside it: `actions/checkout@3d3c42e… # v7.0.1`,
  `actions/upload-artifact@043fb46… # v7.0.1`, `actions/download-artifact@3e5f45b… # v8.0.1`,
  `jfrog/setup-jfrog-cli@68c0efd… # v5.2.0`, `actions/cache@55cc834… # v6.1.0`. Each commit was read from
  the release tag it stands for.
- **The check is `sokar-release check-actions [DIRECTORY]`**, default `.github`. It refuses a tag, a
  branch, a commit without its version, and a container image without its digest, and says how to pin
  each. Only a step in the repository itself (`./...`) is exempt. Here a unit test runs it over this
  repository's `.github`, so a new step that names a tag fails `Build and unit tests`. An agent
  repository runs the same command from its own workflow.
- **What else is fetched by a name**, in jobs that hold a credential:
  - Maven itself, which `mvnw` downloads in every job, `Publish` included: now pinned by
    `distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties`. The SHA-256 was taken from a
    download whose SHA-512 matched the one Maven Central publishes.
  - GraalVM (`pinned-jdk`, and on the snapshots): already checked against its pinned SHA-256.
  - The JFrog CLI, which `setup-jfrog-cli` downloads into `Publish`: the version (2.124.0) is fixed by
    the action's pinned commit, but it is downloaded without a checksum. That is what remains here.
  - `Publish` reads Artifactory's package indexes with `curl`, and runs nothing it reads.

## Still open

- Dependabot's first pull request, reviewed by a person before it lands.
- The agent repositories and the interface, each for itself: pin, add the same `dependabot.yml`, and run
  `check-actions` in their build. Theirs to do; the tool is published with `sokar-release`.
- The JFrog CLI download in `Publish`, pinned by version only.
- The hand check of the most privileged workflow (`Publish`, which holds the signing key) after this
  first pinning.

## To be checked

- **What updates them.** Dependabot understands action pins and raises pull requests with the new
  hash and the release notes, which is the cheapest answer and needs the update pull request to be
  reviewed rather than auto-merged. The alternative is a scheduled job that opens the same request
  from our own tooling, which is more work and fewer moving parts. Decide once, for all
  repositories.
- **Whether the signing step should use a third-party action at all.** `ghaction-import-gpg` is
  handed the private key and its passphrase. Pinning it makes what runs predictable; it does not
  make it ours. Importing a key is a short shell step, and the question is whether the convenience
  is worth the trust - which is a different decision from pinning and should not be folded into it.
- **What to do about actions published by GitHub itself** (`actions/checkout` and the artifact
  pair). The same argument applies and the same fix works; whether they are held to the same rule
  or treated as part of the platform is a decision somebody should make deliberately rather than by
  omission.
- **Whether the runner image is in scope.** `runs-on` names a moving target too, and the same
  reasoning would lead to pinning it. That is a bigger change with a maintenance cost of its own,
  and it is not what this requirement asks for - but it is the same question and is recorded here
  so it is not mistaken for an oversight.
