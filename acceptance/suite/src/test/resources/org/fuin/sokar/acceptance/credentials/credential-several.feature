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
    And a script runs "rm -f ${XDG_DATA_HOME:-$HOME/.local/share}/sokar/destinations/acceptance-echo.yaml; sokar task remove {task} --force" about the task
