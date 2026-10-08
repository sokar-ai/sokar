@slow
Feature: The build of what an online task pushed

  An online task pushes to its gate, which passes its branch on to the forge as sokar/<task>, and the forge builds it
  somewhere the task cannot see. The upstream here is a repository on the machine, which the gate reads from the
  host. When the project names the forge, a helper on the host follows every commit the task pushes and hands each
  verdict into /sokar/files as it changes, with the failed job's log beside it. The task asks for nothing and holds
  no forge credential. The forge here is the stub build reader, which answers from a file the scenario writes: its
  first answer is where the branch was before the task pushed, and every head after it is a push.

  Scenario: a pushed commit's verdicts and its failed job's log arrive in the task, and the forge token does not
    Given the suite runs as an unprivileged user
    And the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "forge-token" of kind "api-key"
    When a script runs:
      """
      test -x ~/.local/share/sokar/builds/stub-forge && echo "stub reader installed"
      echo '{"heads": {}}' > ~/.local/share/sokar/builds/answers.json
      """
    Then its output contains "stub reader installed"
    Given an online project called "builds" pushing to a repository of its own, whose project file also says:
      """
      builds:
        forge: "stub-forge"
        credential: "forge-token"
      """
    When a task is started in "builds" for the "stub" agent and left running
    And a script runs about the task:
      """
      sokar task status {task} | grep -E '^ *builds '
      """
    Then its output contains "stub-forge, no push yet"
    # The push: the branch now points at a commit, whose build runs.
    When a script runs about the task:
      """
      branch=$(sokar task status {task} | awk '$1 == "branch" { sub("refs/sokar/incoming/", "sokar/", $2); print $2 }')
      printf '{"heads": {"%s": "%s"}, "builds": {"%s": {"verdict": "running"}}}' "$branch" "$(printf 'c%.0s' $(seq 40))" \
          "$(printf 'c%.0s' $(seq 40))" > ~/.local/share/sokar/builds/answers.json
      """
    Then within 90 seconds this script about the task exits zero:
      """
      podman exec {task} grep -q '^verdict: running' /sokar/files/build-cccccccccccc.txt
      """
    When a script runs about the task:
      """
      branch=$(sokar task status {task} | awk '$1 == "branch" { sub("refs/sokar/incoming/", "sokar/", $2); print $2 }')
      sha=$(printf 'c%.0s' $(seq 40))
      cat > ~/.local/share/sokar/builds/answers.json <<EOF
      {"heads": {"$branch": "$sha"}, "builds": {"$sha": {"verdict": "failure", "jobs": [
        {"name": "Build / unit tests", "result": "failure", "log": "MARKER-builds FooTest expected 1 but was 2\n"},
        {"name": "Build / lint", "result": "success", "log": "lint ok\n"}]}}}
      EOF
      """
    Then within 90 seconds this script about the task exits zero:
      """
      podman exec {task} grep -q '^verdict: failure' /sokar/files/build-cccccccccccc.txt
      """
    When the task's container runs:
      """
      cat /sokar/files/build-cccccccccccc.txt
      cat /sokar/files/build-cccccccccccc-1.log
      stat -c 'owner %U mode %a' /sokar/files/build-cccccccccccc-1.log
      ls /sokar/files | wc -l | sed 's/^/files: /'
      """
    Then its output contains "job: Build / unit tests - failure - /sokar/files/build-cccccccccccc-1.log"
    And its output contains "job: Build / lint - success"
    And its output contains "MARKER-builds FooTest expected 1 but was 2"
    And its output contains "owner root mode 644"
    And its output contains "files: 2"
    And the task's container environment does not contain the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"
    When a script runs about the task:
      """
      sokar task status {task} | grep -E '^ *build '
      # What Sokar keeps of it: which builds it delivered, never a line of a log.
      cat ~/.local/state/sokar/builds/{task}.jsonl
      grep -rl MARKER-builds ~/.local/state/sokar /run/user/$(id -u)/sokar 2>/dev/null | wc -l \
          | sed 's/^/log copies on the host: /'
      # The helper names the vault entry, never its value.
      ps -eo args | grep '[w]atch-builds' | sed 's/^/helper: /'
      """
    Then its output contains "failure, 2 jobs, 1 failed"
    And its output contains "\"bytes\":43"
    And its output contains "log copies on the host: 0"
    And its output contains "--credential forge-token"
    And its output does not contain "MARKER-builds FooTest"
    And its output does not contain the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"
