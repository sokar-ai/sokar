@slow
Feature: A name refused by declaration does not resolve, whatever allows it

  An agent declares the names it asks for and is deliberately not given, and a project can refuse a
  name as well. The declaration is a promise only if something keeps it: the task's resolver answers
  each refused name NXDOMAIN, also under an allowed parent and also when the same name is allowed
  elsewhere, and a widening of a running task does not take a refusal back. It refuses a name, not
  an address, and the start says so.

  Background:
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    # iana.org allowed and www.iana.org, under it, refused; example.net allowed here and refused by the stub.
    And a project called "refusing" of class "guarded" whose project file also says:
      """
      egress:
        domains: ["iana.org", "example.net"]
        refused: ["www.iana.org"]
      """

  Scenario: a refused name answers NXDOMAIN under an allowed parent, and the start says who refused it
    When a task called "names" is started in "refusing" for the "stub" agent and left running
    Then its output has a line matching "www\.iana\.org +refused on purpose by project"
    And its output has a line matching "example\.net +refused on purpose by agent stub"
    And its output contains "a refusal is of a name"
    When a script runs about the task:
      """
      for host in iana.org www.iana.org example.net; do
          podman exec {task} getent hosts "$host" >/dev/null && echo "$host resolves" || echo "$host does not resolve"
      done
      # Every name 'sokar agents' reports as refused for this agent, asked of the task itself.
      for host in $(sokar agents --verbose | awk '/^[^ ]/ { row = ($1 == "stub") } row && $1 == "refused:" { $1 = ""; print }' | tr ',' ' '); do
          podman exec {task} getent hosts "$host" >/dev/null && echo "reported refused and resolves: $host"
      done
      true
      """
    Then its output contains "iana.org resolves"
    And its output contains "www.iana.org does not resolve"
    And its output contains "example.net does not resolve"
    And its output does not contain "reported refused and resolves"

  Scenario: widening a running task does not take a refusal back
    When a task called "widening" is started in "refusing" for the "stub" agent and left running
    And a script runs "sokar shield egress --task {task} --add-domain cdn.www.iana.org" about the task
    Then it exits non-zero
    And its output contains "cdn.www.iana.org is refused for this task, under www.iana.org"
    When a script runs "podman exec {task} getent hosts www.iana.org || echo 'still refused'" about the task
    Then its output contains "still refused"
