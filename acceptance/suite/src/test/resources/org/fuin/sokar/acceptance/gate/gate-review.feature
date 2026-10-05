@slow
Feature: Work waiting at the gate, reviewed, backed up and restored

  What an agent pushed exists in the gate's mirror and nowhere else until somebody approves it, so
  the mirror is the one copy of that work. This hands the gate a real push from a real task and then
  does to it what a reviewer does. One scenario, because a second push to the same task's ref would
  be refused as not a fast-forward.

  Scenario: a push waits for review, names what is dangerous first, opens where nothing in it runs, and its mirror is backed up
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "gated" of class "guarded" with a file in it
    When a task called "reviewed" is started in "gated" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'cd /workspace && echo change >> README.md && mkdir -p .github/workflows && echo "on: push" > .github/workflows/ci.yml && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: a change to review" && timeout 30 git push -q sokar HEAD:"$SOKAR_TASK_REF"'
      """
    Then it exits zero
    When a script runs "sokar gate pending --project gated"
    Then its output has a line matching "reviewed +\S+ +[0-9a-f]+ +acceptance: a change to review"
    # A one-line workflow beside an ordinary change: the workflow is what a reviewer must meet first,
    # however small, and the patch follows the same order.
    When a script runs "sokar gate review reviewed --project gated"
    Then it exits zero
    And its output contains "asked: "
    And its output contains "READ FIRST - dangerous by kind, however small:"
    And its output has a line matching "A  \.github/workflows/ci\.yml  \+1 -0  a CI definition.*"
    And its output has a line matching "M  README\.md  \+1 -0"
    When a script runs:
      """
      dir=$(mktemp -d)
      sokar gate checkout nope --project gated --into "$dir/nope"; echo "exit $?"
      test -e "$dir/nope" && echo "a directory was made for nothing"
      sokar gate checkout reviewed --project gated --into "$dir/reviewed"
      grep -c change "$dir/reviewed/README.md" | sed 's/^/lines changed: /'
      rm -rf "$dir"
      """
    Then its output contains "There is no pending push named 'nope'"
    And its output does not contain "a directory was made for nothing"
    And its output contains "opened"
    And its output contains "hooks     off in this copy"
    And its output contains "lines changed: 1"
    # A restore that wrote over the mirror would discard whatever was pushed since the backup.
    When a script runs:
      """
      dir=$(mktemp -d)
      sokar gate backup "$dir/gated.bundle" --project gated
      sokar gate restore "$dir/gated.bundle" --project gated; echo "exit $?"
      sokar gate restore "$dir/missing.bundle" --project gated; echo "exit $?"
      rm -rf "$dir"
      """
    Then its output contains "backed up"
    And its output contains "verified  true"
    And its output contains "A mirror already exists"
    And its output contains "No bundle at"
    And its output does not contain "exit 0"
    And a script runs "sokar task remove {task} --force" about the task
