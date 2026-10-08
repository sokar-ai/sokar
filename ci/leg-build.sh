#!/bin/sh
# How this tree is built on a rented machine: for its acceptance suite (acceptance/legs) and, by the machine images'
# build in sokar-buildtools, to prove an image can build it. The modules the suite installs and what they need.
#
# Run from the tree's root with the pinned GraalVM on the PATH. The one argument, if given, is the run number the
# snapshot version is stamped with. The module list is checked against the reactor by LegBuildScriptTest in
# acceptance/legs on every build, so a regrouping that breaks it fails here and not on a rented machine.
set -eu
MODULES=apps/app,daemon,hooks,agents/stub,builds/stub
# sokar's settings.xml, where the build tooling's snapshots come from; the machine has none of its own.
exec ./mvnw -B -s settings.xml -Pnative -DskipTests package ${1:+-Dsokar.snapshot.run=$1} -pl "$MODULES" -am
