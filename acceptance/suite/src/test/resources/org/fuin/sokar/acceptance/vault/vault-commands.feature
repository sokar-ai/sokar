Feature: The vault commands

  Fourteen commands, and the ones that matter most here are the refusals: this is the group where a
  mistake ends with a credential somewhere it should not be.

  Scenario Outline: every vault command answers for itself
    When a script runs "sokar vault <command> --help"
    Then it exits zero

    Examples:
      | command    |
      | init       |
      | devices    |
      | revoke     |
      | put        |
      | import     |
      | list       |
      | remove     |
      | serve      |
      | relay      |
      | agent      |
      | unlock     |
      | lock       |
      | passphrase |
      | login      |

  Scenario: a store that cannot be read says why rather than answering an empty list
    # Three states and only one of them is "there are no credentials": a store that was never
    # created, one that is locked, and one that is open and empty. The first two must say so, or
    # absence reads as "nothing is stored here" - which is this project's most repeated mistake.
    When a script runs "sokar vault list"
    Then its output mentions one of "no vault, passphrase, credential"

  Scenario: putting a credential needs a name
    Given a terminal on the machine
    When I run "sokar vault put"
    Then the terminal shows "missing required parameter"

  Scenario: an agent that does not say how to log in is answered, not guessed at
    When a script runs "sokar vault login no-such-agent"
    Then it exits non-zero

  Scenario: no command prints a credential in its own help
    When a script runs "sokar vault put --help"
    Then its output does not contain "sk-ant"

  # The passphrase below is public by construction, and the vaults are the scenarios' own: the
  # account's vault is never read or replaced.

  Scenario: a vault is made once, and a second one is refused where one exists
    When a script runs:
      """
      dir=$(mktemp -d); export SOKAR_VAULT="$dir/vault.bin"
      sokar vault init --passphrase-command 'printf acceptance'; echo "exit $?"
      sokar vault init --passphrase-command 'printf acceptance'; echo "exit $?"
      sokar vault lock >/dev/null; rm -rf "$dir"
      """
    Then its output contains "created"
    And its output contains "exit 0"
    And its output contains "there is already a vault at"

  Scenario: a passphrase command that produces nothing makes no vault
    When a script runs:
      """
      dir=$(mktemp -d); export SOKAR_VAULT="$dir/vault.bin"
      sokar vault init --passphrase-command 'true'; echo "exit $?"
      test -e "$SOKAR_VAULT" && echo "a vault file was written" || echo "no vault file"
      rm -rf "$dir"
      """
    Then its output contains "the passphrase command produced nothing"
    And its output contains "no vault file"

  Scenario: the ways into a vault are listed, and the last one cannot be revoked
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs "sokar vault devices"
    Then it exits zero
    And its output contains "WHAT IT IS WORTH"
    And its output contains "typed by a person, stored nowhere"
    When a script runs "sokar vault revoke passphrase"
    Then it exits non-zero
    And its output contains "That is the last way into"
    When a script runs "sokar vault revoke no-such-slot"
    Then it exits non-zero
    And its output contains "is called 'no-such-slot'"

  Scenario: a credential is removed by name, and removing it again says there was nothing
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs:
      """
      printf 'not-a-real-token' | sokar vault put acceptance.removed --type token
      sokar vault remove acceptance.removed
      sokar vault list
      sokar vault remove acceptance.removed
      """
    Then it exits zero
    And its output contains "removed   acceptance.removed"
    And its output contains "nothing named 'acceptance.removed' was in the vault"
    And its output contains "the vault is empty"

  Scenario: a locked vault is opened again with its passphrase, and nothing else
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs "sokar vault lock"
    Then it exits zero
    And its output contains "locked"
    When a script runs "sokar vault unlock --passphrase-command 'printf wrong'"
    Then it exits non-zero
    And its output contains "that passphrase does not open"
    When a script runs "sokar vault unlock --passphrase-command 'printf acceptance'"
    Then it exits zero
    And its output contains "cached in"
    When a script runs "sokar vault list"
    Then it exits zero

  Scenario: a vault that does not exist is not unlocked, and init is named instead
    When a script runs "SOKAR_VAULT=$(mktemp -d)/vault.bin sokar vault unlock --passphrase-command 'printf acceptance'"
    Then it exits non-zero
    And its output contains "sokar vault init"
    And its output contains "nothing was cached"

  Scenario: the passphrase is changed only by somebody who knows the current one
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs "sokar vault passphrase --passphrase-command 'printf wrong' --new-passphrase-command 'printf changed'"
    Then it exits non-zero
    And its output contains "does not open"
    And its output contains "nothing was changed"
    When a script runs "sokar vault passphrase --passphrase-command 'printf acceptance' --new-passphrase-command 'printf changed'"
    Then it exits zero
    And its output contains "re-encrypted"
    # The old passphrase opened it until now; it must not be what is cached afterwards.
    When a script runs "sokar vault unlock --passphrase-command 'printf acceptance'"
    Then it exits non-zero
    When a script runs "sokar vault unlock --passphrase-command 'printf changed'"
    Then it exits zero

  Scenario: importing from an agent names what is installed, and what it cannot find
    When a script runs "sokar vault import no-such-agent"
    Then it exits non-zero
    And its output contains "no such agent; installed:"
    When a script runs:
      """
      dir=$(mktemp -d)
      sokar vault import stub --config-dir "$dir"; echo "exit $?"
      rm -rf "$dir"
      """
    Then its output mentions one of "does not say where it keeps its credentials, nothing to import from"
    And its output does not contain "exit 0"
