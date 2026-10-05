@slow
Feature: An agent's login is stored, through the same chain a real login takes

  'sokar vault login' could never store anything, for any agent, and nobody knew: 'podman cp' copied the
  config directory one level deeper than the extractor looked, and the message blamed the person. No unit
  test sees what podman does, so this logs the stub agent in on a machine: its login writes an oauth-typed
  fixture where its definition says, and Sokar copies it out, reads it with the extractor and stores it -
  nothing in Sokar knows it is a stub. No account, no network, no secret.

  Both moments are their own code: a login stored while it still runs, which needs the vault open, and one
  stored after it ends, when the vault was shut and a passphrase has to be asked for.

  Scenario: work started before anybody signed in is not started, and is told to sign in first
    # Told only what was missing, a new person took the one start that needs no credential - a shell, where the
    # agent said "Not logged in". An agent with a sign-in of its own names it first.
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    When a script runs:
      """
      base=$(mktemp -d)
      repo="early$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      git init -q --bare --initial-branch=main "$base/$repo.git"
      git init -q --initial-branch=main "$base/seed"
      git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m first
      git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
      git clone -q "$base/$repo.git" "$base/$repo"
      cd "$base/$repo"
      timeout 300 sokar task start early --agent stub --detach --clearance deny; echo "exit $?"
      sokar project default remove "$repo" > /dev/null 2>&1
      """
    Then its output contains "Sign in first with 'sokar vault login stub'"
    And its output contains "exit 69"

  Scenario: a login is stored the moment it is written, while the agent still runs
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And a terminal on the machine
    When a script runs "touch ~/.sokar-login-began"
    And I run "sokar vault login stub"
    Then within 600 seconds the terminal shows "stub: logged in"
    # Ended by Sokar the moment it was stored, and said once: no "Press Enter" of the agent's beside it.
    And within 30 seconds the terminal shows "Signed in - the credential is stored as 'anthropic'"
    And the terminal shows "Nothing more to do here."
    And the terminal does not show "the agent exited with"
    And the terminal does not show "sokar-stub-fixture-token"
    # Kept whole: what renews it hidden, the rest as settings anybody may read.
    When a script runs "sokar vault list"
    Then its output contains "expires_at = 20"
    And its output contains "token_url = https://example.com/sokar-stub/token"
    And its output contains "client_id = sokar-stub"
    And its output does not contain "sokar-stub-fixture-refresh"
    # Each copy the login made of the agent's config directory, its credential among them, is gone with it.
    When a script runs:
      """
      sleep 2
      echo "copies left behind: $(find /tmp -maxdepth 1 -name 'sokar-login*' -user "$(id -un)" -newer ~/.sokar-login-began | wc -l)"
      rm -f ~/.sokar-login-began
      """
    Then its output contains "copies left behind: 0"

  Scenario: a login that ends while the vault is shut is stored after it, once the passphrase is typed
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And a terminal on the machine
    When I run "sokar vault lock"
    And I run "sokar vault login stub"
    Then within 600 seconds the terminal shows "stub: logged in"
    And within 30 seconds the terminal shows "Vault passphrase:"
    When I type "acceptance"
    Then within 30 seconds the terminal shows "Signed in - the credential is stored as 'anthropic'"
    And the terminal shows "Nothing more to do here."
    And the terminal does not show "sokar-stub-fixture-token"
