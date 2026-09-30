@slow
Feature: A task holds more than one credential

  Besides its agent's own, a task holds every credential its project names, each as a token worthless
  anywhere else and reached through the broker, which puts the real key where that service expects it.
  The service here echoes the headers it received, so what arrived upstream can be read back.

  Scenario: a second credential reaches its own service with its real key, and the key never enters the container
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "search" of kind "api-key"
    # Asked of the daemon below: one that is known to answer, not whatever state a prepared account's is in.
    And a daemon of this scenario's own
    And a script runs:
      """
      mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations"
      printf '%s\n' 'name: echo' 'upstream: https://postman-echo.com' 'auth_header: X-Echo-Key' \
          > "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-echo.yaml"
      """
    And a project called "credentialed" of class "guarded" whose project file also says:
      """
      credentials:
        search: echo
      """
    When a task called "holding" is started in "credentialed" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'echo "url: $SOKAR_URL_SEARCH"; curl -s --max-time 30 -H "X-Echo-Key: $SOKAR_TOKEN_SEARCH" "$SOKAR_URL_SEARCH/headers"'
      """
    Then its output contains "url: http://127.0.0.1:9419"
    # The echo shows the header as it arrived upstream: the real key, put there by the broker.
    And its output contains "sk-sokar-acceptance-not-a-real-key"
    When a script runs about the task:
      """
      podman exec {task} env | grep -c 'sk-sokar-acceptance-not-a-real-key' | sed 's/^/real key in the container: /'
      podman exec {task} sh -c 'test -n "$SOKAR_TOKEN_SEARCH" && echo "a token for search is there"'
      """
    Then its output contains "real key in the container: 0"
    And its output contains "a token for search is there"
    # An interface lists what a task holds, by name and destination, never a token.
    When a script runs:
      """
      printf '%s\0' '{"method":"org.fuin.sokar.Tasks1.List","parameters":{}}' | timeout 60 sokar daemon connect 2>&1 \
          | tr '\0' '\n' > "$HOME/list.out"
      grep -m1 -o '"credentials":{"search":"echo"}' "$HOME/list.out" || head -c 400 "$HOME/list.out"
      rm -f "$HOME/list.out"
      # A destination nobody declared, named by the run: refused before anything exists, and said as the run's.
      sokar task start undeclared --project credentialed --repository credentialed --agent stub --prompt 'x' \
          --clearance deny --credential extra=nowhere; echo "exit $?"
      """
    Then its output contains '"credentials":{"search":"echo"}'
    And its output contains "the run names credential 'extra' for 'nowhere'"
    And its output contains "exit 69"
    And a script runs "rm -f ${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-echo.yaml; sokar task remove {task} --force" about the task

  Scenario: an unattended run whose credential's token cannot be bought is refused before anything is made
    # A credential the broker buys a token with is tried once before the container exists: an authorization
    # server that cannot be reached makes it as missing as a key the vault lacks.
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a script runs:
      """
      # A made-up client secret: the runner's secrets reach the machine on standard input only, never as a
      # variable a script could read.
      printf '%s\n' 'acceptance-client-secret-not-a-real-one' | sokar vault put buyer --type client-credentials \
          --setting token_url=https://sokar-acceptance.invalid/token --setting client_id=acceptance
      mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations"
      printf '%s\n' 'name: bought' 'upstream: https://postman-echo.com' \
          > "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-bought.yaml"
      """
    And a project called "buying" of class "guarded" whose project file also says:
      """
      credentials:
        buyer: bought
      """
    When a script runs:
      """
      sokar task start unbought --project buying --repository buying --agent stub --prompt 'say hello' --clearance deny
      echo "exit $?"
      podman ps -a --format '{{.Names}}' | grep -c 'sokar-buying-unbought' | sed 's/^/containers: /'
      rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-bought.yaml"
      """
    Then its output contains "credential 'buyer': the authorization server at sokar-acceptance.invalid could not be reached"
    And its output contains "nothing was created"
    And its output contains "exit 69"
    And its output contains "containers: 0"

  Scenario: a credential a person grants once refuses an unattended run until somebody has granted it
    # The device code flow: 'vault authorize' shows a link and a code and keeps the grant in this account's
    # vault. Without one, an unattended run is refused before anything exists, as with a missing key.
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    # Asked of the daemon below, so a second person would see the refused start as a question.
    And a daemon of this scenario's own
    And a script runs:
      """
      # A public client: no secret, so its value is '-'.
      printf '%s\n' '-' | sokar vault put forge-app --type oauth-device --setting client_id=acceptance \
          --setting device_authorization_url=https://sokar-acceptance.invalid/device \
          --setting token_url=https://sokar-acceptance.invalid/token --setting scopes=offline_access
      mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations"
      printf '%s\n' 'name: forge' 'upstream: https://postman-echo.com' \
          > "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-forge.yaml"
      """
    And a project called "granting" of class "guarded" whose project file also says:
      """
      credentials:
        forge-app: forge
      """
    When a script runs:
      """
      sokar task start ungranted --project granting --repository granting --agent stub --prompt 'say hello' --clearance deny
      echo "exit $?"
      podman ps -a --format '{{.Names}}' | grep -c 'sokar-granting-ungranted' | sed 's/^/containers: /'
      # The refusal is a question every interface sees: the stream's first reply is what is open now.
      printf '%s\0' '{"method":"org.fuin.sokar.Tasks1.Authorizations","parameters":{},"more":true}' \
          | timeout 5 sokar daemon connect 2>&1 | tr '\0' '\n' | head -1 > "$HOME/asked.out"
      grep -o '"credential":"forge-app"' "$HOME/asked.out" | sed 's/^/asked: /' || head -c 400 "$HOME/asked.out"
      grep -o '"state":"never"' "$HOME/asked.out" | sed 's/^/asked: /'
      rm -f "$HOME/asked.out" "${XDG_STATE_HOME:-$HOME/.local/state}/sokar/authorizations/forge-app.json"
      sokar vault authorize forge-app; echo "authorize exit $?"
      sokar vault list | grep -c 'grant/' | sed 's/^/grants listed: /'
      rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-forge.yaml"
      """
    Then its output contains "credential 'forge-app': nobody has granted it yet"
    And its output contains "sokar vault authorize forge-app"
    And its output contains "exit 69"
    And its output contains "containers: 0"
    And its output contains 'asked: "credential":"forge-app"'
    And its output contains 'asked: "state":"never"'
    And its output contains "the service at sokar-acceptance.invalid could not be reached"
    And its output contains "authorize exit 1"
    And its output contains "grants listed: 0"
