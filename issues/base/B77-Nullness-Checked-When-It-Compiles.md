# B77 — Nullness Checked When It Compiles

**Status:** open, written 2026-09-28 at the operator's word, relayed by Agent Coordinator,
**high priority**. Every Java repository opens the same issue; this is Sokar's.

## What is there

Measured on 2026-09-28 at `b5cf68b`, with `buildtools/package-check` added in the working tree:

- **26 packages hold main code; 23 are `@NullMarked`** in their `package-info.java`. The other three
  have **no `package-info.java` at all**, so nothing in them is marked:
  `acceptance/kit` (`org.fuin.sokar.acceptance`, 12 files - and published to Central),
  `buildtools/hetzner` (`org.fuin.sokar.machines`, 16 files) and
  `core` (`org.fuin.sokar.core.credential`, 3 files).
- **87 tracked Java files use `@Nullable`.** JSpecify is a compile dependency of every module, from
  the root POM.
- **Nothing checks any of it.** Neither the root POM nor the parent, `org.fuin:pom:2.0.2`, configures
  Error Prone or NullAway (Agent Coordinator, from the POMs and the parent in `~/.m2`). A method
  marked as never returning null that returns null compiles, and so does a caller that dereferences
  a `@Nullable` without looking.

So the annotations are documentation that reads as a guarantee. `AGENTS.md`'s rule for a guard
applies to them as it does to a test: one that has never failed is one nobody has checked.

## What must be true

**The build fails on a nullness error in any main code of this repository**, and every package that
holds main code is `@NullMarked`, so there is no code the check silently skips.

## Acceptance

- Error Prone with NullAway runs in the ordinary build - `./mvnw test`, and so in CI - not only in a
  profile somebody has to remember.
- NullAway treats `@NullMarked` code as annotated (JSpecify mode), and the three packages above gain a
  `package-info.java` that marks them.
- **Proven to fail:** a `@Nullable` value dereferenced without a check, and a `null` returned from a
  method that does not declare it, each turn the build red naming the file and line; both put back,
  it is green.
- Whatever is suppressed says why, beside the suppression, and the suppressions are counted in this
  file when it is built - a suppression nobody counts is a check switched off in silence.
- The native builds are unaffected: Error Prone is a compiler plugin, and a `-Pnative` build still
  produces images that pass `cpu-check` and `ffm-check`.

## Measured in the agent repositories

Agent Smith's, 2026-09-28T05:05Z, from `sokar-claude-code` `537abf5` (and `sokar-pi`, `sokar-omp`) -
his measurements, not yet repeated here:

- Error Prone 2.50.0 and NullAway 0.14.2 in the `default-compile` execution only, with
  `-XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true`, so `@NullMarked`
  is the scope and no package list is kept.
- **On JDK 25 it needs `.mvn/jvm.config`** - ten `--add-exports`/`--add-opens` for `jdk.compiler` -
  or the compile dies with `IllegalAccessError` before checking a file.
- **Declaring `annotationProcessorPaths` turns off discovery of processors on the classpath**, so any
  processor this build relies on has to be listed there or it silently stops. Not yet checked here.
- Neither tool reaches the CycloneDX bill; the native builds were unaffected.

## To be checked

1. **Whether test code is checked too, or main code only.** Test code is where `null` is passed on
   purpose to test a refusal; checking it may cost more suppressions than it finds defects.
2. **Where it is configured - the root POM, or the fuinorg parent** so every Java repository gets the
   same configuration from one place. The parent is not this repository's to change; Agent Smith's
   `sokar-claude-code` pom is the worked example once he has committed it (CC20), and he is to be
   asked rather than his working tree read.
