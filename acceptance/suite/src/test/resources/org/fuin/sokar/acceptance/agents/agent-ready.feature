@slow
Feature: An agent reaches work without being asked anything

  An agent repository proves its agent reached work with nothing asked first, attached and unattended,
  and the check fails the day a release adds a question. The kit knows no agent: the agent declares
  the text it shows once at work and how long that may take, and the check waits for it without
  typing anything. The stub is the agent here - one that asks nothing, and, when its workspace holds
  '.sokar-stub-asks', one that asks one question first, which is how the check proves it notices.

  Background:
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"

  Scenario: an agent that asks nothing reaches work within its bound
    Given a project called "ready" of class "guarded" with a file in it
    And a terminal on the machine
    When I run "sokar task start attended --project ready --repository ready --agent stub --clearance deny"
    Then the "stub" agent reaches work without being asked anything
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
    Then waiting for the "stub" agent to reach work fails, and the terminal shows "Trust this folder?"
    And a script runs "sokar task remove sokar-asking-attended --force"

  Scenario: an unattended run of an agent that asks nothing ends within its bound
    Given a project called "unwatched" of class "guarded" with a file in it
    When a task nobody is watching is started in "unwatched" for the "stub" agent and ends within 600 seconds
    Then it exits zero

  Scenario: an unattended run served by a provider the scenario names ends within its bound
    Given a project called "through" of class "guarded" with a file in it
    When a task nobody is watching is started in "through" for the "stub" agent through "anthropic" and ends within 600 seconds
    Then it exits zero
