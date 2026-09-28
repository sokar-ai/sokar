Feature: The shield commands

  What a task may reach, and what happened when it tried.

  Scenario Outline: every shield command answers for itself
    When a script runs "sokar shield <command> --help"
    Then it exits zero

    Examples:
      | command   |
      | read      |
      | watch     |
      | dns       |
      | subscribe |
      | sets      |
      | egress    |

  Scenario: the curated sets are readable without a project
    When a script runs "sokar shield sets"
    Then it exits zero

  Scenario: an unknown set name stops rather than being ignored
    When a script runs "sokar shield egress --add-set no-such-set --dry-run"
    Then it exits non-zero

  Scenario: the resolver's configuration for a project is written without starting a resolver
    Given a project called "resolved" of class "guarded" with a file in it
    When a script runs:
      """
      dir=$(mktemp -d)
      sokar shield dns --project resolved --pid 1 --allow example.org --config-only "$dir/dns.conf"
      # The names that may resolve are in a servers file beside it, the one part dnsmasq re-reads.
      grep -c '^server=/example.org/' "$dir/dnsmasq.servers" | sed 's/^/example.org is served: /'
      rm -rf "$dir"
      """
    Then it exits zero
    And its output has a line matching "config +/.*/dns\.conf"
    And its output does not contain "example.org is served: 0"

  Scenario: subscribing to a watcher that is not there says where it looked
    When a script runs "sokar shield subscribe --socket /tmp/sokar-acceptance-no-watcher.sock --count 1"
    Then it exits non-zero
    And its output contains "cannot connect to /tmp/sokar-acceptance-no-watcher.sock"
