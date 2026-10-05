@slow
Feature: A person alone, working locally, with no forge

  project.yml is committed into the code repository itself, which is then the project's own repository;
  the machine follows that path; a task works on it; and what a person approves lands back in that same
  repository on this machine. Measured end to end, so the getting-started guides can say what is true.

  Scenario: approved work lands in the project's own repository on this machine, on a branch of its own
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "solo" of class "guarded" with a file in it
    When a task called "work" is started in "solo" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'cd /workspace && echo "done by the agent" >> README.md && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: solo work" && timeout 30 git push -q sokar HEAD:"$SOKAR_TASK_REF"'
      """
    Then it exits zero
    When a script runs:
      """
      sokar gate approve work --project solo; echo "with no upstream: exit $?"
      sokar gate approve work --project solo --upstream ~/solo; echo "onto the checked-out main: exit $?"
      sokar gate approve work --project solo --upstream ~/solo --branch sokar/work; echo "onto its own branch: exit $?"
      git -C ~/solo log --format='%s' -1 sokar/work | sed 's/^/landed: /'
      git -C ~/solo log --format='%s' -1 main | sed 's/^/main still: /'
      """
    Then its output contains "No upstream is configured for this project"
    And its output contains "with no upstream: exit 70"
    And its output contains "onto the checked-out main: exit 70"
    And its output contains "onto its own branch: exit 0"
    And its output contains "landed: acceptance: solo work"
    And its output contains "main still: initial"
