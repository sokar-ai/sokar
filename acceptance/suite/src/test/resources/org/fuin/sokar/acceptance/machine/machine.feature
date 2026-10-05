Feature: The commands a machine answers about itself

  Every group's entry point, and the commands that take no group. A command that vanished,
  was renamed, or stopped parsing its own options shows up here before anybody types it.

  Scenario Outline: every command group answers for itself
    When a script runs "sokar <group> --help"
    Then it exits zero
    And its output contains "<mentions>"

    Examples:
      | group  | mentions  |
      | task   | run       |
      | shield | egress    |
      | vault  | login     |
      | gate   | pending   |
      | daemon | connect   |

  Scenario Outline: every top-level command answers for itself
    When a script runs "sokar <command> --help"
    Then it exits zero

    Examples:
      | command    |
      | agents     |
      | providers  |
      | completion |
      | projects   |
      | setup    |
      | doctor   |
      | panic    |

  Scenario: doctor reports whether this machine can run a task
    When a script runs "sokar doctor"
    Then it exits zero
    And its output contains "hooks registered"
    And its output contains "podman"

  Scenario: panic says what it would stop without stopping anything
    When a script runs "sokar panic --dry-run"
    Then it exits zero

  Scenario: agents lists what is installed
    When a script runs "sokar agents"
    Then it exits zero

  Scenario: an unknown command is answered with what does exist
    When a script runs "sokar nonsense"
    Then it exits non-zero
    And its output contains "has no command called"

  Scenario: a command typed in the wrong place is found where it lives
    When a script runs "sokar unlock"
    Then it exits non-zero
    And its output contains "vault unlock"

  Scenario: setup registers the hooks for this account, and says they touch no other container
    # Run on a machine that is already set up, so it rewrites what is there; never --uninstall, which
    # would take the firewall from every task after it.
    When a script runs "sokar setup"
    Then it exits zero
    And its output contains "installed"
    And its output contains "50-sokar.conf"
    And its output contains "only fire for containers carrying Sokar's own annotation"
    # Where the account has the user unit it is started; where it has none - an account running a build from source, as
    # a CI leg's extra accounts do - it says so and how, and the rest goes on.
    And its output has a line matching "daemon +(already running|started, and starts with this account from now on|not started: .+ - start it in this account's own session with 'systemctl --user enable --now sokard')"

  Scenario: setup on an account with no vault leaves it for a terminal and says how, and doctor names it
    # What a wizard meets on a machine just prepared, run the way a script runs it: with no terminal. Only the
    # vault is new here; the hooks and the daemon are this account's, and running setup again changes neither.
    When a script runs:
      """
      export SOKAR_VAULT="$(mktemp -d)/vault.bin"
      sokar setup
      sokar doctor | grep -E '^vault '
      """
    Then it exits zero
    And its output has a line matching "vault +none yet; it needs a passphrase typed at a terminal .*ssh -t.*"
    And its output contains "Not ready yet"
    And its output contains "there is no vault here yet"

  Scenario: the providers say whether the credential each needs is stored, without showing it
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    When a script runs "sokar providers"
    Then it exits zero
    And its output contains "stored as 'anthropic' (api-key)"
    And its output does not contain the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"
    When a script runs "sokar vault lock"
    And a script runs "sokar providers"
    Then its output contains "unknown - vault locked"

  Scenario Outline: a completion script is given for a shell that has one, and refused for one that has not
    When a script runs "sokar completion <shell>"
    Then its output contains "<says>"

    Examples:
      | shell | says                                |
      | bash  | complete -F _sokar_completions sokar |
      | zsh   | #compdef sokar                      |
      | fish  | no completion script for 'fish'     |
