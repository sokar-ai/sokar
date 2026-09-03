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
MODULES="core shield"

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

def key(entry):
    return json.dumps(entry, sort_keys=True)

recorded = json.load(open(generated)).get("foreign", {}).get("downcalls", [])
committed_doc = json.load(open(committed)) if os.path.exists(committed) else {"foreign": {}}
existing = committed_doc.get("foreign", {}).get("downcalls", [])

# The committed file must be a SUPERSET of what the agent saw. The agent only records what a test
# actually executed, and some downcalls cannot be executed in a test at all - binding an NFLOG
# group needs CAP_NET_ADMIN, and an exec path replaces the process. Those entries are written by
# hand, so demanding equality here would delete them on every run.
missing = [e for e in recorded if key(e) not in {key(x) for x in existing}]

if not missing:
    print("    up to date (%d registered, %d observed)" % (len(existing), len(recorded)))
    sys.exit(0)

if update:
    merged = existing + missing
    merged.sort(key=key)
    os.makedirs(os.path.dirname(committed), exist_ok=True)
    with open(committed, "w") as fp:
        fp.write(json.dumps({"foreign": {"downcalls": merged}}, indent=2) + "\n")
    print("    added %d downcall(s) to %s" % (len(missing), committed))
    sys.exit(0)

print("    STALE: " + committed)
for entry in missing:
    print("    not registered: " + key(entry))
print("    run buildtools/check-ffm-metadata.sh --update and commit the result")
sys.exit(1)
PY
    then
        # Not "exit 1" here: every module is checked, so one run reports all the drift.
        FAILED=1
    fi

done

exit $FAILED
