# package-check - `sokar-dist-package-check`

Checks this repository's `.deb` and `.rpm` against each other and against a real install. It builds no package itself.

It runs after the packages are built, from the root:

    ./mvnw -B -q -s settings.xml -pl dist/package-check compile exec:java@package-check

The places it reads - each package's `target/`, the stub agent's and the native binary - are constants in `Main`. They
live in this tree so that a regrouping of the modules changes them in the same commit; `LayoutTest` fails the build when
one no longer names a module. Not published.
