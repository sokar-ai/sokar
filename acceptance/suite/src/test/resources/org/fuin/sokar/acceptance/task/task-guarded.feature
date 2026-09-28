@slow
Feature: A task in a guarded project, from its image to what it hands back

  What a guarded task is promised, checked on a real container: the image it runs, the hardening it
  runs under, the credential it never sees, the names and ports it may reach, and the gate its work
  leaves by. Each layer between a project file and a packet has been wrong at least once - the
  package that ships the sets, the binary that reads them, the resolver that answers for them, the
  firewall rule that decides the port - and a unit test sees none of them together.

  The credential is fake and set by this suite's configuration. The provider rejects it, and that
  rejection is the proof that the broker swapped the task's token for it: a refusal from Sokar would
  mean the swap never happened. Every scenario starts a task of its own name in one project, so the
  image is built once.

  Background:
    Given the suite runs as an unprivileged user
    And the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "guarded" of class "guarded" whose project file also says:
      """
        snippet: |
          RUN apt-get update && apt-get install -y --no-install-recommends jq \
              && rm -rf /var/lib/apt/lists/*
      egress:
        sets: [maven, git-hosting]
      """

  Scenario: the image carries the agent's tool at the version it pins, the project's tooling, and no root
    When a task called "image" is started in "guarded" for the "stub" agent and left running
    Then its output has a line matching "agent +stub .*"
    And its output has a line matching "snippet +2 lines from the project"
    When a script runs about the task:
      """
      image=$(podman container inspect --format '{{.ImageName}}' {task})
      pinned=$(sokar agents --supply-chain | awk '/^[^ ]/ { row = ($1 == "stub") } row && $1 == "installs:" { print $2 }')
      podman run --rm "$image" sh -c 'command -v sokar-stub-cli; command -v jq; echo "runs as $(whoami)"'
      running=$(podman run --rm "$image" sokar-stub-cli --version | awk '{ print $1 }')
      [ -n "$pinned" ] && [ "$running" = "$pinned" ] && echo "the tool is the pinned version" || echo "the image runs $running, the agent pins $pinned"
      """
    Then it exits zero
    And its output contains "/usr/local/bin/sokar-stub-cli"
    And its output contains "/usr/bin/jq"
    And its output contains "runs as agent"
    And its output contains "the tool is the pinned version"

  Scenario: the container holds no privilege, and the firewall was in place before it ran
    When a task called "hardened" is started in "guarded" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'grep -E "^(NoNewPrivs|CapEff):" /proc/self/status' | awk '{ print $1 $2 }'
      grep -q 'nft createRuntime ok' {state}/hooks.log && echo "the firewall hook ran when the container was created"
      podman exec {task} bash -c 'timeout 5 bash -c "echo > /dev/tcp/1.1.1.1/443"' 2>/dev/null \
          && echo "an undeclared address is open" || echo "an undeclared address is closed"
      """
    Then its output contains "NoNewPrivs:1"
    And its output contains "CapEff:0000000000000000"
    And its output contains "the firewall hook ran when the container was created"
    And its output contains "an undeclared address is closed"

  Scenario: the agent is given a token worth nothing outside the task, and its broker redeems it
    When a task called "token" is started in "guarded" for the "stub" agent and left running
    Then its output has a line matching "token +[A-Z0-9_]+=sokar_pt_.*"
    And the task's container environment does not contain the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"
    # The stub asks the provider through the socket it was pointed at, with the token it was given.
    When the task's container runs:
      """
      test -S "$SOKAR_STUB_SOCKET" && echo "the broker's socket is in the container"
      sokar-stub-cli
      """
    Then its output contains "the broker's socket is in the container"
    And its output contains "authentication_error"
    And its output does not contain "sokar:"
    And its output does not contain the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL"

  Scenario: every name the agent asks for is one it declared, and the one it declared refused stays refused
    When a task called "names" is started in "guarded" for the "stub" agent and left running
    And the task's container runs:
      """
      sokar-stub-cli
      """
    Then its output contains "stub: example.com resolved"
    And its output contains "stub: example.net refused"
    # A resolver appends its search domains to an unqualified lookup; the same query must not read as
    # an undeclared name. What the agent declares refused, and what it reaches through its broker,
    # are left out on purpose - both are absent from what it may resolve by design.
    When a script runs about the task:
      """
      sleep 2
      search=$(podman exec {task} sed -n 's/^search //p' /etc/resolv.conf)
      allowed=$(sokar agents --verbose | awk '/^[^ ]/ { row = ($1 == "stub") }
          row && $1 == "refused:" { $1 = ""; print }
          row && $1 == "provider:" { for (i = 1; i < NF; i++) if ($i == "->") print $(i + 1) }' | tr ', ' '\n\n')
      grep -oE 'config [a-z0-9.-]+ is NXDOMAIN' {state}/dnsmasq.log | awk '{ print $2 }' | sort -u | while read -r name; do
          for suffix in $search; do name=${name%."${suffix%.}"}; done
          case "$name" in *.local|*.localdomain) continue ;; esac
          if printf '%s\n' $allowed | grep -qxF "$name"; then echo "refused on purpose: $name"; else echo "undeclared: $name"; fi
      done
      """
    Then it exits zero
    And its output contains "refused on purpose: example.net"
    And its output does not contain "undeclared:"

  Scenario: the start says which set granted each host, and warns that a forge is reachable
    When a task called "report" is started in "guarded" for the "stub" agent and left running
    Then its output has a line matching "repo\.maven\.apache\.org +set maven"
    And its output has a line matching "github\.com +set git-hosting"
    And its output has a line matching "example\.net +refused on purpose"
    And its output contains "the gate now rests on this container holding no credential"
    And its output contains "unverified - whoever can push there decides what tasks here may reach"

  Scenario: a declared host resolves and answers on 443, and nothing else does
    When a task called "ports" is started in "guarded" for the "stub" agent and left running
    And a script runs about the task:
      """
      for host in repo.maven.apache.org pypi.org; do
          podman exec {task} getent hosts "$host" >/dev/null && echo "$host resolves" || echo "$host does not resolve"
      done
      # 28 is a timeout, which is what a dropped packet looks like; 35, 52 and 56 mean TCP got through and only TLS failed.
      for port in 443 22; do
          code=$(podman exec {task} sh -c "curl -s -o /dev/null --max-time 12 https://github.com:$port/; echo \$?")
          case "$code" in 0|35|52|56) echo "github.com:$port is open" ;; *) echo "github.com:$port is closed ($code)" ;; esac
      done
      """
    Then its output contains "repo.maven.apache.org resolves"
    And its output contains "pypi.org does not resolve"
    And its output contains "github.com:443 is open"
    # So no push goes around the gate.
    And its output contains "github.com:22 is closed"

  Scenario: a running task is widened and narrowed without a restart, in its resolver and its firewall
    When a task called "widened" is started in "guarded" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} getent hosts pypi.org >/dev/null && echo "before: resolves" || echo "before: does not resolve"
      sokar shield egress --task {task} --add-domain pypi.org
      podman exec {task} getent hosts pypi.org >/dev/null && echo "widened: resolves" || echo "widened: does not resolve"
      # Reached once, so an address really enters the firewall and is recorded against the name.
      podman exec {task} sh -c 'curl -s -o /dev/null --max-time 12 https://pypi.org/'
      address=$(awk -F '\t' '$1 == "pypi.org" { print $2; exit }' {state}/granted-addresses)
      [ -n "$address" ] && echo "an address is recorded against the name"
      sokar shield egress --task {task} --remove-domain pypi.org --dry-run
      podman exec {task} getent hosts pypi.org >/dev/null && echo "after a dry run: resolves" || echo "after a dry run: does not resolve"
      sokar shield egress --task {task} --remove-domain pypi.org
      podman exec {task} getent hosts pypi.org >/dev/null && echo "narrowed: resolves" || echo "narrowed: does not resolve"
      pid=$(podman inspect --format '{{.State.Pid}}' {task})
      podman unshare nsenter --target "$pid" --net nft list set inet sokar allowed_v4 | grep -qF "$address" \
          && echo "the address is still in the firewall" || echo "the address is out of the firewall"
      """
    Then it exits zero
    And its output contains "before: does not resolve"
    And its output contains "widened: resolves"
    And its output contains "an address is recorded against the name"
    And its output contains "after a dry run: resolves"
    And its output contains "narrowed: does not resolve"
    And its output contains "the address is out of the firewall"
    # What narrowing leaves alone, said out loud, because somebody will assume otherwise.
    And its output contains "runs to its end"

  Scenario: the agent's work leaves through the gate, which nothing off this machine can reach
    When a task called "handed" is started in "guarded" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'cd /workspace && test -d .git && git remote get-url sokar' && echo "the workspace has a remote to the gate"
      podman exec {task} sh -c 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m "acceptance: work from the agent" && timeout 30 git push -q sokar HEAD:"$SOKAR_TASK_REF"' \
          && echo "the push went through"
      sokar gate pending --project guarded
      port=$(podman exec {task} sh -c 'cd /workspace && git remote get-url sokar' | sed -n 's|.*:\([0-9]*\)/.*|\1|p')
      # Java binds a dual-stack socket, so loopback reads as [::ffff:127.0.0.1] here.
      ss -ltnH "sport = :$port" | grep -qE '(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):'"$port" \
          && echo "the gate listens on loopback only" || ss -ltnH "sport = :$port"
      lan=$(ip -4 -o route get 1.1.1.1 | sed -n 's/.* src \([0-9.]*\).*/\1/p')
      timeout 5 bash -c "echo > /dev/tcp/$lan/$port" 2>/dev/null && echo "the gate answers on $lan" || echo "the gate does not answer on the network"
      """
    Then its output contains "the workspace has a remote to the gate"
    And its output contains "the push went through"
    And its output contains "acceptance: work from the agent"
    And its output contains "the gate listens on loopback only"
    And its output contains "the gate does not answer on the network"

  Scenario: a stopped task holding work nobody pushed is not removed until somebody means it
    When a task called "holding" is started in "guarded" for the "stub" agent and left running
    And a script runs about the task:
      """
      podman exec {task} sh -c 'cd /workspace && git -c user.email=agent@localhost -c user.name=agent commit -q --allow-empty -m "acceptance: never pushed"'
      sokar task stop {task} >/dev/null 2>&1
      podman container exists {task} && echo "stopping kept it"
      sokar task remove {task}
      podman container exists {task} && echo "a plain remove kept it"
      sokar task remove {task} --force >/dev/null 2>&1
      podman container exists {task} || echo "--force removed it"
      """
    Then its output contains "stopping kept it"
    And its output contains "never reached the gate"
    And its output contains "a plain remove kept it"
    And its output contains "--force removed it"

  Scenario: a running task's clearance is previewed, and asking for what it has changes nothing
    When a task called "cleared" is started in "guarded" for the "stub" agent and left running
    And a script runs "sokar task clearance {task} allow --dry-run" about the task
    Then it exits zero
    And its output contains "would change clearance deny -> allow"
    When a script runs "sokar task clearance {task} deny" about the task
    Then it exits zero
    And its output contains "clearance already deny, unchanged"
