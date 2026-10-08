@slow
Feature: A file handed to a running task

  Something the work needs and git must not hold - a build log, a screenshot, a specification - is handed to a
  running task with 'sokar task give'. It appears whole in /sokar/files, which holds nothing else, readable by the
  agent and not its to change; it never lands in the work; and every hand-in is written down, also after the task
  is gone. Nothing here takes a file out of a task.

  Scenario: a file handed in is read whole in /sokar/files, cannot be changed by the agent, and is written down
    Given the suite runs as an unprivileged user
    And the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "handin" of class "guarded" with a file in it
    When a task is started in "handin" for the "stub" agent and left running
    # There before anything is handed in, and empty; and the guide its agent is given names it.
    And the task's container runs:
      """
      ls -A /sokar/files | wc -l | sed 's/^/files before: /'
      grep -q 'build-<commit>.txt' /run/sokar/guide/README.md && echo "the guide names the builds"
      echo more >> /run/sokar/guide/README.md 2>/dev/null || echo "the guide is not the agent's to change"
      """
    Then its output contains "files before: 0"
    And its output contains "the guide names the builds"
    And its output contains "the guide is not the agent's to change"
    When a script runs about the task:
      """
      dir=$(mktemp -d)
      yes 'the build failed here' | head -c 5000000 > "$dir/build.log"
      sokar task give {task} "$dir/build.log"; echo "give exit $?"
      sokar task give {task} "$dir/build.log" --as ../escape; echo "escape exit $?"
      rm -rf "$dir"
      """
    Then its output contains "give exit 0"
    And its output contains "given     "
    And its output contains "escape exit 65"
    When the task's container runs:
      """
      echo "task sha256 $(sha256sum /sokar/files/build.log | cut -c1-64)"
      stat -c 'owner %U mode %a' /sokar/files/build.log
      rm -f /sokar/files/build.log 2>/dev/null; test -e /sokar/files/build.log && echo "still there"
      cd /workspace && git status --porcelain | wc -l | sed 's/^/work changed: /'
      """
    Then its output contains "owner root mode 644"
    And its output contains "still there"
    And its output contains "work changed: 0"
    And its output contains "task sha256 2cbca959b926a368a22ed78204ea084ab8b7d9caae53fc4b20a6b457c2d77725"
    When a script runs about the task:
      """
      sokar task status {task} | grep '^file '
      sokar task take-back {task} build.log; echo "take-back exit $?"
      sokar task files {task}
      """
    Then its output contains "/sokar/files/build.log  5000000 bytes"
    And its output contains "take-back exit 0"
    And its output has a line matching ".*given .*5000000 .*build.log"
    And its output has a line matching ".*taken back .*build.log"
    When the task's container runs:
      """
      ls -A /sokar/files | wc -l | sed 's/^/files after: /'
      """
    Then its output contains "files after: 0"
