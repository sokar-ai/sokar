#!/usr/bin/env bash
#
# Regenerates the FFM downcall registrations by running each module's tests under the
# native-image tracing agent, and fails if the result differs from what is committed.
#
# FFM downcalls are not discovered by native-image's static analysis. An unregistered one is not a
# build error - it is a MissingForeignRegistrationError at runtime, in the shipped binary. This
# check is what keeps that from reaching a user, so it belongs in CI, not in anyone's memory.
#
# Usage: buildtools/check-ffm-metadata.sh [--update]
#
#   (no argument)  verify; exit 1 on drift
#   --update       overwrite the committed metadata with what the agent recorded
#
# Requires GraalVM as JAVA_HOME.
set -euo pipefail

cd "$(dirname "$0")/.."

# Modules whose tests exercise FFM. Add a module here when it starts making downcalls.
MODULES="core"

UPDATE=0
[ "${1:-}" = "--update" ] && UPDATE=1

GROUP=org.fuin.sokar
FAILED=0

for module in $MODULES; do

    artifact="sokar-$module"
    committed="$module/src/main/resources/META-INF/native-image/$GROUP/$artifact/reachability-metadata.json"

    echo "==> $artifact"
    rm -rf "$module/target/native/agent-output"
    ./mvnw -B -q -Pnative -Dagent=true -pl "$module" test >/dev/null

    generated=$(find "$module/target/native/agent-output/test" -name reachability-metadata.json | sort | tail -1)
    if [ -z "$generated" ]; then
        echo "    ERROR: the agent produced no metadata - is the 'native' profile wired up?"
        FAILED=1
        continue
    fi

    # Only the 'foreign' section is kept. Reflection and resource entries recorded during a test
    # run describe JUnit and AssertJ, not the product.
    if ! python3 - "$generated" "$committed" "$UPDATE" <<'PY'
import json, os, sys

generated, committed, update = sys.argv[1], sys.argv[2], sys.argv[3] == "1"

actual = {"foreign": json.load(open(generated)).get("foreign", {})}
expected = json.load(open(committed)) if os.path.exists(committed) else None

if actual == expected:
    print("    up to date")
    sys.exit(0)

if update:
    os.makedirs(os.path.dirname(committed), exist_ok=True)
    with open(committed, "w") as fp:
        fp.write(json.dumps(actual, indent=2) + "\n")
    print("    updated " + committed)
    sys.exit(0)

print("    STALE: " + committed)
print("    committed: " + json.dumps(expected))
print("    recorded : " + json.dumps(actual))
print("    run buildtools/check-ffm-metadata.sh --update and commit the result")
sys.exit(1)
PY
    then
        # Not "exit 1" here: every module is checked, so one run reports all the drift.
        FAILED=1
    fi

done

exit $FAILED
