Feature: A draft project file is checked against this machine before a person commits it

  What Sokar refuses anywhere blocks the commit, and what only this machine lacks warns. A transport's own
  section is asked of the transport, when its describe offers the check.

  Scenario: an interface checks a draft, and a transport judges its own section
    Given a daemon of this scenario's own
    When a script runs:
      """
      transports="${XDG_DATA_HOME:-$HOME/.local/share}/sokar/transports"
      mkdir -p "$transports"
      scheme="checked$$"
      adapter="$transports/sokar-message-transport-$scheme"
      # A transport of the scenario's own: it offers the check and refuses a tls_verify that is not on or off.
      printf '%s\n' '#!/bin/sh' \
          "[ \"\$1\" = describe ] && { echo '{\"scheme\":\"$scheme\",\"lifecycle\":[\"setup\",\"settings\"]}'; exit 0; }" \
          '[ "$1" = settings ] || exit 64' \
          'if grep -q "\"maybe\"" ; then echo "{\"refused\":[\"tls_verify is on or off\"],\"warnings\":[]}";' \
          'else echo "{\"refused\":[],\"warnings\":[\"tls_verify off is for development only\"]}"; fi' > "$adapter"
      chmod +x "$adapter"
      check() {
          python3 -c 'import json,sys; print(json.dumps({"method":"org.fuin.sokar.Tasks1.CheckProjectFile","parameters":{"text":sys.stdin.read()}}), end="\0")' \
              | timeout 60 sokar daemon connect | tr '\0' '\n' | head -1
      }
      draft() {
          printf '%s\n' 'project:' '  name: "drafted"' '  security_class: "guarded"' 'image:' '  base_image: "ubuntu:24.04"' \
              'mail:' '  transports:' "    $scheme:" "      tls_verify: $1"
      }
      draft maybe | check | grep -o "\"refused\":\[\"mail.transports.$scheme: tls_verify is on or off\"\]" \
          && echo "a refusal of its own blocks the commit"
      draft off | check | grep -o "mail.transports.$scheme: tls_verify off is for development only" \
          && echo "a warning of its own warns"
      draft off | check | grep -q '"refused":\[\]' && echo "and blocks nothing"
      rm -f "$adapter"
      """
    Then its output contains "a refusal of its own blocks the commit"
    And its output contains "a warning of its own warns"
    And its output contains "and blocks nothing"
