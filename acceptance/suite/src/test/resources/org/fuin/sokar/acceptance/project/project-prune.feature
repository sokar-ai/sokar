Feature: What nothing on a machine owns any more is shown, and removed only when asked

  A project whose file is gone, and what is kept for a task whose container is gone, are left over. They are
  shown, removed with --yes, and what holds work nobody has looked at stays unless --including-work.

  Scenario: leftovers are shown first, removed with --yes, and a push nobody reviewed only with --including-work
    When a script runs:
      """
      base=$(mktemp -d)
      data="${XDG_DATA_HOME:-$HOME/.local/share}/sokar"
      state="${XDG_STATE_HOME:-$HOME/.local/state}/sokar"
      left="prune-left-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      work="prune-work-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      gone="sokar-$left-shell-1"
      mkdir -p "$data/projects" "$data/mirrors" "$state/tasks/$gone"
      # As September left it: a registry entry into a deleted directory, and a mirror with nothing in it.
      echo "$base/deleted/project.yml" > "$data/projects/$left"
      git init -q --bare "$data/mirrors/$left.git"
      # A project whose mirror holds a push nobody reviewed.
      echo "$base/deleted/project.yml" > "$data/projects/$work"
      git init -q --bare "$data/mirrors/$work.git"
      git init -q "$base/work" && git -C "$base/work" -c user.name=T -c user.email=t@example.org \
          commit -q --allow-empty -m 'work nobody reviewed'
      git -C "$base/work" push -q "$data/mirrors/$work.git" "HEAD:refs/sokar/incoming/shell-1"
      # What a task left whose container is gone, untouched for an hour.
      touch -d '1 hour ago' "$state/tasks/$gone"
      sokar prune > "$base/shown"
      sed -n '/^Would be removed/,/^$/p' "$base/shown" | grep -c "^  project $left\$" | sed 's/^/shown: /'
      sed -n '/^Would be removed/,/^$/p' "$base/shown" | grep -c "^  task $gone - " | sed 's/^/shown the task: /'
      sed -n '/^Kept/,/^$/p' "$base/shown" | grep -A1 "^  project $work\$" | grep -c "pushes nobody reviewed" \
          | sed 's/^/kept: /'
      [ -d "$data/mirrors/$left.git" ] && echo "nothing removed without --yes"
      sokar prune --yes > /dev/null
      [ -d "$data/mirrors/$left.git" ] || echo "the left-over mirror is gone"
      [ -e "$data/projects/$left" ] || echo "its registry entry is gone"
      [ -d "$state/tasks/$gone" ] || echo "the gone task's state is gone"
      [ -d "$data/mirrors/$work.git" ] && echo "the unreviewed push is still there"
      sokar prune --yes --including-work > /dev/null
      [ -d "$data/mirrors/$work.git" ] || echo "and goes with --including-work"
      sokar prune | grep -c "$left\|$work" | sed 's/^/found again: /'
      """
    Then its output contains "shown: 1"
    And its output contains "shown the task: 1"
    And its output contains "kept: 1"
    And its output contains "nothing removed without --yes"
    And its output contains "the left-over mirror is gone"
    And its output contains "its registry entry is gone"
    And its output contains "the gone task's state is gone"
    And its output contains "the unreviewed push is still there"
    And its output contains "and goes with --including-work"
    And its output contains "found again: 0"
