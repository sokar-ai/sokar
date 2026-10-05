@slow
Feature: An agent reaches work without being asked anything

  An agent repository proves its agent reached work with nothing asked first, attached and unattended,
  and the check fails the day a release adds a question. The kit knows no agent: the agent declares
  the text it shows once at work and how long that may take, and the check reads the screen tmux
  draws in the task until that text has stayed there, typing nothing. The stub is the agent here: one
  that asks nothing; with '.sokar-stub-asks' in its workspace, one that asks a question first; with
  '.sokar-stub-covers', one that draws its prompt and then a question over it - which is how the
  check proves it notices each.

  Background:
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"

  Scenario: an agent that asks nothing reaches work within its bound
    Given a project called "ready" of class "guarded" with a file in it
    And a terminal on the machine
    When I run "sokar task start attended --project ready --repository ready --agent stub --clearance deny"
    Then the "stub" agent in task "attended" of "ready" reaches work without being asked anything
    And a script runs "sokar task remove sokar-ready-attended --force"

  Scenario: an agent that asks one question first does not pass
    Given a project called "asking" of class "guarded" with a file in it
    And a script runs:
      """
      cd ~/asking && touch .sokar-stub-asks && git add -A && git commit -q -m 'ask first' \
          && sokar project follow asking ~/asking --unverified >/dev/null
      """
    And a terminal on the machine
    When I run "sokar task start attended --project asking --repository asking --agent stub --clearance deny"
    Then waiting for the "stub" agent in task "attended" of "asking" to reach work fails, and the screen shows "Trust this folder?"
    And a script runs "sokar task remove sokar-asking-attended --force"

  Scenario: an agent that draws its prompt and then a question over it does not pass
    Given a project called "covering" of class "guarded" with a file in it
    And a script runs:
      """
      cd ~/covering && touch .sokar-stub-covers && git add -A && git commit -q -m 'cover the prompt' \
          && sokar project follow covering ~/covering --unverified >/dev/null
      """
    And a terminal on the machine
    When I run "sokar task start attended --project covering --repository covering --agent stub --clearance deny"
    Then waiting for the "stub" agent in task "attended" of "covering" to reach work fails, and the screen shows "Setup step 1 of 5"
    And a script runs "sokar task remove sokar-covering-attended --force"

  Scenario: an unattended run of an agent that asks nothing ends within its bound
    Given a project called "unwatched" of class "guarded" with a file in it
    When a task nobody is watching is started in "unwatched" for the "stub" agent and ends within 600 seconds
    Then it exits zero

  Scenario: an unattended run served by a provider the scenario names ends within its bound
    Given a project called "through" of class "guarded" with a file in it
    When a task nobody is watching is started in "through" for the "stub" agent through "anthropic" and ends within 600 seconds
    Then it exits zero
