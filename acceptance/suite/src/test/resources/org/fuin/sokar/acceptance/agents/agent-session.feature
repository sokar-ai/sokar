@slow
Feature: A task that comes back continues the conversation it was having

  Where an agent declares how it names its sessions, Sokar records the one a task's agent was running -
  from an unattended run's records, or from an attached agent's own session files when the task stops -
  and starting the task again continues it, and says so. Removing the task forgets it. The stub is the
  agent here: it names a session per run and, given '--resume', says it continued one.

  Background:
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"

  Scenario: an unattended run started again continues its session, and a removed task starts fresh
    Given a project called "cont" of class "guarded" with a file in it
    When a task called "talk" nobody is watching is started in "cont" for the "stub" agent and ends within 600 seconds
    Then sokar says task "talk" of "cont" last said "stub: finished"
    And sokar says task "talk" of "cont" has a session to continue
    When a script runs "sokar task stop sokar-cont-talk"
    And a task called "talk" nobody is watching is started in "cont" for the "stub" agent and ends within 600 seconds
    Then sokar says task "talk" of "cont" last said "stub: continued"
    # Removing the task forgets its session: a new task of the same name has nothing to continue.
    When a script runs "sokar task remove sokar-cont-talk --force"
    And a task called "talk" nobody is watching is started in "cont" for the "stub" agent and ends within 600 seconds
    Then sokar says task "talk" of "cont" last said "stub: finished"
    And a script runs "sokar task remove sokar-cont-talk --force"

  Scenario: an attached agent stopped and started again continues its session
    Given a project called "back" of class "guarded" with a file in it
    And a terminal on the machine
    When I run "sokar task start attended --project back --repository back --agent stub --clearance deny"
    Then the "stub" agent in task "attended" of "back" reaches work without being asked anything
    When a script runs "sokar task stop sokar-back-attended"
    Then sokar says task "attended" of "back" has a session to continue
    Given a terminal on the machine
    When I run "sokar task start attended --project back --repository back --agent stub --clearance deny"
    Then the screen of task "attended" of "back" shows "stub: continuing"
    And a script runs "sokar task remove sokar-back-attended --force"
