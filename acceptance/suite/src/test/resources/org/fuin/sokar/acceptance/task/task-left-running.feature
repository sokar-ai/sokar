@slow
Feature: A task left running, and what it holds

  The last stage of every agent repository asks this of its own agent with a real credential. This
  asks it of the stub with a fake one, so that the steps are proven here, on every run that has a
  machine, before three repositories depend on them.

  The credential is fake and set by this suite's own configuration, never an account's: what is
  proven is that the value is searched for and not found, and a fake value is as findable as a real
  one. The kit removes what the scenario made whether it passed or not: the project, unfollowed with
  its task, and the scenario's own vault - the account's is never touched.

  Scenario: the credential reaches neither the task's container nor anything it logged
    Given the suite runs as an unprivileged user
    And the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "live" of class "guarded" with a file in it
    When a task is started in "live" for the "stub" agent and left running
    Then the task's container environment does not contain the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"
    And no log the task left contains the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"
    When the task's container runs:
      """
      echo "handed in: ${SOKAR_ACCEPTANCE_MODEL}"
      """
    Then it exits zero
    And its output contains "handed in: stub-model"
    # The stub's CLI posts to the provider through the socket it was given; that request is the broker's to log.
    When the task's container runs:
      """
      sokar-stub-cli
      """
    Then the task's broker saw a request
    And no log the task left contains the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"

  Scenario: an agent whose provider refused it says so, with the provider's words, and is not called idle
    # The operator's run: the provider answered 402, the agent ended, and the task said "idle", then "working".
    # The stub's last act is one call to its provider, which refuses the fake key; the stub reads that answer
    # as the end of its run.
    Given the suite runs as an unprivileged user
    And the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "refused" of class "guarded" with a file in it
    When a script runs:
      """
      timeout 900 sokar task start asked --project refused --repository refused --agent stub -P 'say hello' \
          --detach --clearance deny >/dev/null 2>&1; echo "start exit $?"
      for i in $(seq 1 45); do
        said=$(sokar task status sokar-refused-asked 2>&1)
        echo "$said" | grep -q '^ended' && break
        sleep 2
      done
      echo "$said" | grep -E '^(activity|ended) '
      """
    Then its output contains "start exit 0"
    And its output has a line matching "activity +ended"
    And its output has a line matching "ended +with an error at .*: the provider - .+"
