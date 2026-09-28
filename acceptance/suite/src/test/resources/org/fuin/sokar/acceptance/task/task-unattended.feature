@slow
Feature: An unattended run, started at the terminal and through the daemon

  The two ways a person or an interface starts an agent with a prompt: 'task start -P', and the
  daemon's Start. They share one method, and "they share a method" is an argument, not a
  measurement - the daemon's half once answered "started" for a run nobody performed. The agent
  authenticates with a fake credential and fails; what is measured is that it ran and its output was
  kept.

  The daemon is this scenario's own, reading this scenario's vault: the account's daemon would answer
  about the account's credential. The account's daemon is started again afterwards.

  Background:
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "unattended" of class "guarded" with a file in it

  Scenario: a prompt given at the terminal runs the agent and keeps what it said
    When a script runs:
      """
      sokar task start headless --project unattended --repository unattended --agent stub --clearance deny -P "say hello and stop" >/dev/null 2>&1
      log="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/sokar-unattended-headless/task.log"
      test -s "$log" && echo "the agent's output was kept" || echo "nothing was kept at $log"
      sokar task remove sokar-unattended-headless --force >/dev/null 2>&1
      """
    Then its output contains "the agent's output was kept"

  Scenario: the daemon says whether work can start, and why not, before anything is started
    Given a daemon of this scenario's own
    When a script runs:
      """
      ask() { printf '%s\0' "$1" | timeout 60 sokar daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
      # Asked without a repository, it has to answer about everything else first: being told to choose
      # one means nothing else is in the way.
      ask '{"method":"org.fuin.sokar.Tasks1.CanStart","parameters":{"project":"unattended","agent":"stub"}}' \
          | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/without a repository: /'
      # The name it looked for is the provider's: a credential belongs to whoever issued it.
      ask '{"method":"org.fuin.sokar.Tasks1.CanStart","parameters":{"project":"unattended","agent":"stub","repository":"unattended"}}' \
          | grep -oE '"(outcome|credential)":"[^"]*"' | sed 's/^/with one: /'
      ask '{"method":"org.fuin.sokar.Tasks1.CanStart","parameters":{"agent":"not-installed"}}' \
          | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/an agent nobody installed: /'
      # A locked vault is not a missing credential: unlocking is the action, storing one is not.
      sokar vault lock >/dev/null
      ask '{"method":"org.fuin.sokar.Tasks1.CanStart","parameters":{"project":"unattended","agent":"stub","repository":"unattended"}}' \
          | grep -oE '"outcome":"[A-Z_]+"' | sed 's/^/locked: /'
      """
    Then it exits zero
    And its output contains 'without a repository: "outcome":"NO_REPOSITORY_CHOSEN"'
    And its output contains 'with one: "outcome":"READY"'
    And its output contains 'with one: "credential":"anthropic"'
    And its output contains 'an agent nobody installed: "outcome":"UNKNOWN_AGENT"'
    And its output contains 'locked: "outcome":"VAULT_LOCKED"'

  Scenario: the daemon says what an agent was refused, and runs one with a prompt
    Given a daemon of this scenario's own
    When a script runs:
      """
      ask() { printf '%s\0' "$1" | timeout 600 sokar daemon connect 2>/dev/null | tr '\0' '\n' | head -1; }
      # The stub declares example.net and is deliberately not given it - the name the resolver refuses.
      ask '{"method":"org.fuin.sokar.Tasks1.Agents","parameters":{}}' | grep -oE '"refusedDomains":\["example.net"\]'
      reply=$(ask '{"method":"org.fuin.sokar.Tasks1.Start","parameters":{"task":"viadaemon","project":"unattended","agent":"stub","repository":"unattended","prompt":"say hello and stop","clearance":"deny","keep":true}}')
      container=$(printf '%s' "$reply" | grep -oE '"container":"[^"]*"' | cut -d'"' -f4)
      echo "started: ${container:-nothing}"
      test -s "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/$container/task.log" && echo "the daemon ran the agent, not only the container"
      [ -n "$container" ] && sokar task remove "$container" --force >/dev/null 2>&1
      true
      """
    Then its output contains '"refusedDomains":["example.net"]'
    And its output contains "started: sokar-unattended-viadaemon"
    And its output contains "the daemon ran the agent, not only the container"
